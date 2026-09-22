package eu.kanade.tachiyomi.extension.all.xianscan

import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.HttpUrl

@Source
abstract class XianScan : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        return client.get(
            "$baseUrl/api/mihon/library?page=$page",
        ).parseAs<LibraryDto>().let { dto ->
            MangasPage(
                dto.books.map { it.toSManga(baseUrl) },
                dto.hasNextPage,
            )
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return getPopularManga(page)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val status = filters
            .filterIsInstance<StatusFilter>()
            .firstOrNull()
            ?.state
            ?.takeIf { it > 0 }
            ?.let { STATUS_KEYS[it - 1] }

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/mihon/search")
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .apply {
                status?.let { addQueryParameter("status", it) }
            }
            .build()

        return client.get(url).parseAs<LibraryDto>().let { dto ->
            MangasPage(
                dto.books.map { it.toSManga(baseUrl) },
                dto.hasNextPage,
            )
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            client.get("$baseUrl${manga.url}")
                .parseAs<MangaDto>()
                .toSManga(baseUrl)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            client.get("$baseUrl${manga.url}/chapters")
                .parseAs<ChapterListDto>()
                .chapters
                .map { chapter ->
                    SChapter.create().apply {
                        url = chapter.url
                        name = chapter.name
                        date_upload = chapter.dateUpload
                        chapter_number = chapter.chapterNumber
                        scanlator = "XianScan"
                    }
                }
                .sortedByDescending { it.chapter_number }
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return client.get("$baseUrl${chapter.url}/pages")
            .parseAs<PageListDto>()
            .pages
            .map { page ->
                val imageUrl = page.imageUrl.toAbsolute(baseUrl)
                Page(
                    index = page.index,
                    imageUrl = imageUrl,
                )
            }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        if (!url.encodedPath.startsWith("/api/mihon/manga/")) {
            return null
        }

        return client.get(url)
            .parseAs<MangaDto>()
            .toSManga(baseUrl)
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList {
        return FilterList(StatusFilter())
    }
}

private class StatusFilter : Filter.Select<String>(
    "Status",
    arrayOf("All") + STATUS_KEYS.map {
        it.replace('_', ' ')
            .replaceFirstChar { char -> char.uppercaseChar() }
    }.toTypedArray(),
)

private val STATUS_KEYS = listOf(
    "unknown",
    "ongoing",
    "completed",
    "licensed",
    "publishing_finished",
    "cancelled",
    "on_hiatus",
)
