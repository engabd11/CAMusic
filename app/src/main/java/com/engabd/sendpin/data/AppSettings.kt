package com.engabd.sendpin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sendpin_settings")

/** Persisted app settings: the Music Assistant server + login for the on-device
 * library (the Sendspin player socket itself needs no login on a LAN). */
class AppSettings(private val context: Context) {
    companion object {
        private val MA_BASE_URL = stringPreferencesKey("ma_base_url")   // e.g. http://192.168.0.10:8095
        private val MA_USERNAME = stringPreferencesKey("ma_username")
        private val MA_PASSWORD = stringPreferencesKey("ma_password")
    }

    val maBaseUrl: Flow<String> = context.dataStore.data.map { it[MA_BASE_URL] ?: "" }
    val maUsername: Flow<String> = context.dataStore.data.map { it[MA_USERNAME] ?: "" }
    val maPassword: Flow<String> = context.dataStore.data.map { it[MA_PASSWORD] ?: "" }

    suspend fun setMa(baseUrl: String, username: String, password: String) {
        context.dataStore.edit {
            it[MA_BASE_URL] = baseUrl
            it[MA_USERNAME] = username
            it[MA_PASSWORD] = password
        }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        context.dataStore.edit { it[MA_BASE_URL] = baseUrl }
    }
}
