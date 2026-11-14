package com.bhavish.jarvis.tasks

import org.json.JSONObject

data class PlannedStep(val action: String, val args: JSONObject, val sensitive: Boolean)
data class ActionPlan(val summary: String, val steps: List<PlannedStep>)

object ActionPlanParser {
    val allowedActions = setOf(
        "screen.capture", "screen.update",
        "input.move", "input.click", "input.scroll", "input.type", "input.hotkey",
        "window.list", "window.activate", "window.close",
        "app.launch", "app.close", "project.open",
        "file.search", "file.read", "file.create", "file.edit", "file.move",
        "file.organize", "file.summarize",
        "browser.open", "browser.navigate", "browser.click", "browser.type",
        "browser.extract", "browser.back", "browser.forward",
        "system.status", "system.apps"
    )
    private val sensitiveActions = setOf(
        "input.move", "input.click", "input.scroll", "input.type", "input.hotkey",
        "window.activate", "window.close", "app.close", "project.open",
        "file.create", "file.edit", "file.move", "file.organize",
        "browser.click", "browser.type"
    )

    fun parse(raw: String): ActionPlan {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val body = try { JSONObject(clean) } catch (_: Exception) {
            throw IllegalArgumentException("The model did not return a JSON plan")
        }
        val summary = body.optString("summary").trim().take(160)
        if (summary.isBlank()) throw IllegalArgumentException("Plan summary is missing")
        val array = body.optJSONArray("steps") ?: throw IllegalArgumentException("Plan steps are missing")
        if (array.length() !in 1..8) throw IllegalArgumentException("Plan needs 1 to 8 steps")
        val steps = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: throw IllegalArgumentException("Step must be an object")
            if (item.has("approved")) throw IllegalArgumentException("A model cannot approve its own actions")
            val action = item.optString("action")
            if (action !in allowedActions) throw IllegalArgumentException("Unknown action: $action")
            val args = item.optJSONObject("args") ?: throw IllegalArgumentException("Action arguments must be an object")
            if (args.toString().length > 16_384) throw IllegalArgumentException("Action arguments are too large")
            PlannedStep(action, args, action in sensitiveActions)
        }
        return ActionPlan(summary, steps)
    }
}
