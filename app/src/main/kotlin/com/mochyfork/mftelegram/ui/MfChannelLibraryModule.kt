package com.mochyfork.mftelegram.ui

import android.content.Context
import com.mochyfork.mftelegram.TelegramEngine
import com.mochyy.mochymusic.di.ApplicationScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MfChannelLibraryModule {
    @Provides
    @Singleton
    fun provideTelegramEngine(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ) = TelegramEngine(context, scope)

    @Provides
    @Singleton
    fun provideMfChannelLibraryGateway(
        engine: TelegramEngine,
        keyStore: MfApiKeyStore,
        @ApplicationContext context: Context,
    ): MfChannelLibraryGateway = TelegramMfChannelLibraryGateway(engine, keyStore, context)

    @Provides
    @Singleton
    fun provideMfApiKeyStore(
        store: KeystoreMfApiKeyStore,
    ): MfApiKeyStore = store
}
