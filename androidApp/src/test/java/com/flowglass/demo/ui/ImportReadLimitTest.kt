package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException

/**
 * STO-11：导入文件必须先做有界读取，再允许解析；不能先 readText() 把整个文件放进内存。
 */
class ImportReadLimitTest {
    @Test
    fun aTextExactlyAtTheLimitIsAccepted() {
        val text = "a".repeat(64)
        assertEquals(text, readImportText(ByteArrayInputStream(text.toByteArray()), maxChars = 64))
    }

    @Test
    fun anOversizedStreamIsRejectedBeforeItIsFullyBuffered() {
        val text = "a".repeat(65)
        val error = runCatching {
            readImportText(ByteArrayInputStream(text.toByteArray()), maxChars = 64)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("文件过大", error?.message)
    }

    @Test
    fun multibyteCharactersCountAsCharactersNotBytes() {
        val text = "触发器".repeat(10)
        assertEquals(30, text.length)
        assertEquals(text, readImportText(ByteArrayInputStream(text.toByteArray()), maxChars = 30))
    }

    @Test(expected = CharacterCodingException::class)
    fun invalidUtf8IsRejectedInsteadOfSilentlyReplaced() {
        // 0xC3 单独出现不是合法的 UTF-8 序列。
        readImportText(ByteArrayInputStream(byteArrayOf('a'.code.toByte(), 0xC3.toByte())), maxChars = 10)
    }
}
