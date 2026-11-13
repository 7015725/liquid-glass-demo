package com.flowglass.demo.system.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class TaskParametersTest {
    @Test fun parsesDelayUnitsAndCapsTenMinutes() {
        assertEquals(500L, TaskParameters.delayMillis("500 毫秒"))
        assertEquals(1_500L, TaskParameters.delayMillis("1.5秒"))
        assertEquals(120_000L, TaskParameters.delayMillis("2 分钟"))
        assertEquals(600_000L, TaskParameters.delayMillis("11 分钟"))
    }

    @Test fun rejectsEmptyOrMalformedDelay() {
        for (value in listOf("", "-1 秒", "1 小时")) {
            assertInvalid { TaskParameters.delayMillis(value) }
        }
    }

    @Test fun parsesPercentAndTriStateValues() {
        assertEquals(37.5, TaskParameters.percent("37.5％"), 0.0)
        assertEquals(SwitchMode.ON, TaskParameters.mode(" on "))
        assertEquals(SwitchMode.OFF, TaskParameters.mode("关闭"))
        assertEquals(SwitchMode.TOGGLE, TaskParameters.mode("切换"))
    }

    @Test fun parsesHttpRequestsAndPreservesAnEmptyPostBody() {
        val get = TaskParameters.http("GET https://example.test/path?q=1")
        assertEquals("GET", get.method)
        assertEquals("https://example.test/path?q=1", get.url.toExternalForm())
        assertNull(get.body)
        val post = TaskParameters.http("POST https://example.test/submit ")
        assertEquals("POST", post.method)
        assertEquals("", post.body)
        assertInvalid { TaskParameters.http("") }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid parameter to be rejected")
        } catch (expected: IllegalArgumentException) {
            // The production parser reports malformed input with a stable user-facing exception.
        }
    }
}