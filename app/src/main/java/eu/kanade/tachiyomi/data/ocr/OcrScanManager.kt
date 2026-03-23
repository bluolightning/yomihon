package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class OcrScanManager internal constructor(
    private val context: Context,
    private val store: OcrScanStore,
    private val scanner: OcrChapterScanner,
    private val notifier: OcrScanNotifier,
) {
    private val mutableQueueState = MutableStateFlow(store.snapshot().toQueueState())
    private val cacheEventsFlow = MutableSharedFlow<OcrChapterScanCacheEvent>(extraBufferCapacity = 32)

    internal val queueState = mutableQueueState.asStateFlow()
    internal val cacheEvents = cacheEventsFlow.asSharedFlow()

    fun startIfPending() {
        if (store.hasPendingWork()) {
            OcrScanJob.start(context)
        }
    }

    suspend fun enqueue(chapterIds: Collection<Long>) {
        if (store.enqueue(chapterIds)) {
            syncQueueState()
            OcrScanJob.start(context)
        }
    }

    suspend fun runPendingQueue(): Boolean {
        store.restoreActiveChapterToQueue()
        syncQueueState()

        var processedAny = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val chapterId = store.startNextChapter() ?: break
            processedAny = true
            syncQueueState()

            try {
                scanner.scanChapter(
                    chapterId = chapterId,
                    onProgress = { progress ->
                        syncQueueState(progress)
                        notifier.onProgress(progress, remainingChapters = queueState.value.queuedChapterIds.size)
                    },
                    onComplete = { progress ->
                        syncQueueState(progress)
                        notifier.onComplete(progress)
                    },
                    onError = { error ->
                        notifier.onError(
                            mangaId = error.mangaId,
                            mangaTitle = error.mangaTitle,
                            chapterName = error.chapterName,
                            error = error.error,
                        )
                    },
                    onCacheStateChanged = { changedChapterId, hasResults ->
                        cacheEventsFlow.tryEmit(
                            OcrChapterScanCacheEvent(
                                chapterId = changedChapterId,
                                hasResults = hasResults,
                            ),
                        )
                    },
                )
            } finally {
                store.finishActiveChapter(chapterId)
                syncQueueState()
            }
        }

        if (!processedAny) {
            notifier.dismissProgress()
        }

        return processedAny
    }

    private fun syncQueueState(activeProgress: OcrChapterScanProgress? = null) {
        val snapshot = store.snapshot()
        mutableQueueState.value = snapshot.toQueueState(
            activeProgress = activeProgress?.takeIf { it.chapterId == snapshot.activeChapterId },
        )
    }
}

internal data class OcrScanQueueState(
    val activeChapterId: Long?,
    val queuedChapterIds: List<Long>,
    val activeProgress: OcrChapterScanProgress?,
) {
    val isActive: Boolean
        get() = activeChapterId != null || queuedChapterIds.isNotEmpty()
}

internal data class OcrChapterScanCacheEvent(
    val chapterId: Long,
    val hasResults: Boolean,
)

private fun OcrScanStoreSnapshot.toQueueState(
    activeProgress: OcrChapterScanProgress? = null,
): OcrScanQueueState {
    return OcrScanQueueState(
        activeChapterId = activeChapterId,
        queuedChapterIds = queuedChapterIds,
        activeProgress = activeProgress,
    )
}
