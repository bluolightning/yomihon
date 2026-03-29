package mihon.domain.ocr

import kotlinx.coroutines.test.runTest
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.repository.OcrRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OcrRepositoryContractTest {

    @Test
    fun scanPageOverwritesExistingPageResults() {
        runTest {
            val repository = InMemoryOcrRepository()

            val first = repository.scanPage(chapterId = 5L, pageIndex = 2, image = image(120, 240))
            val second = repository.scanPage(chapterId = 5L, pageIndex = 2, image = image(200, 300))

            assertEquals("page-5-2-2", second.text)
            assertEquals(second, repository.getCachedPage(chapterId = 5L, pageIndex = 2))
            assertEquals(1, repository.cachedEntriesCount())
            assertEquals(120, first.imageWidth)
            assertEquals(200, second.imageWidth)
        }
    }

    @Test
    fun clearCachedChapterRemovesOnlyMatchingChapter() {
        runTest {
            val repository = InMemoryOcrRepository()

            repository.scanPage(chapterId = 5L, pageIndex = 0, image = image(100, 100))
            repository.scanPage(chapterId = 6L, pageIndex = 0, image = image(100, 100))

            repository.clearCachedChapter(5L)

            assertNull(repository.getCachedPage(chapterId = 5L, pageIndex = 0))
            assertEquals("page-6-0-1", repository.getCachedPage(chapterId = 6L, pageIndex = 0)?.text)
            assertEquals(1, repository.cachedEntriesCount())
        }
    }

    @Test
    fun clearCacheRemovesAllCachedResultsAndResetsSize() {
        runTest {
            val repository = InMemoryOcrRepository()

            repository.scanPage(chapterId = 5L, pageIndex = 0, image = image(100, 100))
            repository.scanPage(chapterId = 6L, pageIndex = 1, image = image(100, 100))

            assertEquals(2, repository.cachedEntriesCount())
            assertEquals(256L, repository.getCacheSizeBytes())

            repository.clearCache()

            assertNull(repository.getCachedPage(chapterId = 5L, pageIndex = 0))
            assertNull(repository.getCachedPage(chapterId = 6L, pageIndex = 1))
            assertEquals(0, repository.cachedEntriesCount())
            assertEquals(0L, repository.getCacheSizeBytes())
        }
    }

    @Test
    fun getCachedChapterIdsReturnsOnlyChaptersWithCachedPages() {
        runTest {
            val repository = InMemoryOcrRepository()

            repository.scanPage(chapterId = 5L, pageIndex = 0, image = image(100, 100))
            repository.scanPage(chapterId = 6L, pageIndex = 1, image = image(100, 100))

            assertEquals(
                setOf(5L, 6L),
                repository.getCachedChapterIds(listOf(5L, 6L, 7L)),
            )
        }
    }

    private class InMemoryOcrRepository : OcrRepository {
        private val pages = mutableMapOf<Key, OcrPageResult>()

        override suspend fun recognizeText(image: OcrImage): String = "recognized-${image.width}x${image.height}"

        override suspend fun scanPage(
            chapterId: Long,
            pageIndex: Int,
            image: OcrImage,
        ): OcrPageResult {
            val result = OcrPageResult(
                chapterId = chapterId,
                pageIndex = pageIndex,
                ocrModel = OcrModel.GLENS,
                imageWidth = image.width,
                imageHeight = image.height,
                regions = listOf(
                    OcrRegion(
                        order = 0,
                        text = "page-$chapterId-$pageIndex-${image.width / 100}",
                        boundingBox = OcrBoundingBox(
                            left = 0.0f,
                            top = 0.0f,
                            right = 1.0f,
                            bottom = 1.0f,
                        ),
                    ),
                ),
            )
            pages[Key(chapterId, pageIndex, result.ocrModel)] = result
            return result
        }

        override suspend fun getCachedPage(
            chapterId: Long,
            pageIndex: Int,
        ): OcrPageResult? {
            return pages[Key(chapterId, pageIndex, OcrModel.GLENS)]
        }

        override suspend fun getCachedChapterIds(chapterIds: Collection<Long>): Set<Long> {
            return pages.keys.map(Key::chapterId)
                .distinct()
                .filter { it in chapterIds }
                .toSet()
        }

        override suspend fun clearCachedChapter(chapterId: Long) {
            pages.keys.removeAll { it.chapterId == chapterId }
        }

        override suspend fun clearCache() {
            pages.clear()
        }

        override suspend fun getCacheSizeBytes(): Long {
            return pages.size * 128L
        }

        override fun cleanup() = Unit

        fun cachedEntriesCount(): Int = pages.size
    }

    private data class Key(
        val chapterId: Long,
        val pageIndex: Int,
        val ocrModel: OcrModel,
    )

    private fun image(width: Int, height: Int): OcrImage {
        return OcrImage(
            width = width,
            height = height,
            pixels = IntArray(width * height),
        )
    }
}
