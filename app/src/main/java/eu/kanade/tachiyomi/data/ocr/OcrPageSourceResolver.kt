package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ArchivePageLoader
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.EpubPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

internal class OcrPageSourceResolver(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) {
    suspend fun resolve(
        manga: Manga,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val source = sourceManager.getOrStub(manga.source)
        return when {
            downloadManager.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                manga.title,
                manga.source,
                skipCache = true,
            ) -> resolveDownloadedPages(manga, chapter, source)
            source is LocalSource -> resolveLocalPages(source, chapter)
            source is HttpSource -> resolveRemotePages(source, chapter)
            else -> ResolvedOcrPages(emptyList())
        }
    }

    private suspend fun resolveDownloadedPages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): ResolvedOcrPages {
        val loader = DownloadPageLoader(
            chapter = ReaderChapter(chapter),
            manga = manga,
            source = source,
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
        )
        return loader.toResolvedPages()
    }

    private suspend fun resolveLocalPages(
        source: LocalSource,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val loader = when (val format = source.getFormat(chapter.toSChapter())) {
            is Format.Directory -> DirectoryPageLoader(format.file)
            is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
            is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
        }
        return loader.toResolvedPages()
    }

    private suspend fun resolveRemotePages(
        source: HttpSource,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val pages = source.getPageList(chapter.toSChapter())
            .mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
            .map { page ->
                OcrPageInput(
                    pageIndex = page.index,
                    openBitmap = {
                        withIOContext {
                            if (page.imageUrl.isNullOrBlank()) {
                                page.imageUrl = source.getImageUrl(page)
                            }
                            source.getImage(page).use { response ->
                                BitmapFactory.decodeStream(response.body.byteStream())
                            }
                        }
                    },
                )
            }

        return ResolvedOcrPages(pages)
    }

    private suspend fun PageLoader.toResolvedPages(): ResolvedOcrPages {
        val pages = getPages().map { page ->
            OcrPageInput(
                pageIndex = page.index,
                openBitmap = {
                    withIOContext {
                        page.stream?.invoke()?.use(BitmapFactory::decodeStream)
                    }
                },
            )
        }
        return ResolvedOcrPages(
            pages = pages,
            closeBlock = ::recycle,
        )
    }
}

internal data class OcrPageInput(
    val pageIndex: Int,
    val openBitmap: suspend () -> Bitmap?,
)

internal class ResolvedOcrPages(
    val pages: List<OcrPageInput>,
    private val closeBlock: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        closeBlock()
    }
}
