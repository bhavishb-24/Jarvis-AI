package com.bhavish.jarvis

import com.bhavish.jarvis.tasks.ActionPlanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlanTest {
    @Test fun parsesAllowlistedStepsAndMarksSensitiveOnPhone() {
        val plan = ActionPlanParser.parse("""{"summary":"Open app","steps":[{"action":"app.launch","args":{"app":"Chrome"}},{"action":"input.click","args":{"x":1,"y":2}}]}""")
        assertEquals(2, plan.steps.size)
        assertFalse(plan.steps[0].sensitive)
        assertTrue(plan.steps[1].sensitive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsArbitraryShell() {
        ActionPlanParser.parse("""{"summary":"bad","steps":[{"action":"shell.run","args":{"command":"whoami"}}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyPlan() {
        ActionPlanParser.parse("""{"summary":"empty","steps":[]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsModelApprovalFlagAsAuthority() {
        ActionPlanParser.parse("""{"summary":"bad","steps":[{"action":"input.click","args":{},"approved":true}]}""")
    }
}
