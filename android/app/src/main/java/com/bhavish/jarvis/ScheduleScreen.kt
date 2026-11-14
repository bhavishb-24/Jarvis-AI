package com.bhavish.jarvis

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.bhavish.jarvis.data.ScheduledItemEntity
import com.bhavish.jarvis.tasks.ActionPlan
import com.bhavish.jarvis.tasks.ComputerPlanner
import com.bhavish.jarvis.tasks.ScheduledItemWorker
import java.util.UUID
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun ScheduleScreen(app: JarvisApplication) {
    val scope = rememberCoroutineScope()
    val devices by app.database.computers().observeAll().collectAsState(initial = emptyList())
    val items by app.database.schedules().observeAll().collectAsState(initial = emptyList())
    var kind by remember { mutableStateOf("reminder") }
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("15") }
    var repeat by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<ActionPlan?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message = "Notification permission denied; reminders will be stored but cannot appear as notifications"
    }
    val selected = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Routines and reminders")
                Row {
                    OutlinedButton(onClick = { kind = "reminder" }) { Text(if (kind == "reminder") "✓ Reminder" else "Reminder") }
                    OutlinedButton(onClick = { kind = "routine" }) { Text(if (kind == "routine") "✓ Routine" else "Routine") }
                }
                OutlinedTextField(title, { title = it; plan = null },
                    label = { Text(if (kind == "reminder") "Reminder text" else "Computer task") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) },
                    label = { Text("Start in minutes") }, modifier = Modifier.fillMaxWidth())
                Row { Checkbox(repeat, { repeat = it }); Text("Repeat every this many minutes (minimum 15)", Modifier.padding(top = 12.dp)) }
                if (kind == "routine") {
                    devices.forEach { device ->
                        OutlinedButton(onClick = { selectedId = device.id }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (selected?.id == device.id) "✓ " else ""}${device.name}")
                        }
                    }
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            try { plan = ComputerPlanner(app).plan(title); message = "Review the routine plan" }
                            catch (error: Exception) { message = error.message ?: "Planning failed" }
                            finally { busy = false }
                        }
                    }, enabled = !busy && selected != null && title.isNotBlank()) { Text("Plan routine") }
                    plan?.let { candidate ->
                        Text(candidate.summary)
                        candidate.steps.forEachIndexed { index, step -> Text("${index + 1}. ${step.action} ${step.args}${if (step.sensitive) " · needs approval each run" else ""}") }
                    }
                }
                Button(onClick = {
                    val delay = minutes.toIntOrNull() ?: 0
                    if (delay < 1 || (repeat && delay < 15)) { message = "Use at least 1 minute, or 15 minutes for repeats"; return@Button }
                    val candidate = plan
                    if (kind == "routine" && (candidate == null || selected == null)) { message = "Plan a routine and choose a computer"; return@Button }
                    val steps = JSONArray()
                    candidate?.steps?.forEach { step -> steps.put(JSONObject().put("action", step.action)
                        .put("args", step.args).put("sensitive", step.sensitive)) }
                    val item = ScheduledItemEntity(UUID.randomUUID().toString(), kind, title.trim(),
                        if (kind == "routine") selected!!.id else "", steps.toString(),
                        System.currentTimeMillis() + delay * 60_000L, if (repeat) delay else 0)
                    scope.launch {
                        app.database.schedules().save(item)
                        ScheduledItemWorker.schedule(app, item)
                        message = "Scheduled"
                        if (kind == "reminder" && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        title = ""; plan = null
                    }
                }, enabled = title.isNotBlank() && !busy) { Text("Schedule") }
                if (message.isNotBlank()) Text(message)
            }
        }
        items(items, key = { it.id }) { item ->
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${item.kind}: ${item.title}")
                Text("${if (item.enabled) "Active" else "Completed"} · ${if (item.repeatMinutes > 0) "every ${item.repeatMinutes} min" else "once"}")
                OutlinedButton(onClick = { scope.launch {
                    ScheduledItemWorker.cancel(app, item.id)
                    app.database.schedules().delete(item.id)
                } }) { Text("Remove") }
            }
        }
    }
}
