package com.flowglass.demo.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "rules")
internal data class RuleEntity(@PrimaryKey val id: String, val ruleJson: String)

@Entity(
    tableName = "local_variables",
    primaryKeys = ["ruleId", "name"],
    foreignKeys = [ForeignKey(
        entity = RuleEntity::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class LocalVariableEntity(
    val ruleId: String,
    val name: String,
    val ordinal: Int,
    val variableJson: String,
)

@Entity(tableName = "global_variables")
internal data class GlobalVariableEntity(@PrimaryKey val name: String, val variableJson: String)

@Dao
internal interface RuleDao {
    @androidx.room.Upsert
    suspend fun saveRule(entity: RuleEntity)

    @androidx.room.Insert
    suspend fun insertLocals(entities: List<LocalVariableEntity>)

    @Query("DELETE FROM local_variables WHERE ruleId = :ruleId")
    suspend fun deleteLocals(ruleId: String)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRule(id: String)

    @Query("SELECT * FROM rules ORDER BY id")
    suspend fun rules(): List<RuleEntity>

    @androidx.room.Upsert
    suspend fun saveGlobal(entity: GlobalVariableEntity)

    @Query("DELETE FROM global_variables WHERE name = :name")
    suspend fun deleteGlobal(name: String)

    @Query("SELECT * FROM global_variables ORDER BY name")
    suspend fun globals(): List<GlobalVariableEntity>
}

@Database(
    entities = [RuleEntity::class, LocalVariableEntity::class, GlobalVariableEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class RuleDatabase : RoomDatabase() {
    abstract fun rulesDao(): RuleDao
}
