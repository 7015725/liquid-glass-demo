package com.flowglass.engine

import java.io.File
import java.io.FileOutputStream

object LegacyFileMigration {
    fun backupBeforeWrite(original: ByteArray, backup: File, writeMigrated: () -> Unit) {
        if (backup.exists()) {
            require(backup.isFile && backup.readBytes().contentEquals(original)) {
                "旧数据备份已存在且内容不同，停止迁移以避免覆盖。"
            }
        } else {
            require(backup.createNewFile()) { "无法创建旧数据备份" }
            try {
                FileOutputStream(backup).use { output ->
                    output.write(original)
                    output.fd.sync()
                }
            } catch (failure: Exception) {
                backup.delete()
                throw failure
            }
        }
        writeMigrated()
    }
}
