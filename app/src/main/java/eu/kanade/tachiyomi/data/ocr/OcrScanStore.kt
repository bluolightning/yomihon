package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.preference.AndroidPreferenceStore

internal class OcrScanStore(
    context: Context,
) {
    private val mutex = Mutex()
    private val preferenceStore = AndroidPreferenceStore(context)
    private val queuePreference = preferenceStore.getString(QUEUE_KEY, "")
    private val activeChapterPreference = preferenceStore.getLong(ACTIVE_CHAPTER_KEY, NO_ACTIVE_CHAPTER)

    fun hasPendingWork(): Boolean {
        return activeChapterPreference.get() != NO_ACTIVE_CHAPTER || queuePreference.get().isNotBlank()
    }

    fun snapshot(): OcrScanStoreSnapshot {
        return OcrScanStoreSnapshot(
            activeChapterId = activeChapterPreference.get().takeIf { it != NO_ACTIVE_CHAPTER },
            queuedChapterIds = queuePreference.get().decodeQueue(),
        )
    }

    suspend fun enqueue(chapterIds: Collection<Long>): Boolean {
        return mutex.withLock {
            val queue = queuePreference.get().decodeQueue().toMutableList()
            val activeChapterId = activeChapterPreference.get()
            var changed = false

            chapterIds
                .filter { it != NO_ACTIVE_CHAPTER }
                .forEach { chapterId ->
                    if (chapterId != activeChapterId && chapterId !in queue) {
                        queue += chapterId
                        changed = true
                    }
                }

            if (changed) {
                queuePreference.set(queue.encodeQueue())
            }
            changed
        }
    }

    suspend fun restoreActiveChapterToQueue() {
        mutex.withLock {
            val activeChapterId = activeChapterPreference.get()
            if (activeChapterId == NO_ACTIVE_CHAPTER) {
                return@withLock
            }

            val queue = queuePreference.get().decodeQueue().toMutableList()
            if (activeChapterId !in queue) {
                queue.add(0, activeChapterId)
                queuePreference.set(queue.encodeQueue())
            }
            activeChapterPreference.set(NO_ACTIVE_CHAPTER)
        }
    }

    suspend fun startNextChapter(): Long? {
        return mutex.withLock {
            val queue = queuePreference.get().decodeQueue().toMutableList()
            val nextChapterId = queue.removeFirstOrNull() ?: return@withLock null
            queuePreference.set(queue.encodeQueue())
            activeChapterPreference.set(nextChapterId)
            nextChapterId
        }
    }

    suspend fun finishActiveChapter(chapterId: Long) {
        mutex.withLock {
            if (activeChapterPreference.get() == chapterId) {
                activeChapterPreference.set(NO_ACTIVE_CHAPTER)
            }
        }
    }

    private fun String.decodeQueue(): List<Long> {
        if (isBlank()) return emptyList()
        return split(',')
            .mapNotNull(String::toLongOrNull)
            .distinct()
    }

    private fun List<Long>.encodeQueue(): String {
        return joinToString(separator = ",")
    }

    private companion object {
        // Keep the persisted keys stable so existing queued scan work survives upgrades.
        const val QUEUE_KEY = "ocr_preprocess_queue"
        const val ACTIVE_CHAPTER_KEY = "ocr_preprocess_active_chapter"
        const val NO_ACTIVE_CHAPTER = -1L
    }
}

internal data class OcrScanStoreSnapshot(
    val activeChapterId: Long?,
    val queuedChapterIds: List<Long>,
)
