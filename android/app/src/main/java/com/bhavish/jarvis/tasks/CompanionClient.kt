package com.bhavish.jarvis.tasks

import com.bhavish.jarvis.data.ComputerEntity
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CompanionHttpException(val status: Int, message: String) : IOException(message)

/** TLS identity is verified against the fingerprint read from the computer's local console. */
class CompanionClient {
    companion object {
        fun canonicalFingerprint(value: String): String {
        val clean = value.replace(":", "").replace(" ", "").uppercase()
        require(clean.matches(Regex("[0-9A-F]{64}"))) { "Enter the 64-character SHA-256 fingerprint" }
        return clean
    }

        fun validateAddress(value: String): String {
            val url = URL(value.trim())
            require(url.protocol == "https" && url.userInfo == null && url.host.isNotBlank()) {
                "A valid HTTPS address is required"
            }
            return value.trimEnd('/')
        }
    }

    private fun connection(base: String, fingerprint: String, path: String, token: String): HttpsURLConnection {
        val url = URL(validateAddress(base) + path)
        val expected = canonicalFingerprint(fingerprint)
        val verifier = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.size != 1) throw CertificateException("Unexpected certificate chain")
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02X".format(it) }
                if (actual != expected) throw CertificateException("Computer certificate fingerprint changed")
                chain[0].checkValidity()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(verifier), null) }
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = tls.socketFactory
            // The manually verified certificate fingerprint is the peer identity, including over ADB reverse.
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                try {
                    val cert = session.peerCertificates.single() as X509Certificate
                    val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                        .joinToString("") { "%02X".format(it) }
                    actual == expected
                } catch (_: Exception) { false }
            }
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun exchange(base: String, fingerprint: String, path: String, token: String,
                         method: String, body: JSONObject? = null): JSONObject {
        val conn = connection(base, fingerprint, path, token)
        try {
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val payload = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result = JSONObject(payload)
            if (status !in 200..299) throw CompanionHttpException(status, result.optString("error", "HTTP $status"))
            return result
        } finally { conn.disconnect() }
    }

    private fun usbUrl(device: ComputerEntity) = "https://127.0.0.1:${device.usbPort}"

    private fun request(device: ComputerEntity, token: String, path: String, method: String,
                        body: JSONObject? = null): JSONObject {
        val urls = if (device.useUsb) listOf(usbUrl(device), device.wifiUrl).filter { it.isNotBlank() }
                   else listOf(device.wifiUrl)
        var last: IOException? = null
        for ((index, url) in urls.withIndex()) {
            try { return exchange(url, device.fingerprint, path, token, method, body) }
            catch (error: CompanionHttpException) { throw error }
            catch (error: IOException) {
                last = error
                if (index == urls.lastIndex) throw error
            }
        }
        throw last ?: IOException("No computer address configured")
    }

    suspend fun pair(url: String, fingerprint: String, code: String): JSONObject = withContext(Dispatchers.IO) {
        exchange(url, fingerprint, "/v1/pair", "", "POST", JSONObject().put("code", code.trim()))
    }

    suspend fun status(device: ComputerEntity, token: String): JSONObject = withContext(Dispatchers.IO) {
        request(device, token, "/v1/status", "GET")
    }

    suspend fun submit(device: ComputerEntity, token: String, taskId: String, steps: org.json.JSONArray): JSONObject = withContext(Dispatchers.IO) {
        request(device, token, "/v1/tasks", "POST", JSONObject().put("task_id", taskId).put("steps", steps))
    }

    suspend fun task(device: ComputerEntity, token: String, taskId: String): JSONObject = withContext(Dispatchers.IO) {
        request(device, token, "/v1/tasks/$taskId", "GET")
    }

    suspend fun cancel(device: ComputerEntity, token: String, taskId: String): JSONObject = withContext(Dispatchers.IO) {
        request(device, token, "/v1/tasks/$taskId/cancel", "POST", JSONObject())
    }

    suspend fun revoke(device: ComputerEntity, token: String): JSONObject = withContext(Dispatchers.IO) {
        request(device, token, "/v1/devices/${device.id}", "DELETE")
    }

    suspend fun artifact(device: ComputerEntity, token: String, artifactId: String): ByteArray = withContext(Dispatchers.IO) {
        require(artifactId.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid artifact ID" }
        val urls = if (device.useUsb) listOf(usbUrl(device), device.wifiUrl).filter { it.isNotBlank() }
                   else listOf(device.wifiUrl)
        var last: IOException? = null
        for ((index, url) in urls.withIndex()) {
            val conn = connection(url, device.fingerprint, "/v1/artifacts/$artifactId", token)
            try {
                if (conn.responseCode != 200) throw CompanionHttpException(conn.responseCode, "Artifact unavailable")
                if (conn.contentLengthLong > 8L * 1024 * 1024) throw IOException("Artifact too large")
                val output = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        if (output.size() > 8 * 1024 * 1024) throw IOException("Artifact too large")
                    }
                }
                val bytes = output.toByteArray()
                if (bytes.size > 8 * 1024 * 1024) throw IOException("Artifact too large")
                return@withContext bytes
            } catch (error: CompanionHttpException) { throw error }
            catch (error: IOException) { last = error; if (index == urls.lastIndex) throw error }
            finally { conn.disconnect() }
        }
        throw last ?: IOException("No computer address configured")
    }
}
