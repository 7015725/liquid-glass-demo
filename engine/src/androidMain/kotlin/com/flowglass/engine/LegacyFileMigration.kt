package com.flowglass.engine

import java.io.File
import java.io.FileOutputStream

object LegacyFileMigration {
    /**
     * Keeps the pre-migration bytes without ever overwriting an existing backup. A device can
     * legitimately hold a backup from an earlier upgrade of a different source format (v0.5 text
     * next to pre-release v2 preview bytes), so a taken path is never treated as a conflict that
     * aborts the load: identical bytes reuse it, different bytes get a numbered sibling.
     */
    fun backupBeforeWrite(original: ByteArray, backup: File, writeMigrated: () -> Unit): File {
        val used = keepBackup(original, backup)
        writeMigrated()
        return used
    }

    private fun keepBackup(original: ByteArray, backup: File): File {
        var candidate = backup
        var index = 1
        while (true) {
            if (candidate.isFile && candidate.readBytes().contentEquals(original)) return candidate
            if (!candidate.exists()) {
                require(candidate.createNewFile()) { "无法创建旧数据备份" }
                try {
                    FileOutputStream(candidate).use { output ->
                        output.write(original)
                        output.fd.sync()
                    }
                    return candidate
                } catch (failure: Exception) {
                    candidate.delete()
                    throw failure
                }
            }
            index++
            require(index <= MAX_BACKUPS) { "旧数据备份数量过多，停止迁移以避免覆盖。" }
            candidate = File(backup.parentFile, "${backup.nameWithoutExtension}-$index.${backup.extension}")
        }
    }

    private const val MAX_BACKUPS = 50
}
