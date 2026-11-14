package com.bhavish.jarvis

import android.app.Application
import androidx.room.Room
import com.bhavish.jarvis.data.JarvisDatabase
import com.bhavish.jarvis.data.KeyVault
import com.bhavish.jarvis.data.SettingsStore
import com.bhavish.jarvis.provider.ProviderManager

class JarvisApplication : Application() {
    val database: JarvisDatabase by lazy {
        Room.databaseBuilder(this, JarvisDatabase::class.java, "jarvis.db")
            .addMigrations(JarvisDatabase.MIGRATION_1_2, JarvisDatabase.MIGRATION_2_3).build()
    }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val vault: KeyVault by lazy { KeyVault(this) }
    val providers: ProviderManager by lazy { ProviderManager() }
    val conversation: ConversationService by lazy {
        ConversationService(database.conversations(), settings, vault, providers)
    }
}
