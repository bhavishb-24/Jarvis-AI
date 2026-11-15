package com.bhavish.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import com.bhavish.jarvis.data.AgentTaskEntity
import com.bhavish.jarvis.data.AgentTaskStepEntity
import com.bhavish.jarvis.data.ComputerEntity
import com.bhavish.jarvis.data.TaskEventEntity
import com.bhavish.jarvis.tasks.ActionPlan
import com.bhavish.jarvis.tasks.CompanionClient
import com.bhavish.jarvis.tasks.CompanionHttpException
import com.bhavish.jarvis.tasks.ComputerPlanner
import com.bhavish.jarvis.tasks.ComputerTaskWorker
import com.bhavish.jarvis.tasks.PairingPackage
import kotlinx.coroutines.launch
import java.util.UUID
import org.json.JSONObject

@Composable
internal fun DevicesScreen(app: JarvisApplication) {
    val scope = rememberCoroutineScope()
    val devices by app.database.computers().observeAll().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pairingText by remember { mutableStateOf("") }
    var usb by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pair a computer")
                Text("Paste the single JARVIS pairing text from the computer, then pair. The detailed fields remain available if needed.")
                OutlinedTextField(pairingText, { pairingText = it }, label = { Text("JARVIS pairing text") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    try {
                        val packageInfo = PairingPackage.parse(pairingText)
                        address = packageInfo.address
                        fingerprint = packageInfo.fingerprint
                        code = packageInfo.code
                        message = "Pairing details loaded"
                    } catch (error: Exception) { message = error.message ?: "Invalid pairing text" }
                }, enabled = pairingText.isNotBlank()) { Text("Load pairing text") }
                OutlinedTextField(name, { name = it }, label = { Text("Computer name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, label = { Text("HTTPS address") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fingerprint, { fingerprint = it }, label = { Text("Certificate fingerprint") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it }, label = { Text("One-time pairing code") }, modifier = Modifier.fillMaxWidth())
                Row { Checkbox(usb, { usb = it }); Text("Prefer USB with ADB reverse on port 8765", modifier = Modifier.padding(top = 12.dp)) }
                Button(onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        try {
                            val result = CompanionClient().pair(address, fingerprint, code)
                            val id = result.getString("device_id")
                            app.vault.saveDeviceToken(id, result.getString("token"))
                            app.database.computers().save(ComputerEntity(id, name.ifBlank { result.getString("platform") },
                                result.getString("platform"), address.trim(), fingerprint.trim(), useUsb = usb,
                                lastStatus = "paired", lastSeen = System.currentTimeMillis()))
                            code = ""
                            message = "Paired successfully"
                        } catch (error: Exception) { message = error.message ?: "Pairing failed" }
                        finally { busy = false }
                    }
                }, enabled = !busy && address.isNotBlank() && fingerprint.isNotBlank() && code.isNotBlank()) { Text("Pair") }
                if (message.isNotBlank()) Text(message)
            }
        }
        items(devices, key = { it.id }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${device.name} · ${device.platform}")
                    Text("${device.lastStatus} · ${if (device.useUsb) "USB preferred" else "Wi-Fi"}")
                    Text(device.wifiUrl)
                    Row {
                        OutlinedButton(onClick = { scope.launch {
                            try {
                                CompanionClient().status(device, app.vault.deviceToken(device.id))
                                app.database.computers().save(device.copy(lastStatus = "online", lastSeen = System.currentTimeMillis()))
                            } catch (error: Exception) {
                                app.database.computers().save(device.copy(lastStatus = "offline"))
                                message = error.message ?: "Connection failed"
                            }
                        } }) { Text("Check") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { scope.launch {
                            app.database.computers().save(device.copy(useUsb = !device.useUsb))
                        } }) { Text(if (device.useUsb) "Use Wi-Fi" else "Prefer USB") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { scope.launch {
                            try {
                                CompanionClient().revoke(device, app.vault.deviceToken(device.id))
                                app.vault.removeDeviceToken(device.id)
                                app.database.computers().delete(device.id)
                                message = "Connection revoked"
                            } catch (error: Exception) { message = "Revocation failed: ${error.message}" }
                        } }) { Text("Revoke") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TasksScreen(app: JarvisApplication) {
    val scope = rememberCoroutineScope()
    val devices by app.database.computers().observeAll().collectAsState(initial = emptyList())
    val tasks by app.database.agentTasks().observeAll().collectAsState(initial = emptyList())
    var deviceId by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<ActionPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val selected = devices.firstOrNull { it.id == deviceId } ?: devices.firstOrNull()
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Computer tasks")
                Text("Choose a paired computer, describe the task, then review the proposed actions.")
                devices.forEach { device ->
                    OutlinedButton(onClick = { deviceId = device.id; plan = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (selected?.id == device.id) "✓ " else ""}${device.name} · ${device.lastStatus}")
                    }
                }
                OutlinedTextField(request, { request = it; plan = null }, label = { Text("What should JARVIS do?") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        try { plan = ComputerPlanner(app).plan(request, selected?.id); notice = "Review the actions below" }
                        catch (error: Exception) { notice = error.message ?: "Planning failed" }
                        finally { busy = false }
                    }
                }, enabled = !busy && selected != null && request.isNotBlank()) { Text("Create plan") }
                if (notice.isNotBlank()) Text(notice)
                plan?.let { candidate ->
                    Text(candidate.summary)
                    candidate.steps.forEachIndexed { index, step ->
                        Text("${index + 1}. ${step.action} ${step.args} ${if (step.sensitive) "· approval required" else ""}")
                    }
                    Button(onClick = {
                        val device = selected ?: return@Button
                        busy = true
                        scope.launch {
                            try {
                                val id = UUID.randomUUID().toString()
                                val needsApproval = candidate.steps.any { it.sensitive }
                                app.database.agentTasks().save(AgentTaskEntity(id = id, deviceId = device.id,
                                    request = request.trim(), summary = candidate.summary, total = candidate.steps.size,
                                    status = if (needsApproval) "awaiting_approval" else "queued"))
                                app.database.agentTasks().addSteps(candidate.steps.mapIndexed { index, step ->
                                    AgentTaskStepEntity(taskId = id, position = index, action = step.action,
                                        argsJson = step.args.toString(), sensitive = step.sensitive)
                                })
                                app.database.agentTasks().addEvent(TaskEventEntity(taskId = id, message = "Plan created on phone"))
                                if (!needsApproval) ComputerTaskWorker.enqueue(app, id)
                                plan = null
                                notice = if (needsApproval) "Review and approve the queued task below" else "Task queued"
                            } catch (error: Exception) { notice = error.message ?: "Could not queue task" }
                            finally { busy = false }
                        }
                    }, enabled = !busy) { Text("Queue plan") }
                }
            }
        }
        items(tasks, key = { it.id }) { task -> TaskCard(app, task, devices.firstOrNull { it.id == task.deviceId }?.name ?: "Removed computer") }
    }
}

