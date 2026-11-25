package com.sotto.app.di

import com.sotto.app.data.repository.ConversationRepositoryImpl
import com.sotto.app.data.repository.IdentityRepositoryImpl
import com.sotto.app.data.repository.MessageRepositoryImpl
import com.sotto.app.data.repository.PushTokenRepositoryImpl
import com.sotto.app.data.repository.SettingsRepositoryImpl
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.repository.MessageRepository
import com.sotto.app.domain.repository.PushTokenRepository
import com.sotto.app.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: com.sotto.app.data.repository.GroupRepositoryImpl): com.sotto.app.domain.repository.GroupRepository

    @Binds
    @Singleton
    abstract fun bindGroupTransport(impl: MessageRepositoryImpl): com.sotto.app.data.repository.GroupTransport

    @Binds
    @Singleton
    abstract fun bindMemberKeys(impl: com.sotto.app.data.repository.GroupKeys): com.sotto.app.data.repository.MemberKeys

    @Binds
    @Singleton
    abstract fun bindGroupSignatures(impl: com.sotto.app.data.repository.AccountGroupSignatures): com.sotto.app.group.GroupSignatures

    @Binds
    @Singleton
    abstract fun bindGroupEnvironment(impl: com.sotto.app.data.repository.AndroidGroupEnvironment): com.sotto.app.data.repository.GroupEnvironment

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindIdentityRepository(impl: IdentityRepositoryImpl): IdentityRepository

    @Binds
    @Singleton
    abstract fun bindPushTokenRepository(impl: PushTokenRepositoryImpl): PushTokenRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
