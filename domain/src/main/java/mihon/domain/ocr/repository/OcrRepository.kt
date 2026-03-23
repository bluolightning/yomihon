package mihon.domain.ocr.repository

import android.graphics.Bitmap
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult

interface OcrRepository {
    suspend fun recognizeText(image: Bitmap): String

    suspend fun scanPage(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
    ): OcrPageResult = OcrPageResult(
        chapterId = chapterId,
        pageIndex = pageIndex,
        ocrModel = OcrModel.LEGACY,
        imageWidth = image.width,
        imageHeight = image.height,
        regions = emptyList(),
    )

    suspend fun getCachedPage(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult? = null

    suspend fun getCachedChapterIds(chapterIds: Collection<Long>): Set<Long> = emptySet()

    suspend fun clearCachedChapter(chapterId: Long) = Unit

    suspend fun clearCache() = Unit

    suspend fun getCacheSizeBytes(): Long = 0L

    suspend fun <T> withScanSession(block: suspend () -> T): T = block()

    /**
     * Cleanup and release all OCR resources, which can take up lots of RAM.
     * Used for memory management when system is under pressure.
     */
    fun cleanup()
}
