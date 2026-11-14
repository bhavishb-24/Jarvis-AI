package com.bhavish.jarvis

import com.bhavish.jarvis.home.HomeAssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeAssistantConfigTest {
    @Test fun requiresHttpsOriginAndLightEntity() {
        assertEquals("home.example", HomeAssistantConfig.validateUrl("https://home.example").host)
        assertEquals("light.desk", HomeAssistantConfig.validateLightEntity("light.desk"))
        assertThrows(IllegalArgumentException::class.java) { HomeAssistantConfig.validateUrl("http://home.example") }
        assertThrows(IllegalArgumentException::class.java) { HomeAssistantConfig.validateLightEntity("switch.desk") }
    }
}
