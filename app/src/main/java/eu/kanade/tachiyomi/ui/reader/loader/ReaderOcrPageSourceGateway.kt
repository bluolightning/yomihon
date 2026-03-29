package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.ocr.OcrPageInput
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceGateway
import eu.kanade.tachiyomi.data.ocr.ResolvedOcrPages
import eu.kanade.tachiyomi.data.ocr.decodeArchiveBitmap
import eu.kanade.tachiyomi.data.ocr.decodeBitmap
import eu.kanade.tachiyomi.data.ocr.isArchiveImageEntry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

internal class ReaderOcrPageSourceGateway(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) : OcrPageSourceGateway {

    override suspend fun resolveDownloadedPages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): ResolvedOcrPages {
        val chapterPath = downloadProvider.findChapterDir(chapter.name, chapter.scanlator, manga.title, source)
        if (chapterPath?.isFile == true) {
            return resolveArchivePages(chapterPath)
        }

        val loader = DownloadPageLoader(
            chapter = ReaderChapter(chapter),
            manga = manga,
            source = source,
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
        )
        return loader.toResolvedPages()
    }

    override suspend fun resolveLocalPages(
        source: LocalSource,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val loader = when (val format = source.getFormat(chapter.toSChapter())) {
            is Format.Directory -> DirectoryPageLoader(format.file)
            is Format.Archive -> return resolveArchivePages(format.file)
            is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
        }
        return loader.toResolvedPages()
    }

    private suspend fun resolveArchivePages(
        file: UniFile,
    ): ResolvedOcrPages {
        val reader = file.archiveReader(context)
        val entryNames = withIOContext {
            buildList {
                reader.useEntriesAndStreams { entry, stream ->
                    if (entry.isFile && isArchiveImageEntry(entry.name, stream)) {
                        add(entry.name)
                    }
                }
            }
        }
            .sortedWith { entry1, entry2 ->
                entry1.compareToCaseInsensitiveNaturalOrder(entry2)
            }

        val pages = entryNames.mapIndexed { index, entryName ->
            OcrPageInput(
                pageIndex = index,
                openBitmap = {
                    withIOContext {
                        reader.getInputStream(entryName)?.use(::decodeArchiveBitmap)
                    }
                },
            )
        }

        return ResolvedOcrPages(
            pages = pages,
            closeBlock = reader::close,
        )
    }

    private suspend fun PageLoader.toResolvedPages(): ResolvedOcrPages {
        val pages = getPages().map { page ->
            OcrPageInput(
                pageIndex = page.index,
                openBitmap = {
                    withIOContext {
                        page.stream?.invoke()?.use(::decodeBitmap)
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
