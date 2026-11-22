package com.flowglass.demo.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * STO-15：AtomicFile.finishWrite 在 fsync/rename 失败时只写日志、不抛异常。写完后必须读回
 * 比对，否则磁盘还是旧内容，App 却按新状态继续跑，重启后改动丢失。
 */
class CommandStoreCommitTest {
    @Test
    fun aMatchingReadBackIsAccepted() {
        val bytes = "{\"version\":3}".toByteArray()
        verifyCommitted({ bytes.copyOf() }, bytes)
    }

    @Test(expected = IOException::class)
    fun aSilentlyFailedCommitIsReported() {
        // 模拟 finishWrite 静默失败：磁盘上仍是旧文档。
        verifyCommitted({ "old".toByteArray() }, "new".toByteArray())
    }

    @Test(expected = IOException::class)
    fun aTruncatedWriteIsReported() {
        val expected = "0123456789".toByteArray()
        verifyCommitted({ "01234".toByteArray() }, expected)
    }

    @Test
    fun theFailureMessageDoesNotLeakFileContents() {
        val expected = "secret-token-value".toByteArray()
        val error = runCatching { verifyCommitted({ ByteArray(0) }, expected) }.exceptionOrNull()
        assertTrue(error is IOException)
        val message = error?.message.orEmpty()
        assertTrue("错误信息不应包含文件内容", !message.contains("secret-token-value"))
        assertTrue("错误信息应提示校验失败", message.contains("提交校验失败"))
    }
}
