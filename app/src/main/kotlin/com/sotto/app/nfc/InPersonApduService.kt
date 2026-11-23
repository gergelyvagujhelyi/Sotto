package com.sotto.app.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * The card side of an in-person NFC tap ([InPersonNfc]). Android routes our AID here only while
 * the phone is unlocked (res/xml/in_person_apdu_service.xml), and it answers only while a
 * verify screen is open.
 */
class InPersonApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray =
        InPersonNfc.respond(commandApdu)

    override fun onDeactivated(reason: Int) = Unit
}
