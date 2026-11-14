package com.bhavish.jarvis

import com.bhavish.jarvis.tasks.CapabilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CapabilityPolicyTest {
    @Test fun groupsKnownActions() {
        assertEquals("file", CapabilityPolicy.group("file.edit"))
        assertEquals("browser", CapabilityPolicy.group("browser.extract"))
    }

    @Test fun rejectsUnknownActions() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityPolicy.group("shell.run") }
    }
}
