package com.bhavish.jarvis.home

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object HomeAssistantConfig {
    fun validateUrl(value: String): URL {
        val url = URL(value)
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null &&
            url.path.isNullOrEmpty() && url.query == null && url.ref == null) {
            "Enter the HTTPS origin of your Home Assistant server"
        }
        return url
    }

    fun validateLightEntity(value: String): String {
        require(value.matches(Regex("light\\.[a-z0-9_]{1,100}"))) {
            "Choose a light entity such as light.desk"
        }
        return value
    }
}

class HomeAssistantClient {
    suspend fun setDeskLight(origin: String, token: String, entity: String, on: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val base = HomeAssistantConfig.validateUrl(origin)
            val light = HomeAssistantConfig.validateLightEntity(entity)
            require(token.isNotBlank()) { "Add a Home Assistant token" }
            val service = if (on) "turn_on" else "turn_off"
            val url = URL(base.toString().trimEnd('/') + "/api/services/light/$service")
            val conn = url.openConnection() as HttpsURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { output ->
                    output.write(JSONObject().put("entity_id", light).toString().toByteArray(Charsets.UTF_8))
                }
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("Home Assistant returned HTTP ${conn.responseCode}")
                }
                conn.inputStream.close()
            } finally { conn.disconnect() }
        }
}
