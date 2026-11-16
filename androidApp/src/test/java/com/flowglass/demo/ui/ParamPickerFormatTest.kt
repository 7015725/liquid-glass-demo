package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure parsing used by the duration picker; keeps stored values in the existing human format. */
class ParamPickerFormatTest {
    @Test fun durationAcceptsIntegerAndDecimalUnits() {
        assertEquals("1" to "秒", parseDuration("1 秒"))
        assertEquals("1.5" to "分钟", parseDuration("1.5 分钟"))
        assertEquals("500" to "毫秒", parseDuration("500毫秒"))
    }

    @Test fun durationRejectsUnsupportedOrEmptyValues() {
        assertNull(parseDuration(""))
        assertNull(parseDuration("10"))
        assertNull(parseDuration("1 小时"))
    }
}