@Composable
private fun TaskCard(app: JarvisApplication, task: AgentTaskEntity, computerName: String) {
    val scope = rememberCoroutineScope()
    val steps by remember(task.id) { app.database.agentTasks().observeSteps(task.id) }.collectAsState(initial = emptyList())
    val events by remember(task.id) { app.database.agentTasks().observeEvents(task.id) }.collectAsState(initial = emptyList())
    var message by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(task.summary)
            Text("$computerName · ${task.status} · ${task.progress}/${task.total}")
            steps.forEach { step ->
                Text("${step.position + 1}. ${step.action} · ${step.status}${if (step.sensitive) " · sensitive" else ""}")
                if (step.resultJson.isNotBlank()) {
                    val result = remember(step.resultJson) { runCatching { JSONObject(step.resultJson) }.getOrNull() }
                    val localFile = result?.optJSONObject("data")?.optString("local_file") ?: ""
                    if (localFile.isNotBlank()) {
                        val bitmap = remember(localFile) { BitmapFactory.decodeFile(localFile)?.asImageBitmap() }
                        if (bitmap != null) Image(bitmap, "Computer screenshot", Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit)
                    } else {
                        val detail = if (result?.optBoolean("ok") == false) result.optString("error")
                                     else result?.optJSONObject("data")?.toString() ?: ""
                        if (detail.isNotBlank()) Text(detail.take(500))
                    }
                }
            }
            if (task.error.isNotBlank()) Text(task.error)
            events.takeLast(4).forEach { Text(it.message) }
            if (message.isNotBlank()) Text(message)
            if (task.status == "awaiting_approval") {
                Button(onClick = { scope.launch {
                    app.database.agentTasks().save(task.copy(approved = true, status = "queued", updatedAt = System.currentTimeMillis()))
                    app.database.agentTasks().addEvent(TaskEventEntity(taskId = task.id, message = "Sensitive actions approved on phone"))
                    ComputerTaskWorker.enqueue(app, task.id)
                } }) { Text("Approve and run") }
            }
            if (task.status in setOf("queued", "awaiting_approval", "running")) {
                OutlinedButton(onClick = { scope.launch {
                    try {
                        if (task.status in setOf("queued", "running")) {
                            val device = app.database.computers().get(task.deviceId)
                            if (device != null) {
                                try {
                                    val remote = CompanionClient().cancel(device, app.vault.deviceToken(device.id), task.id)
                                    if (remote.optString("status") in setOf("completed", "failed")) {
                                        message = "Agent already ${remote.optString("status")}; refresh task history"
                                        return@launch
                                    }
                                }
                                catch (error: CompanionHttpException) { if (error.status != 404) throw error }
                            }
                        }
                        ComputerTaskWorker.cancel(app, task.id)
                        app.database.agentTasks().update(task.id, "cancelled", task.progress, task.total, "")
                        app.database.agentTasks().addEvent(TaskEventEntity(taskId = task.id, message = "Cancelled on phone"))
                    } catch (error: Exception) { message = error.message ?: "Cancellation failed" }
                } }) { Text("Cancel") }
            }
        }
    }
}
