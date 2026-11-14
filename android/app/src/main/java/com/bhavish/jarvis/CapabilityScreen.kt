package com.bhavish.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bhavish.jarvis.tasks.CapabilityPolicy

@Composable
internal fun CapabilityScreen(app: JarvisApplication) {
    val devices by app.database.computers().observeAll().collectAsState(initial = emptyList())
    var revision by remember { mutableIntStateOf(0) }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Computer capabilities")
            Text("Choose which built-in action groups each paired computer may execute. Changes also apply to queued tasks and routines.")
        }
        items(devices, key = { it.id }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(device.name)
                    CapabilityPolicy.groups.forEach { group ->
                        // The revision causes Compose to read preferences again after a toggle.
                        val enabled = remember(device.id, group, revision) {
                            app.settings.capabilityGroupEnabled(device.id, group)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(group.replaceFirstChar { it.uppercase() }, Modifier.padding(top = 10.dp))
                            Switch(checked = enabled, onCheckedChange = {
                                app.settings.setCapabilityGroup(device.id, group, it)
                                revision++
                            })
                        }
                    }
                }
            }
        }
    }
}
