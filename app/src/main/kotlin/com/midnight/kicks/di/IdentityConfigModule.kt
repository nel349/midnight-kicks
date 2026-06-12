package com.midnight.kicks.di

import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Midnight Kicks's passkey relying-party config.
 *
 * The SDK provides no default `PasskeyConfig` (wishlist #22) — `rpId` is the
 * passkey domain and must match the `assetlinks.json` this app hosts, so each
 * consuming app declares its own. Kicks uses the shared dev DAL host for now;
 * `rpName` is what shows in the biometric prompt.
 */
@Module
@InstallIn(SingletonComponent::class)
object IdentityConfigModule {

    @Provides
    @Singleton
    fun providePasskeyConfig(): PasskeyConfig = PasskeyConfig(
        rpId = RP_ID,
        rpName = RP_NAME,
    )

    // Canonical Kuira sigil relying-party host (GitHub Pages, org-owned).
    // Shared across the Kuira example apps so one sigil restores across all of
    // them; the matching assetlinks.json lists every app's package + cert.
    private const val RP_ID = "kuiralabs.github.io"
    private const val RP_NAME = "Midnight Kicks"
}
