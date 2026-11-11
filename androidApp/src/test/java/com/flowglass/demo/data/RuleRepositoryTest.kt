package com.flowglass.demo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repositories = mutableListOf<RuleRepository>()

    @Before
    fun cleanDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() = runBlocking {
        repositories.forEach { it.close() }
        context.deleteDatabase(DATABASE_NAME)
        Unit
    }

    @Test
    fun refreshCreatesAnEmptyRealDatabaseWithoutSamples() = runBlocking {
        val repository = openRepository()
        repository.refresh()
        assertEquals(emptyList<Any>(), repository.rules.value)
        assertEquals(emptyList<Any>(), repository.globals.value)
        assertNull(repository.error.value)
        repository.close()

        val file = context.getDatabasePath(DATABASE_NAME)
        assertTrue("refresh must create a real SQLite database", file.isFile)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            for (table in listOf("rules", "local_variables", "global_variables")) {
                database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("No synthetic rows in $table", 0, cursor.getInt(0))
                }
            }
        }
    }

    @Test
    fun savedRuleAndLocalsSurviveClosingAndReopening() = runBlocking {
        val repository = openRepository()
        val rule = com.flowglass.engine.Rule(name = "保存测试", type = "manual",
            actions = listOf(com.flowglass.engine.ComponentRegistry.newComponent("action.delay")),
            locals = listOf(com.flowglass.engine.Variable("count", "int", kotlinx.serialization.json.JsonPrimitive(Long.MAX_VALUE))))
        repository.save(rule)
        assertEquals(listOf(rule), repository.rules.value)
        repository.close()
        val reopened = openRepository()
        reopened.refresh()
        assertEquals(listOf(rule), reopened.rules.value)
        SQLiteDatabase.openDatabase(context.getDatabasePath(DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT ruleId, name FROM local_variables", null).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(rule.id, cursor.getString(0))
                assertEquals("count", cursor.getString(1))
            }
        }
    }

    @Test
    fun deletingOneRuleCascadesOnlyItsOwnLocals() = runBlocking {
        val repository = openRepository()
        val first = com.flowglass.engine.Rule(name = "第一条", type = "manual",
            actions = listOf(com.flowglass.engine.ComponentRegistry.newComponent("action.delay")),
            locals = listOf(com.flowglass.engine.Variable("count", "int", kotlinx.serialization.json.JsonPrimitive(1))))
        val second = first.copy(id = java.util.UUID.randomUUID().toString(), name = "第二条",
            locals = listOf(first.locals.single().copy(initial = kotlinx.serialization.json.JsonPrimitive(2))))
        repository.save(first)
        repository.save(second)
        repository.delete(first.id)
        repository.close()
        val reopened = openRepository()
        reopened.refresh()
        assertEquals(listOf(second), reopened.rules.value)
        SQLiteDatabase.openDatabase(context.getDatabasePath(DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT ruleId, name FROM local_variables", null).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(second.id, cursor.getString(0))
            }
        }
    }

    @Test
    fun globalValuesRoundTripAndDeleteWithoutChangingRules() = runBlocking {
        val repository = openRepository()
        val variable = com.flowglass.engine.Variable("mode", "dict",
            com.flowglass.engine.RuleCodec.json.parseToJsonElement("{\"big\":9223372036854775807,\"items\":[true,null]}"))
        repository.saveGlobal(variable)
        assertEquals(listOf(variable), repository.globals.value)
        repository.close()
        val reopened = openRepository()
        reopened.refresh()
        assertEquals(listOf(variable), reopened.globals.value)
        reopened.deleteGlobal("mode")
        reopened.close()
        repository.refresh()
        assertTrue(repository.globals.value.isEmpty())
    }

    @Test
    fun importRejectsGlobalCollisionAsAWholeThenPersistsSafeBatch() = runBlocking {
        val repository = openRepository()
        val existing = com.flowglass.engine.Variable("mode", initial = kotlinx.serialization.json.JsonPrimitive("original"))
        repository.saveGlobal(existing)
        val rule = com.flowglass.engine.Rule(name = "导入", type = "manual", enabled = true,
            actions = listOf(com.flowglass.engine.ComponentRegistry.newComponent("action.delay")))
        val collision = com.flowglass.engine.RuleBundle(rules = listOf(rule), globals = listOf(existing.copy(initial = kotlinx.serialization.json.JsonPrimitive("overwrite"))))
        val failure = runCatching { repository.importBundle(collision) }.exceptionOrNull()
        assertTrue("Global conflict must reject the whole batch", failure is IllegalArgumentException)
        assertTrue(repository.rules.value.isEmpty())
        assertEquals(listOf(existing), repository.globals.value)
        assertTrue(repository.error.value != null)
        repository.importBundle(com.flowglass.engine.RuleBundle(rules = listOf(rule)))
        repository.close()
        val reopened = openRepository()
        reopened.refresh()
        assertEquals(listOf(rule.copy(enabled = false)), reopened.rules.value)
        assertEquals(listOf(existing), reopened.globals.value)
        val duplicateFailure = runCatching { reopened.importBundle(com.flowglass.engine.RuleBundle(rules = listOf(rule))) }.exceptionOrNull()
        assertTrue(duplicateFailure is IllegalArgumentException)
        assertEquals(1, reopened.rules.value.size)
    }

    private fun openRepository() = RuleRepository(context).also(repositories::add)

    companion object {
        private const val DATABASE_NAME = "rule-authoring.db"
    }
}
