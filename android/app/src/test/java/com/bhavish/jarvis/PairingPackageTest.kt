package com.bhavish.jarvis

import com.bhavish.jarvis.tasks.PairingPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPackageTest {
    private val fingerprint = "A".repeat(64)

    @Test fun roundTripsAComputerPairingPackage() {
        val text = PairingPackage("https://192.168.1.50:8765", fingerprint, "code_12345678").encode()
        assertEquals("https://192.168.1.50:8765", PairingPackage.parse(text).address)
    }

    @Test fun rejectsMalformedOrInsecurePairingText() {
        assertThrows(IllegalArgumentException::class.java) { PairingPackage.parse("not a pairing package") }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPackage.parse(PairingPackage("http://computer", fingerprint, "code_12345678").encode())
        }
    }
}
