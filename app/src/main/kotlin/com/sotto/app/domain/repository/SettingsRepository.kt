package com.sotto.app.domain.repository

import com.sotto.app.domain.model.PrivacySettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getPrivacySettings(): Flow<PrivacySettings>
    suspend fun updatePrivacySettings(settings: PrivacySettings)
    suspend fun wipeLocalData()
    suspend fun retryServerDeletion()
    suspend fun wipeLocalOnly()
}
