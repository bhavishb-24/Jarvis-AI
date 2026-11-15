package com.bhavish.jarvis.tasks

import java.net.URLDecoder
import java.net.URLEncoder

data class PairingPackage(val address: String, val fingerprint: String, val code: String) {
    fun encode(): String = listOf("JARVIS_PAIR_V1", address, fingerprint, code)
        .joinToString("|") { URLEncoder.encode(it, Charsets.UTF_8.name()) }

    companion object {
        fun parse(raw: String): PairingPackage {
            val fields = raw.trim().split('|')
            require(fields.size == 4 && decode(fields[0]) == "JARVIS_PAIR_V1") { "Invalid JARVIS pairing text" }
            val result = PairingPackage(decode(fields[1]), decode(fields[2]), decode(fields[3]))
            CompanionClient.validateAddress(result.address)
            CompanionClient.canonicalFingerprint(result.fingerprint)
            require(result.code.length in 8..128 && !result.code.any { it.isWhitespace() }) { "Invalid pairing code" }
            return result
        }

        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}
