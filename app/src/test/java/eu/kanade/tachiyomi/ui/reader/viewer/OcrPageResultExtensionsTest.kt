package eu.kanade.tachiyomi.ui.reader.viewer

import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPageResultExtensionsTest {

    @Test
    fun findRegionAtReturnsMatchingRegion() {
        val result = OcrPageResult(
            chapterId = 1L,
            pageIndex = 0,
            ocrModel = OcrModel.LEGACY,
            imageWidth = 100,
            imageHeight = 200,
            regions = listOf(
                OcrRegion(
                    order = 0,
                    text = "hello",
                    boundingBox = OcrBoundingBox(
                        left = 0.10f,
                        top = 0.10f,
                        right = 0.30f,
                        bottom = 0.30f,
                    ),
                ),
                OcrRegion(
                    order = 1,
                    text = "world",
                    boundingBox = OcrBoundingBox(
                        left = 0.50f,
                        top = 0.50f,
                        right = 0.80f,
                        bottom = 0.80f,
                    ),
                ),
            ),
        )

        assertEquals("hello", result.findRegionAt(sourceX = 15f, sourceY = 40f)?.text)
        assertEquals("world", result.findRegionAt(sourceX = 65f, sourceY = 160f)?.text)
        assertNull(result.findRegionAt(sourceX = 95f, sourceY = 10f))
    }

    @Test
    fun findRegionAtPrefersFirstMatchingRegion() {
        val result = OcrPageResult(
            chapterId = 1L,
            pageIndex = 0,
            ocrModel = OcrModel.LEGACY,
            imageWidth = 100,
            imageHeight = 100,
            regions = listOf(
                OcrRegion(
                    order = 0,
                    text = "first",
                    boundingBox = OcrBoundingBox(
                        left = 0.10f,
                        top = 0.10f,
                        right = 0.60f,
                        bottom = 0.60f,
                    ),
                ),
                OcrRegion(
                    order = 1,
                    text = "second",
                    boundingBox = OcrBoundingBox(
                        left = 0.25f,
                        top = 0.25f,
                        right = 0.75f,
                        bottom = 0.75f,
                    ),
                ),
            ),
        )

        assertEquals("first", result.findRegionAt(sourceX = 40f, sourceY = 40f)?.text)
    }

    @Test
    fun boundingBoxContainsUsesInclusiveEdgesAndImageSize() {
        val box = OcrBoundingBox(
            left = 0.25f,
            top = 0.10f,
            right = 0.50f,
            bottom = 0.40f,
        )

        assertTrue(box.contains(sourceX = 25f, sourceY = 10f, imageWidth = 100, imageHeight = 100))
        assertTrue(box.contains(sourceX = 50f, sourceY = 40f, imageWidth = 100, imageHeight = 100))
        assertFalse(box.contains(sourceX = 24.9f, sourceY = 10f, imageWidth = 100, imageHeight = 100))
        assertFalse(box.contains(sourceX = 25f, sourceY = 41f, imageWidth = 100, imageHeight = 100))
    }

    @Test
    fun boundingBoxContainsRejectsInvalidImageDimensions() {
        val box = OcrBoundingBox(
            left = 0.0f,
            top = 0.0f,
            right = 1.0f,
            bottom = 1.0f,
        )

        assertFalse(box.contains(sourceX = 0f, sourceY = 0f, imageWidth = 0, imageHeight = 100))
        assertFalse(box.contains(sourceX = 0f, sourceY = 0f, imageWidth = 100, imageHeight = 0))
    }

    @Test
    fun pageTextJoinsRegionsInOrder() {
        val result = OcrPageResult(
            chapterId = 1L,
            pageIndex = 0,
            ocrModel = OcrModel.GLENS,
            imageWidth = 100,
            imageHeight = 100,
            regions = listOf(
                OcrRegion(
                    order = 1,
                    text = "second",
                    boundingBox = OcrBoundingBox(
                        left = 0.5f,
                        top = 0.5f,
                        right = 0.6f,
                        bottom = 0.6f,
                    ),
                ),
                OcrRegion(
                    order = 0,
                    text = "first",
                    boundingBox = OcrBoundingBox(
                        left = 0.1f,
                        top = 0.1f,
                        right = 0.2f,
                        bottom = 0.2f,
                    ),
                ),
            ),
        )

        assertEquals("second first", result.text)
    }
}
