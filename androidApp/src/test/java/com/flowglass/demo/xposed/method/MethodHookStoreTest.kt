package com.flowglass.demo.xposed.method

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.flowglass.engine.methodhook.MethodExtraction
import com.flowglass.engine.methodhook.MethodExtractionSource
import com.flowglass.engine.methodhook.MethodHookSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MethodHookStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val file = File(context.cacheDir, "method-hooks-test.json")

    @After
    fun cleanup() {
        file.delete()
        File(file.path + ".bak").delete()
        File(file.path + ".new").delete()
        context.getSharedPreferences("method_hook_secrets", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun roundTripsHooksWithoutSecretsAndRejectsCorruptData() {
        val spec = MethodHookSpec(
            id = "h1",
            packageName = "com.example.target",
            className = "com.example.Target",
            methodName = "submit",
            paramCount = 1,
            extractions = listOf(
                MethodExtraction("订单号", MethodExtractionSource.ARGUMENT, 0),
                MethodExtraction("结果", MethodExtractionSource.RESULT),
            ),
        )
        val store = MethodHookStore(file)
        assertTrue(store.load().writable)
        store.save(listOf(spec))
        val loaded = MethodHookStore(file).load()
        assertTrue(loaded.error == null)
        assertEquals(listOf(spec), loaded.hooks)
        assertFalse(file.readText().contains("token"))

        file.writeText("{not json", Charsets.UTF_8)
        val broken = MethodHookStore(file).load()
        assertNotNull(broken.error)
        assertFalse(broken.writable)
        assertEquals("{not json", file.readText())
    }

    @Test
    fun secretsAreStablePerHookAndAbsentUntilCreated() {
        val secrets = MethodHookSecrets(context)
        assertNull(secrets.token("h1"))
        val first = secrets.ensureToken("h1")
        assertEquals(first, secrets.ensureToken("h1"))
        val second = secrets.ensureToken("h2")
        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertFalse(first == second)
        secrets.remove("h1")
        assertNull(secrets.token("h1"))
        assertNotNull(secrets.token("h2"))
    }
}
