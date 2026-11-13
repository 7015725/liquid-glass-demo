package com.flowglass.demo.system.exec

import android.media.AudioManager
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ExecutionCancellationTest.CancellingAudioManager::class])
class ExecutionCancellationTest {
    @Implements(AudioManager::class)
    class CancellingAudioManager : ShadowAudioManager() {
        companion object {
            var onWrite: (() -> Unit)? = null
        }

        @Implementation
        override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
            super.setStreamVolume(streamType, index, flags)
            onWrite?.invoke()
        }
    }

    @Test fun nativeSynchronousActionCancellationStopsNextActionAndReleasesGuard() =
        cancellationStopsRun(injected = false, actionCount = 2)

    @Test fun nativeFinalActionCancellationDoesNotReturnRunResult() =
        cancellationStopsRun(injected = false, actionCount = 1)

    @Test fun injectedSynchronousActionCancellationStopsNextActionAndReleasesGuard() =
        cancellationStopsRun(injected = true, actionCount = 2)

    private fun cancellationStopsRun(injected: Boolean, actionCount: Int) = runTest {
        val context = RuntimeEnvironment.getApplication()
        val command = Cmd(
            id = 702,
            auto = false,
            ic = Ic.Bolt,
            title = "同步取消",
            desc = "",
            actions = (1..actionCount).map { index ->
                CommandItem(index.toLong(), "重命名的音量动作 $index", "$index%", Ic.Bolt, "action.set_volume")
            },
        )
        var writes = 0
        var cancelJob: Job? = null
        val onWrite = {
            writes++
            cancelJob?.cancel()
            cancelJob = null
        }
        CancellingAudioManager.onWrite = onWrite
        val engine = if (injected) ExecutionEngine(context) { _, _ ->
            onWrite()
            TaskOutcome(StepStatus.SUCCESS, "同步完成")
        } else ExecutionEngine(context)
        var returned: RunResult? = null
        // No suspension in the action: a cancelled Deferred alone could hide a returned result.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancelJob = currentCoroutineContext()[Job]
            returned = engine.run(command, "取消")
        }
        try {
            job.join()
            assertTrue("the first synchronous action must cancel its Job", job.isCancelled)
            assertEquals("a cancelled run must not perform the next action", 1, writes)
            assertNull("cancellation must escape run(), not return a RunResult", returned)

            val retry = engine.run(command, "取消后重试")
            assertFalse("cancellation must release the per-command guard", retry.ignored)
            assertEquals(actionCount, retry.steps.size)
            assertTrue(retry.steps.all { it.status == StepStatus.SUCCESS })
            assertEquals(1 + actionCount, writes)
        } finally {
            job.cancelAndJoin()
            CancellingAudioManager.onWrite = null
        }
    }
}
