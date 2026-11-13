package com.flowglass.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyFileMigrationTest {
    @Test
    fun writesBackupBeforeMigratedPayloadAndDoesNotOverwriteSameBackup() {
        val directory = Files.createTempDirectory("engine-migration").toFile()
        val source = File(directory, "commands.json").apply { writeText("legacy bytes") }
        val backup = File(directory, "commands.v1.bak.json")
        val original = source.readBytes()
        var writeCount = 0

        LegacyFileMigration.backupBeforeWrite(original, backup) {
            assertContentEquals("legacy bytes".toByteArray(), backup.readBytes())
            writeCount++
        }
        val originalBackup = backup.readBytes()
        LegacyFileMigration.backupBeforeWrite(original, backup) {
            writeCount++
            source.writeText("v2 bytes")
        }

        assertTrue(writeCount == 2)
        assertContentEquals(originalBackup, backup.readBytes())
        assertFalse(source.readText() == "legacy bytes")
        directory.deleteRecursively()
    }

    @Test
    fun refusesConflictingBackupWithoutCallingWriter() {
        val directory = Files.createTempDirectory("engine-migration-conflict").toFile()
        val source = File(directory, "commands.json").apply { writeText("current") }
        val backup = File(directory, "commands.v1.bak.json").apply { writeText("different") }
        val original = source.readBytes()
        var writeCalled = false

        assertFailsWith<IllegalArgumentException> {
            LegacyFileMigration.backupBeforeWrite(original, backup) { writeCalled = true }
        }
        assertFalse(writeCalled)
        assertTrue(source.readText() == "current")
        directory.deleteRecursively()
    }
}
