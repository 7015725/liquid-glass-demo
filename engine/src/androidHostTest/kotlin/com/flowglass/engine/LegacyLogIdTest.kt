package com.flowglass.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LegacyLogIdTest {
    @Test fun nonPositiveLogsGetSequentialIdsAboveAllDeclarationsAndReferences() {
        val document = LegacyCommandMigration.migrate(source(logs = listOf(
            log(-1), log(0), log(Long.MIN_VALUE), log(-1), log(50, 90),
        )))
        assertEquals(listOf(91L, 92L, 93L, 94L, 50L), document.ui.logs.map { it.id })
        assertEquals(listOf(90L), document.ui.logs.last().steps.map { it.itemId })
        assertEquals("原文 | 保留\n第二行", document.ui.logs.first().detail)
    }

    @Test fun commandIdsRemainPositiveUniqueAndReportTheirCategory() {
        for (id in listOf(-1L, 0L, Long.MAX_VALUE)) {
            rejects(source(commands = listOf(command(id))), "指令 id")
        }
        rejects(source(commands = listOf(command(1), command(1, 3))), "指令 id")
    }

    @Test fun itemIdsRemainPositiveUniqueAndCannotCollideWithCommands() {
        for (id in listOf(-1L, 0L, Long.MAX_VALUE)) {
            rejects(source(commands = listOf(command(1, id))), "条目 id")
        }
        rejects(source(commands = listOf(command(1, 2), command(3, 2))), "条目 id")
        rejects(source(commands = listOf(command(1, 1))), "条目 id")
    }

    @Test fun positiveLogIdsRemainUniqueAndCannotCollideWithCommandsOrItems() {
        rejects(source(logs = listOf(log(3), log(3))), "执行记录 id")
        for (id in listOf(1L, 2L, Long.MAX_VALUE)) {
            rejects(source(logs = listOf(log(id))), "执行记录 id")
        }
    }

    @Test fun allocationExhaustionIsRejectedBeforeOverflow() {
        rejects(source(logs = listOf(log(Long.MAX_VALUE - 1), log(0))), "执行记录 id")
        rejects(source(logs = listOf(log(0, Long.MAX_VALUE - 1))), "执行记录 id")
        val document = LegacyCommandMigration.migrate(source(logs = listOf(log(Long.MAX_VALUE - 2), log(0))))
        assertEquals(Long.MAX_VALUE - 1, document.ui.logs.last().id)
    }

    @Test fun invalidHistoricalStepReferencesAreNotUsedForAllocation() {
        for (id in listOf(-1L, 0L, Long.MAX_VALUE)) {
            rejects(source(logs = listOf(log(-1, id))), "执行记录步骤")
        }
    }

    private fun rejects(source: String, category: String) {
        val failure = assertFailsWith<IllegalArgumentException> { LegacyCommandMigration.migrate(source) }
        assertTrue(failure.message.orEmpty().contains(category), failure.message)
    }

    private fun source(commands: List<String> = listOf(command(1)), logs: List<String> = emptyList()) =
        """{"version":1,"oneTaps":[${commands.joinToString()}],"logs":[${logs.joinToString()}]}"""

    private fun command(id: Long, itemId: Long = 2) =
        """{"id":$id,"auto":false,"ic":"Bolt","title":"保留指令","desc":"原说明","actions":[{"id":$itemId,"name":"复制到剪贴板","parameter":"原文","ic":"Copy"}]}"""

    private fun log(id: Long, reference: Long? = null): String {
        val steps = reference?.let {
            """{"itemId":$it,"name":"历史任务","status":"SUCCESS","detail":"保持","durationMs":12}"""
        }.orEmpty()
        return """{"id":$id,"title":"日志","reason":"手动","time":"12:00","ok":true,"ic":"Bell","detail":"原文 | 保留\n第二行","steps":[$steps]}"""
    }
}
