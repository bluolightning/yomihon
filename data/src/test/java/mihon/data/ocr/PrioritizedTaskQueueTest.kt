package mihon.data.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrioritizedTaskQueueTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun highPriorityTaskRunsBeforeQueuedNormalTask() = runTest {
        val events = mutableListOf<String>()
        val holdFirstTask = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope)

        val first = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "normal-1-start"
                holdFirstTask.await()
                events += "normal-1-end"
            }
        }
        advanceUntilIdle()

        val second = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "normal-2"
            }
        }
        val highPriority = async {
            queue.submit(PrioritizedTaskQueue.Priority.HIGH) {
                events += "high"
            }
        }

        holdFirstTask.complete(Unit)

        first.await()
        highPriority.await()
        second.await()

        assertEquals(
            listOf("normal-1-start", "normal-1-end", "high", "normal-2"),
            events,
        )
    }
}
