package com.sotto.app.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.sotto.app.BuildConfig
import com.sotto.app.crypto.InPersonCode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException

/**
 * Verifying a contact in person by holding the two phones together: one tap swaps both
 * phones' [InPersonCode]s, so each checks the other at once.
 *
 * Both phones have "Verify in person" (or "Add contact") open. Each answers as a card (host card
 * emulation, [InPersonApduService]) and, now and then, reads in reader mode
 * ([InPersonNfcEffect]), so one finds the other and reads it ([readTag]):
 *
 *     reader → SELECT our AID                      ← 90 00
 *     reader → EXCHANGE with the reader's code     ← the card's code + 90 00
 *
 * The card only answers while its verify screen is open ([start]); otherwise it says the
 * application isn't there, as for any other app's reader. NFC reaches a few centimetres, so a
 * code that arrives this way came from a phone right next to this one.
 */
object InPersonNfc {

    /** Our application ID: proprietary (F0…), "SOTTO" and a version byte. */
    val AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x53, 0x4F, 0x54, 0x54, 0x4F, 0x01)

    private const val CLA_PROPRIETARY = 0x80.toByte()
    private const val INS_EXCHANGE = 0x10.toByte()
    private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    private val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    private val SW_CONDITIONS = byteArrayOf(0x69, 0x85.toByte())
    private val SW_UNKNOWN = byteArrayOf(0x6D, 0x00)
    private const val TIMEOUT_MS = 3_000
    private const val REPEAT_QUIET_MS = 5_000L
    private const val CARD_BUSY_MS = 1_500L

    /** This phone's code while a verify screen is open; null otherwise (the card stays silent). */
    @Volatile
    private var myCode: InPersonCode? = null

    private val _received = MutableSharedFlow<InPersonCode>(extraBufferCapacity = 4)

    /** Reading, or being read: don't switch roles now ([InPersonNfcEffect]). */
    @Volatile
    private var reading = false

    @Volatile
    private var cardActiveUntil = 0L

    val isBusy: Boolean get() = reading || System.currentTimeMillis() < cardActiveUntil

    /** The last code passed on, and when: phones left together swap again and again. */
    @Volatile
    private var lastDelivered: Pair<InPersonCode, Long>? = null

    private fun deliver(code: InPersonCode) {
        val now = System.currentTimeMillis()
        val last = lastDelivered
        if (last != null && last.first == code && now - last.second < REPEAT_QUIET_MS) return
        lastDelivered = code to now
        _received.tryEmit(code)
    }

    /** Codes that came from a phone held against this one, as reader or as card. */
    val received: SharedFlow<InPersonCode> = _received.asSharedFlow()

    val isActive: Boolean get() = myCode != null

    fun start(code: InPersonCode) {
        myCode = code
    }

    fun stop() {
        myCode = null
        lastDelivered = null
    }

    fun selectCommand(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    fun exchangeCommand(code: InPersonCode): ByteArray {
        val data = code.toBytes()
        require(data.size <= 255) { "Code too long for a short APDU" }
        return byteArrayOf(CLA_PROPRIETARY, INS_EXCHANGE, 0x00, 0x00, data.size.toByte()) + data + byteArrayOf(0x00)
    }

    /**
     * The card's answer to [command] (InPersonApduService). An exchange hands the reader's code
     * to [received] and answers with this phone's.
     */
    fun respond(command: ByteArray): ByteArray {
        val code = myCode
        return when {
            isSelect(command) -> if (code != null) {
                cardActiveUntil = System.currentTimeMillis() + CARD_BUSY_MS
                SW_OK
            } else {
                SW_NOT_FOUND
            }
            command.size >= 5 && command[0] == CLA_PROPRIETARY && command[1] == INS_EXCHANGE -> {
                if (code == null) return SW_CONDITIONS
                val length = command[4].toInt() and 0xFF
                if (command.size < 5 + length) return SW_CONDITIONS
                InPersonCode.parse(command.copyOfRange(5, 5 + length))?.let(::deliver)
                cardActiveUntil = 0L
                code.toBytes() + SW_OK
            }
            else -> SW_UNKNOWN
        }
    }

    private fun isSelect(command: ByteArray): Boolean {
        if (command.size < 5 + AID.size || command[0] != 0x00.toByte() || command[1] != 0xA4.toByte()) return false
        val length = command[4].toInt() and 0xFF
        return length == AID.size && command.copyOfRange(5, 5 + length).contentEquals(AID)
    }

    /**
     * Reads the phone behind [tag] (blocking: call off the main thread). Anything that isn't
     * another Sotto with its verify screen open is ignored.
     */
    fun readTag(tag: Tag) {
        val code = myCode ?: return
        val isoDep = IsoDep.get(tag) ?: return
        reading = true
        try {
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS
            if (!isoDep.transceive(selectCommand()).endsWithOk()) return
            val answer = isoDep.transceive(exchangeCommand(code))
            if (!answer.endsWithOk()) return
            InPersonCode.parse(answer.copyOfRange(0, answer.size - 2))?.let(::deliver)
        } catch (e: IOException) {
            // The phones moved apart mid-exchange: the user taps again.
            if (BuildConfig.DEBUG) Log.d("InPersonNfc", "Tap broke off: ${e.message}")
        } catch (e: SecurityException) {
            // The tag went away before connect (Android reports a stale tag this way).
            if (BuildConfig.DEBUG) Log.d("InPersonNfc", "Tag gone: ${e.message}")
        } finally {
            runCatching { isoDep.close() }
            reading = false
        }
    }

    private fun ByteArray.endsWithOk(): Boolean =
        size >= 2 && this[size - 2] == SW_OK[0] && this[size - 1] == SW_OK[1]
}
