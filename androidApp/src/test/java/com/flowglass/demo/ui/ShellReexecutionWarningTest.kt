package com.flowglass.demo.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShellReexecutionWarningTest {
    @Test
    fun warnsWhenEvalOrShellCommandAndVariableExpansionCoexist() {
        assertNotNull(shellReexecutionWarning("eval %{payload}"))
        assertNotNull(shellReexecutionWarning("sh -c %{payload}"))
        assertNotNull(shellReexecutionWarning("bash -c \"echo %{payload}\""))
        assertNotNull(shellReexecutionWarning("EVAL %{payload}"))
    }

    @Test
    fun staysQuietWhenOnlyOnePartIsPresent() {
        assertNull(shellReexecutionWarning("echo %{payload}"))
        assertNull(shellReexecutionWarning("eval echo hello"))
        assertNull(shellReexecutionWarning("evaluate the result"))
        assertNull(shellReexecutionWarning("sh -c 'echo hello'"))
        assertNull(shellReexecutionWarning(""))
    }
}
