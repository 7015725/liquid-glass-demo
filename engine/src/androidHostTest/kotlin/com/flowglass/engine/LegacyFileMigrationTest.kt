package com.flowglass.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
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
    fun keepsAConflictingBackupAndWritesANumberedSibling() {
        // A device can hold a backup from an earlier upgrade of a different source format.
        // Aborting the load there locked the app in read-only mode, so the conflict now gets
        // its own file instead of blocking the migration.
        val directory = Files.createTempDirectory("engine-migration-conflict").toFile()
        val source = File(directory, "commands.json").apply { writeText("current") }
        val backup = File(directory, "commands.v1.bak.json").apply { writeText("different") }
        val original = source.readBytes()
        var writeCalled = false

        val used = LegacyFileMigration.backupBeforeWrite(original, backup) { writeCalled = true }

        assertTrue(writeCalled)
        assertEquals("different", backup.readText())
        assertEquals("commands.v1.bak-2.json", used.name)
        assertContentEquals(original, used.readBytes())
        assertEquals("current", source.readText())
        directory.deleteRecursively()
    }
}
