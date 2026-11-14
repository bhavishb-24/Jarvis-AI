package com.bhavish.jarvis.tasks

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bhavish.jarvis.JarvisApplication
import com.bhavish.jarvis.R
import com.bhavish.jarvis.data.AgentTaskEntity
import com.bhavish.jarvis.data.AgentTaskStepEntity
import com.bhavish.jarvis.data.ScheduledItemEntity
import com.bhavish.jarvis.data.TaskEventEntity
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray

class ScheduledItemWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as JarvisApplication
        val id = inputData.getString("schedule_id") ?: return Result.failure()
        val schedule = app.database.schedules().get(id) ?: return Result.success()
        if (!schedule.enabled) return Result.success()
        if (schedule.kind == "reminder") {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("jarvis_reminders", "JARVIS reminders", NotificationManager.IMPORTANCE_DEFAULT))
            if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                val notice = NotificationCompat.Builder(applicationContext, "jarvis_reminders")
                    .setSmallIcon(R.drawable.ic_jarvis)
                    .setContentTitle("JARVIS reminder")
                    .setContentText(schedule.title.take(200))
                    .setAutoCancel(true).build()
                manager.notify(id.hashCode(), notice)
            }
        } else if (schedule.kind == "routine") {
            val device = app.database.computers().get(schedule.deviceId) ?: return Result.failure()
            if (app.vault.deviceToken(device.id).isBlank()) return Result.failure()
            val steps = JSONArray(schedule.stepsJson)
            if (steps.length() !in 1..8) return Result.failure()
            val taskId = UUID.randomUUID().toString()
            val planned = (0 until steps.length()).map { index ->
                val step = steps.getJSONObject(index)
                val action = step.getString("action")
                if (action !in ActionPlanParser.allowedActions) return Result.failure()
                AgentTaskStepEntity(taskId = taskId, position = index, action = action,
                    argsJson = step.getJSONObject("args").toString(), sensitive = step.getBoolean("sensitive"))
            }
            val needsApproval = planned.any { it.sensitive }
            app.database.agentTasks().save(AgentTaskEntity(id = taskId, deviceId = device.id,
                request = schedule.title, summary = schedule.title, total = steps.length(),
                status = if (needsApproval) "awaiting_approval" else "queued"))
            app.database.agentTasks().addSteps(planned)
            app.database.agentTasks().addEvent(TaskEventEntity(taskId = taskId,
                message = "Created by routine ${schedule.title}"))
            if (!needsApproval) ComputerTaskWorker.enqueue(app, taskId)
        } else return Result.failure()
        app.database.schedules().save(schedule.copy(lastRunAt = System.currentTimeMillis(),
            dueAt = if (schedule.repeatMinutes > 0) System.currentTimeMillis() + schedule.repeatMinutes * 60_000L else schedule.dueAt,
            enabled = schedule.repeatMinutes > 0))
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, item: ScheduledItemEntity) {
            val manager = WorkManager.getInstance(context)
            val input = workDataOf("schedule_id" to item.id)
            val delay = (item.dueAt - System.currentTimeMillis()).coerceAtLeast(0)
            if (item.repeatMinutes > 0) {
                val request = PeriodicWorkRequestBuilder<ScheduledItemWorker>(
                    item.repeatMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(input).build()
                manager.enqueueUniquePeriodicWork("schedule-${item.id}", ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                val request = OneTimeWorkRequestBuilder<ScheduledItemWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(input)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
                manager.enqueueUniqueWork("schedule-${item.id}", ExistingWorkPolicy.REPLACE, request)
            }
        }
        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("schedule-$id")
        }
    }
}
