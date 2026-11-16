package com.flowglass.demo.system.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAppsTest {
    @Test fun parsesUsersWithoutHardcodingVendorIds() {
        val output = """
            Users:
                UserInfo{0:Owner:c13} running
                UserInfo{10:Work profile:30} running
                UserInfo{999:XSpace:404} running
        """.trimIndent()
        assertEquals(listOf(0 to "Owner", 10 to "Work profile", 999 to "XSpace"),
            RootApps.parseUsers(output).map { it.id to it.name })
    }

    @Test fun parsesOnlyLauncherActivityComponentsFromScanOutput() {
        val output = """
            Users:
                UserInfo{0:Owner:c13} running
                UserInfo{10:Work profile:30} running
            __FLOWGLASS_USER__:0
            com.example.one/.Main
            com.example.two/com.example.two.Home
            priority=0 component=com.example.three/.Entry
            bad line
            __FLOWGLASS_USER__:10
            com.example.one/.Main
            com.example.clone/.Main
            com.example.one/.Main
        """.trimIndent()
        assertEquals(
            listOf(
                0 to "com.example.one",
                0 to "com.example.two",
                0 to "com.example.three",
                10 to "com.example.one",
                10 to "com.example.clone",
            ),
            RootApps.parseScanOutput(output).map { it.userId to it.packageName },
        )
    }

    @Test fun scanDoesNotUseTheAllPackageListingOrOneRootCallPerProfile() {
        val command = RootApps.scanCommand()
        assertTrue(command.contains("query-activities"))
        assertTrue(command.contains("__FLOWGLASS_USER__"))
        assertFalse(command.contains("pm list packages"))
    }

    @Test fun scanCommandRunsOneRootScriptAndParsesEveryProfile() {
        val dir = java.nio.file.Files.createTempDirectory("flowglass-root-scan-")
        try {
            val pm = dir.resolve("pm")
            val cmd = dir.resolve("cmd")
            java.nio.file.Files.writeString(pm, """
                #!/bin/sh
                printf '%s\n' 'Users:' '    UserInfo{0:Owner:c13} running' '    UserInfo{10:Work profile:30} running'
            """.trimIndent())
            java.nio.file.Files.writeString(cmd, """
                #!/bin/sh
                case "${'$'}*" in
                    *"--user 0"*) printf '%s\n' 'com.example.one/.Main' 'com.example.two/.Main' ;;
                    *"--user 10"*) printf '%s\n' 'com.example.clone/.Main' ;;
                esac
            """.trimIndent())
            listOf(pm, cmd).forEach { it.toFile().setExecutable(true) }
            val process = ProcessBuilder("/bin/sh", "-c", RootApps.scanCommand()).apply {
                environment()["PATH"] = "$dir:${System.getenv("PATH")}"
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            assertEquals(
                listOf(
                    0 to "com.example.one",
                    0 to "com.example.two",
                    10 to "com.example.clone",
                ),
                RootApps.parseScanOutput(output).map { it.userId to it.packageName },
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun extractsComponentFromResolveActivityOutput() {
        assertEquals("com.example/.Main", RootApps.resolveComponent(
            "priority=0 preferredOrder=0\ncom.example/.Main",
        ))
        assertEquals("com.example/.Main", RootApps.resolveComponent(
            "priority=0 component=com.example/.Main",
        ))
        assertNull(RootApps.resolveComponent("no component here"))
    }
}
