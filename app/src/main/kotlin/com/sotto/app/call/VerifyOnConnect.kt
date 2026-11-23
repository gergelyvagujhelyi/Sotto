package com.sotto.app.call

/**
 * "Verify by phone" from the first-message warning: the chat starts a call and asks for the
 * code check to open by itself once that call first connects (CallManager reads it then).
 * Process-wide and one-shot; a call to someone else, or one that ends first, drops it.
 */
object VerifyOnConnect {
    @Volatile
    var peerId: String? = null
}
