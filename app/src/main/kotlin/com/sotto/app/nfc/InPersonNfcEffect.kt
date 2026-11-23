package com.sotto.app.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sotto.app.crypto.InPersonCode
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * While the calling screen is in front: answer a tap with [code] as a card, and every now and
 * then read as a reader, for a random while, so two phones held together soon find each other
 * in opposite roles and one tap swaps both codes ([InPersonNfc]).
 *
 * Reader mode, not foreground dispatch: Android's tag dispatch first checks the other phone for
 * an NDEF message, and on some phones that check broke the link before Sotto's exchange ran, so
 * the first tap did nothing (seen on the owner's phone with the Note 9, 0.7.10). Reader mode
 * skips that check. It also stops this phone answering as a card while it's on, hence the
 * alternating, with random lengths so two phones don't stay in step.
 */
@Composable
fun InPersonNfcEffect(code: InPersonCode?) {
    val activity = LocalContext.current as? Activity ?: return
    val adapter = remember { NfcAdapter.getDefaultAdapter(activity) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(code, adapter, lifecycleOwner) {
        if (code == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            InPersonNfc.start(code)
            try {
                if (adapter == null) {
                    // No NFC: nothing to read with; the QR code is the way.
                    kotlinx.coroutines.awaitCancellation()
                }
                while (true) {
                    // Answer as a card…
                    delay(Random.nextLong(CARD_MS_MIN, CARD_MS_MAX))
                    if (InPersonNfc.isBusy) continue
                    // …then read for a while, unless an exchange is going on.
                    runCatching { adapter.enableReaderMode(activity, InPersonNfc::readTag, READER_FLAGS, READER_EXTRAS) }
                    delay(Random.nextLong(READER_MS_MIN, READER_MS_MAX))
                    while (InPersonNfc.isBusy) delay(50)
                    runCatching { adapter.disableReaderMode(activity) }
                }
            } finally {
                runCatching { adapter?.disableReaderMode(activity) }
                InPersonNfc.stop()
            }
        }
    }
}

private const val CARD_MS_MIN = 300L
private const val CARD_MS_MAX = 800L
private const val READER_MS_MIN = 300L
private const val READER_MS_MAX = 800L

private const val READER_FLAGS =
    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

private val READER_EXTRAS = Bundle().apply {
    // Don't poll the other phone for presence in the middle of the exchange.
    putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000)
}
