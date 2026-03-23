package mihon.domain.ocr.interactor

import android.graphics.Bitmap
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.repository.OcrRepository

class ScanPageOcr(
    private val ocrRepository: OcrRepository,
) {
    suspend fun await(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
    ): OcrPageResult {
        return ocrRepository.scanPage(chapterId, pageIndex, image)
    }
}
