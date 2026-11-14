package com.bhavish.jarvis.tasks

/** Built-in capability groups; a companion never loads model-supplied code. */
object CapabilityPolicy {
    val groups = listOf("screen", "input", "window", "app", "project", "file", "browser", "system")

    fun group(action: String): String {
        require(action in ActionPlanParser.allowedActions) { "Unknown computer action" }
        return action.substringBefore('.')
    }
}
