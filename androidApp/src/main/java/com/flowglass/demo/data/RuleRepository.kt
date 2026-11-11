package com.flowglass.demo.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.flowglass.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serialized transactional authoring store. It does not execute saved rules. */
class RuleRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val mutex = Mutex()
    private var database: RuleDatabase? = null
    private val mutableRules = MutableStateFlow<List<Rule>>(emptyList())
    private val mutableGlobals = MutableStateFlow<List<Variable>>(emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val rules: StateFlow<List<Rule>> = mutableRules.asStateFlow()
    val globals: StateFlow<List<Variable>> = mutableGlobals.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()

    private fun open(): RuleDatabase = database ?: Room.databaseBuilder(
        applicationContext, RuleDatabase::class.java, "rule-authoring.db",
    ).build().also { database = it }

    private suspend fun publish(db: RuleDatabase) {
        val snapshot = db.withTransaction {
            val dao = db.rulesDao()
            dao.rules().map { RuleCodec.decodeRule(it.ruleJson) } to dao.globals().map {
                RuleCodec.json.decodeFromString(Variable.serializer(), it.variableJson)
            }
        }
        mutableRules.value = snapshot.first
        mutableGlobals.value = snapshot.second
        mutableError.value = null
    }

    private suspend fun operation(block: suspend (RuleDatabase) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = open()
                block(db)
                publish(db)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                mutableError.value = failure.message ?: "数据库操作失败"
                throw failure
            }
        }
    }

    private fun validate(rule: Rule) {
        val issues = RuleValidator.validate(rule)
        require(issues.isEmpty()) { issues.joinToString("\n") { "${it.path}：${it.message}" } }
    }

    private suspend fun writeRule(dao: RuleDao, rule: Rule) {
        dao.saveRule(RuleEntity(rule.id, RuleCodec.encodeRule(rule)))
        dao.deleteLocals(rule.id)
        dao.insertLocals(rule.locals.mapIndexed { ordinal, variable ->
            LocalVariableEntity(rule.id, variable.name, ordinal,
                RuleCodec.json.encodeToString(Variable.serializer(), variable))
        })
    }

    suspend fun save(rule: Rule) = operation { db ->
        validate(rule)
        db.withTransaction { writeRule(db.rulesDao(), rule) }
    }

    suspend fun delete(id: String) = operation { db ->
        db.withTransaction { db.rulesDao().deleteRule(id) }
    }

    suspend fun saveGlobal(variable: Variable) = operation { db ->
        val issues = RuleValidator.validateVariable(variable)
        require(issues.isEmpty()) { issues.joinToString("\n") { "${it.path}：${it.message}" } }
        db.withTransaction {
            db.rulesDao().saveGlobal(GlobalVariableEntity(variable.name,
                RuleCodec.json.encodeToString(Variable.serializer(), variable)))
        }
    }
    suspend fun deleteGlobal(name: String) = operation { db ->
        db.withTransaction { db.rulesDao().deleteGlobal(name) }
    }

    suspend fun importBundle(bundle: RuleBundle) = operation { db ->
        require(bundle.formatVersion == 1) { "未知导入格式版本：${bundle.formatVersion}" }
        // Validate the complete batch (including limits/IDs/types) before the first write.
        RuleCodec.exportBundle(bundle.rules, bundle.globals)
        db.withTransaction {
            val dao = db.rulesDao()
            val existingIds = dao.rules().map { it.id }.toSet()
            val existingNames = dao.globals().map { it.name }.toSet()
            require(bundle.rules.none { it.id in existingIds }) { "规则 ID 已存在，请重新导入以分配新 ID" }
            val conflicts = bundle.globals.map { it.name }.filter { it in existingNames }
            require(conflicts.isEmpty()) { "全局变量重名：${conflicts.joinToString()}；未导入任何内容，请先改名或移除冲突" }
            bundle.rules.forEach { writeRule(dao, it.copy(enabled = false)) }
            bundle.globals.forEach { variable ->
                dao.saveGlobal(GlobalVariableEntity(variable.name,
                    RuleCodec.json.encodeToString(Variable.serializer(), variable)))
            }
        }
    }

    suspend fun refresh() = operation { }

    /** A later refresh can reopen the same disk database. */
    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            database?.close()
            database = null
        }
    }
}
