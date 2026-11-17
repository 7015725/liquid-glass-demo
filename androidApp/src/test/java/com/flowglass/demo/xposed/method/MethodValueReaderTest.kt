package com.flowglass.demo.xposed.method

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodValueReaderTest {
    private data class Address(val city: String)
    private data class Order(val id: String, val address: Address?, val amount: Int)
    private class Exploding {
        override fun toString(): String = error("toString must not escape")
    }
    private class PrivateBox(private val value: String)

    @Test
    fun readsNestedFieldsAndStopsAtNullOrMissingValues() {
        val order = Order("A-1", Address("Shanghai"), 42)
        assertEquals("A-1", MethodValueReader.read(order, "id"))
        assertEquals("Shanghai", MethodValueReader.read(order, "address.city"))
        assertEquals("42", MethodValueReader.read(order, "amount"))
        assertTrue(MethodValueReader.read(order, "")?.contains("A-1") == true)
        assertNull(MethodValueReader.read(order, "address.missing"))
        assertNull(MethodValueReader.read(Order("A-2", null, 1), "address.city"))
    }

    @Test
    fun extractionFailureIsNullNotAnException() {
        assertNull(MethodValueReader.read(null, ""))
        assertNull(MethodValueReader.read(Exploding(), ""))
        assertEquals("x", MethodValueReader.read(PrivateBox("x"), "value"))
        assertNull(MethodValueReader.read(PrivateBox("x"), "missing"))
    }

    @Test
    fun truncatesLongStrings() {
        val long = "x".repeat(MethodValueReader.MAX_TEXT + 100)
        assertEquals(MethodValueReader.MAX_TEXT, MethodValueReader.read(long, "")?.length)
    }
}
