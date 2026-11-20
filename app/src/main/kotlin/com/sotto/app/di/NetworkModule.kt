package com.sotto.app.di

import com.sotto.app.BuildConfig
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.KtorApiService
import com.sotto.app.data.remote.MockApiService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Network module — swap between mock and real API service here.
 *
 * For development without a server: use MockApiService.
 * For integration with the Python relay: use KtorApiService.
 *
 * Toggle by changing which @Binds method is active.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    /**
     * Real HTTP client for the Python FastAPI relay server.
     * Talks to the relay chosen at setup (RelayConfig).
     */
    @Binds
    @Singleton
    abstract fun bindApiService(impl: KtorApiService): ApiService

    /**
     * In-process mock for development — no server needed.
     */
    // @Binds
    // @Singleton
    // abstract fun bindApiService(impl: MockApiService): ApiService
}
