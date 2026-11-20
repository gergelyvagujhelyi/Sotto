package com.sotto.app.di

import com.sotto.app.crypto.AndroidClassicalKeyAgreement
import com.sotto.app.crypto.BouncyCastlePqcProvider
import com.sotto.app.crypto.ClassicalKeyAgreement
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.crypto.ratchet.RatchetSessionManager
import com.sotto.app.domain.repository.CryptoEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {

    /**
     * Double Ratchet-based crypto engine with per-message forward secrecy and
     * break-in recovery. Combines hybrid X25519 + ML-KEM key establishment with
     * a Signal-style ratchet for ongoing message encryption.
     */
    @Binds
    @Singleton
    abstract fun bindCryptoEngine(impl: RatchetSessionManager): CryptoEngine

    @Binds
    @Singleton
    abstract fun bindClassicalKeyAgreement(impl: AndroidClassicalKeyAgreement): ClassicalKeyAgreement

    /** Production ML-KEM-768 via Bouncy Castle 1.79+. */
    @Binds
    @Singleton
    abstract fun bindPqcProvider(impl: BouncyCastlePqcProvider): PqcProvider
}
