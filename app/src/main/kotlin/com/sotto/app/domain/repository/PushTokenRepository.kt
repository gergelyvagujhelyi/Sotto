package com.sotto.app.domain.repository

/**
 * This device's push registration with the relay. Since 0.5.5 the relay addresses the
 * device by its Firebase installation ID; FCM registration tokens are deprecated.
 */
interface PushTokenRepository {
    /**
     * Register with FCM and tell the server this device's installation ID, if we have an
     * identity. Safe to call repeatedly (app start, after onboarding); failures are logged
     * and retried on the next call. Without Google Play services nothing is registered.
     */
    suspend fun syncPushRegistration()

    /** Tell the server the installation ID FCM registered (FirebaseMessagingService.onRegistered). */
    suspend fun registerInstallationId(installationId: String)

    /** Forget the locally stored registration (local data wipe). */
    suspend fun clearLocal()
}
