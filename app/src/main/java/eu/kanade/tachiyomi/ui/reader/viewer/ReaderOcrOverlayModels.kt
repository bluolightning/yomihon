package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.RectF
import mihon.domain.ocr.model.OcrBoundingBox

data class ReaderOcrPageIdentity(
    val chapterId: Long,
    val pageIndex: Int,
)

data class ReaderOcrRegionSelection(
    val page: ReaderOcrPageIdentity,
    val regionOrder: Int,
    val text: String,
    val boundingBox: OcrBoundingBox,
    val anchorRectOnScreen: RectF?,
    val initialSelectionOffset: Int,
)

data class ReaderPageOcrRegionTap(
    val regionOrder: Int,
    val text: String,
    val boundingBox: OcrBoundingBox,
    val anchorRectOnScreen: RectF?,
    val initialSelectionOffset: Int,
)

data class ReaderActiveOcrOverlay(
    val page: ReaderOcrPageIdentity,
    val regionOrder: Int,
    val text: String,
    val boundingBox: OcrBoundingBox,
    val highlightRange: Pair<Int, Int>? = null,
)

sealed interface ReaderActiveOcrTapResult {
    data class SelectWord(val offset: Int) : ReaderActiveOcrTapResult

    data object BubbleTap : ReaderActiveOcrTapResult
}

internal fun searchTextForOffset(
    text: String,
    offset: Int,
): String {
    if (text.isBlank()) return text
    return text.substring(offset.coerceIn(0, text.lastIndex))
}
