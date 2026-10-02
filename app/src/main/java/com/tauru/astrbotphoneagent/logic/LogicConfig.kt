package com.tauru.astrbotphoneagent.logic

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tauru.astrbotphoneagent.data.ConnectionSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.logicDataStore by preferencesDataStore(name = "logic_config")

/**
 * Private app configuration. Public builds start without server addresses,
 * identities, or credentials; the settings page provisions them after install.
 */
class LogicConfig(private val context: Context) {

    private object Keys {
        val astrbotBaseUrl = stringPreferencesKey("astrbot_base_url")
        val openApiKey = stringPreferencesKey("openapi_key")
        val chatUsername = stringPreferencesKey("chat_username")
        val chatSession = stringPreferencesKey("chat_session")
        val pluginBaseUrl = stringPreferencesKey("plugin_base_url")
        val pluginJwt = stringPreferencesKey("plugin_jwt")
        val sharedToken = stringPreferencesKey("shared_token")
        val relayBaseUrl = stringPreferencesKey("relay_base_url")
        val relayToken = stringPreferencesKey("relay_token")
        val deviceName = stringPreferencesKey("device_name")
    }

    companion object {
        const val DEFAULT_ASTRBOT_URL = ""
        // Plugin routes live behind the dashboard's /api/plug/ proxy and
        // authenticate with a dashboard JWT (the OpenAPI abk_ key is not
        // accepted on that router). registerDevice sends this Bearer token.
        const val DEFAULT_PLUGIN_URL = ""
        // Configure the relay endpoint in the app settings when needed.
        const val DEFAULT_RELAY_URL = ""
        const val DEFAULT_RELAY_TOKEN = ""
        const val DEFAULT_SHARED_TOKEN = ""
        val DEFAULT_USERNAME = ""
        const val DEFAULT_SESSION = "phone_app"
        const val APP_VERSION = "0.2.0"
        // The dashboard token is intentionally provisioned after installation.
        const val DEFAULT_DASHBOARD_JWT = ""
        // The OpenAPI key is intentionally provisioned after installation.
        const val DEFAULT_OPENAPI_KEY = ""
    }

    val astrbotBaseUrl: Flow<String> = context.logicDataStore.data.map { it[Keys.astrbotBaseUrl] ?: DEFAULT_ASTRBOT_URL }
    val openApiKey: Flow<String> = context.logicDataStore.data.map { it[Keys.openApiKey] ?: DEFAULT_OPENAPI_KEY }
    val chatUsername: Flow<String> = context.logicDataStore.data.map { it[Keys.chatUsername] ?: DEFAULT_USERNAME }
    val chatSession: Flow<String> = context.logicDataStore.data.map { it[Keys.chatSession] ?: DEFAULT_SESSION }
    val pluginBaseUrl: Flow<String> = context.logicDataStore.data.map { it[Keys.pluginBaseUrl] ?: DEFAULT_PLUGIN_URL }
    val pluginJwt: Flow<String> = context.logicDataStore.data.map { it[Keys.pluginJwt] ?: DEFAULT_DASHBOARD_JWT }
    val sharedTokenFlow: Flow<String> = context.logicDataStore.data.map { it[Keys.sharedToken] ?: DEFAULT_SHARED_TOKEN }
    val relayBaseUrl: Flow<String> = context.logicDataStore.data.map { it[Keys.relayBaseUrl] ?: DEFAULT_RELAY_URL }
    val relayToken: Flow<String> = context.logicDataStore.data.map { it[Keys.relayToken] ?: DEFAULT_RELAY_TOKEN }
    val deviceName: Flow<String> = context.logicDataStore.data.map { it[Keys.deviceName] ?: "phone" }

    val connectionSettings: Flow<ConnectionSettings> = context.logicDataStore.data.map { prefs ->
        ConnectionSettings(
            serverUrl = prefs[Keys.astrbotBaseUrl] ?: DEFAULT_ASTRBOT_URL,
            pluginUrl = prefs[Keys.pluginBaseUrl] ?: DEFAULT_PLUGIN_URL,
            relayUrl = prefs[Keys.relayBaseUrl] ?: DEFAULT_RELAY_URL,
            chatUsername = prefs[Keys.chatUsername] ?: DEFAULT_USERNAME,
            apiKeyConfigured = (prefs[Keys.openApiKey] ?: DEFAULT_OPENAPI_KEY).isNotBlank(),
            pluginTokenConfigured = (prefs[Keys.pluginJwt] ?: DEFAULT_DASHBOARD_JWT).isNotBlank(),
            sharedTokenConfigured = (prefs[Keys.sharedToken] ?: DEFAULT_SHARED_TOKEN).isNotBlank(),
            relayTokenConfigured = (prefs[Keys.relayToken] ?: DEFAULT_RELAY_TOKEN).isNotBlank(),
        )
    }

    val appVersion: String get() = com.tauru.astrbotphoneagent.BuildConfig.VERSION_NAME

    suspend fun sharedToken(): String = sharedTokenFlow.first()
    suspend fun astrbotUrl(): String = astrbotBaseUrl.first().trimEnd('/')
    suspend fun apiKey(): String = openApiKey.first()
    suspend fun username(): String = chatUsername.first()
    suspend fun session(): String = DEFAULT_SESSION
    suspend fun pluginUrl(): String = pluginBaseUrl.first().trimEnd('/')
    suspend fun pluginJwt(): String = pluginJwt.first()
    suspend fun relayUrl(): String = relayBaseUrl.first().trimEnd('/')
    suspend fun relayTokenValue(): String = relayToken.first()

    suspend fun update(updates: Map<String, String>) {
        context.logicDataStore.edit { prefs ->
            for ((key, value) in updates) {
                val prefKey = when (key) {
                    "astrbot_base_url" -> Keys.astrbotBaseUrl
                    "openapi_key" -> Keys.openApiKey
                    "chat_username" -> Keys.chatUsername
                    "chat_session" -> Keys.chatSession
                    "plugin_base_url" -> Keys.pluginBaseUrl
                    "plugin_jwt" -> Keys.pluginJwt
                    "shared_token" -> Keys.sharedToken
                    "relay_base_url" -> Keys.relayBaseUrl
                    "relay_token" -> Keys.relayToken
                    "device_name" -> Keys.deviceName
                    else -> null
                }
                if (prefKey != null) prefs[prefKey] = value
            }
        }
    }
}
