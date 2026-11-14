package com.bhavish.jarvis.tasks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import com.bhavish.jarvis.JarvisApplication
import com.bhavish.jarvis.data.TaskEventEntity
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ComputerTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as JarvisApplication
        val dao = app.database.agentTasks()
        val id = inputData.getString("task_id") ?: return Result.failure()
        val local = dao.get(id) ?: return Result.failure()
        if (local.status in setOf("completed", "failed", "cancelled", "interrupted")) return Result.success()
        val device = app.database.computers().get(local.deviceId) ?: run {
            dao.update(id, "failed", local.progress, local.total, "Computer was removed")
            return Result.failure()
        }
        val token = app.vault.deviceToken(device.id)
        if (token.isBlank()) {
            dao.update(id, "failed", local.progress, local.total, "Pairing token unavailable")
            return Result.failure()
        }
        val steps = dao.steps(id)
        if (steps.any { !app.settings.capabilityEnabled(device.id, it.action) }) {
            dao.update(id, "failed", local.progress, steps.size, "A computer capability was disabled")
            return Result.failure()
        }
        if (steps.any { it.sensitive } && !local.approved) {
            dao.update(id, "failed", 0, steps.size, "Human approval required")
            return Result.failure()
        }
        val payload = JSONArray()
        steps.forEach { step ->
            payload.put(JSONObject().put("action", step.action).put("args", JSONObject(step.argsJson))
                .put("approved", step.sensitive && local.approved))
        }
        val client = CompanionClient()
        try {
            // Submission uses a stable task ID; retries cannot re-execute an already accepted task.
            client.submit(device, token, id, payload)
            dao.update(id, "running", 0, steps.size, "")
            dao.addEvent(TaskEventEntity(taskId = id, message = "Submitted to ${device.name}"))
            repeat(180) {
                if (isStopped) {
                    runCatching { client.cancel(device, token, id) }
                    dao.update(id, "cancelled", dao.get(id)?.progress ?: 0, steps.size, "")
                    return Result.success()
                }
                val remote = client.task(device, token, id)
                val state = remote.getString("status")
                val progress = remote.getJSONObject("progress").getInt("completed")
                val results = remote.optJSONArray("results") ?: JSONArray()
                for (index in 0 until minOf(results.length(), steps.size)) {
                    val stepResult = results.getJSONObject(index)
                    val data = stepResult.optJSONObject("data")
                    val artifactId = data?.optString("artifact_id") ?: ""
                    if (artifactId.isNotBlank() && !data!!.has("local_file")) {
                        val bytes = client.artifact(device, token, artifactId)
                        val file = File(applicationContext.filesDir, "task_${id}_$index.image")
                        file.writeBytes(bytes)
                        data.put("local_file", file.absolutePath)
                    }
                    dao.updateStep(id, index, if (stepResult.optBoolean("ok")) "completed" else "failed", stepResult.toString())
                }
                dao.update(id, state, progress, steps.size, if (state == "failed") {
                    results.optJSONObject(results.length() - 1)?.optString("error") ?: "Agent task failed"
                } else "")
                if (state in setOf("completed", "failed", "cancelled", "interrupted")) {
                    dao.addEvent(TaskEventEntity(taskId = id, message = "Agent reported $state"))
                    if (state == "completed") {
                        val report = runCatching { TaskReporter(app).summarize(local.request, results) }.getOrNull()
                        if (!report.isNullOrBlank()) dao.addEvent(TaskEventEntity(taskId = id, message = report))
                    }
                    return Result.success()
                }
                delay(2_000)
            }
            // WorkManager may time-limit a single worker. Continue with the same remote task ID.
            return Result.retry()
        } catch (error: CompanionHttpException) {
            dao.update(id, "failed", dao.get(id)?.progress ?: 0, steps.size, error.message ?: "HTTP error")
            return Result.failure()
        } catch (error: IOException) {
            // After a disconnect, the computer may already be executing. The task ID remains stable.
            dao.addEvent(TaskEventEntity(taskId = id, message = "Connection lost: ${error.message ?: "network error"}"))
            if (runAttemptCount < 3) return Result.retry()
            dao.update(id, "interrupted", dao.get(id)?.progress ?: 0, steps.size,
                "Connection lost; check the computer before starting a new task")
            return Result.success()
        } catch (error: Exception) {
            dao.update(id, "failed", dao.get(id)?.progress ?: 0, steps.size, error.message ?: "Task error")
            return Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context, id: String) {
            val work = OneTimeWorkRequestBuilder<ComputerTaskWorker>()
                .setInputData(androidx.work.workDataOf("task_id" to id))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("computer-$id", ExistingWorkPolicy.KEEP, work)
        }
        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("computer-$id")
        }
    }
}
