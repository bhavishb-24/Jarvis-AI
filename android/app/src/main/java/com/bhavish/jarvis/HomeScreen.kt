package com.bhavish.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bhavish.jarvis.home.HomeAssistantClient
import com.bhavish.jarvis.home.HomeAssistantConfig
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(app: JarvisApplication) {
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(app.settings.homeAssistantUrl) }
    var entity by remember { mutableStateOf(app.settings.deskLightEntity) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Desk lighting")
        Text("Connect a Home Assistant light over HTTPS. Controls work manually even when presence monitoring is off.")
        OutlinedTextField(origin, { origin = it }, label = { Text("Home Assistant HTTPS origin") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(entity, { entity = it }, label = { Text("Light entity, e.g. light.desk") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Long-lived access token (leave blank to keep saved token)") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            try {
                HomeAssistantConfig.validateUrl(origin)
                HomeAssistantConfig.validateLightEntity(entity)
                app.settings.homeAssistantUrl = origin.trimEnd('/')
                app.settings.deskLightEntity = entity
                if (token.isNotBlank()) app.vault.saveHomeAssistantToken(token)
                token = ""
                message = "Saved"
            } catch (error: Exception) { message = error.message ?: "Invalid settings" }
        }) { Text("Save connection") }
        OutlinedButton(onClick = { scope.launch {
            busy = true
            try {
                HomeAssistantClient().setDeskLight(app.settings.homeAssistantUrl,
                    app.vault.homeAssistantToken(), app.settings.deskLightEntity, true)
                message = "Desk light on"
            } catch (error: Exception) { message = error.message ?: "Light command failed" }
            finally { busy = false }
        } }, enabled = !busy) { Text("Turn on") }
        OutlinedButton(onClick = { scope.launch {
            busy = true
            try {
                HomeAssistantClient().setDeskLight(app.settings.homeAssistantUrl,
                    app.vault.homeAssistantToken(), app.settings.deskLightEntity, false)
                message = "Desk light off"
            } catch (error: Exception) { message = error.message ?: "Light command failed" }
            finally { busy = false }
        } }, enabled = !busy) { Text("Turn off") }
        if (message.isNotBlank()) Text(message)
    }
}
