package eu.kanade.tachiyomi.multisrc.oceanwp

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

abstract class OceanWP(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH),
) : ParsedHttpSource() {

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular / Latest ==============================
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/page/$page/", headers)

    override fun popularMangaSelector() = "article.blog-entry, article.entry"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst(".blog-entry-title a, .entry-title a")!!.let {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst(".thumbnail img")?.attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = "ul.page-numbers a.next"

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ============================== Search ==============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addPathSegments("page/$page/")
                addQueryParameter("s", query)
            } else {
                addPathSegments("page/$page/")
            }

            filters.forEach { filter ->
                when (filter) {
                    is UriPartFilter -> {
                        if (filter.toUriPart().isNotEmpty()) {
                            addQueryParameter(filterParam, filter.toUriPart())
                        }
                    }
                    else -> {}
                }
            }
        }.build()

        return GET(url, headers)
    }

    protected open val filterParam: String = "category_name"

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ============================== Manga Details ==============================
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val titleElement = document.selectFirst("h1.single-post-title, h1.entry-title")
        title = titleElement?.text() ?: ""

        val contentElement = document.selectFirst(".entry-content, .entry")
        description = contentElement?.select("p")?.eachText()?.joinToString("\n")

        genre = document.select(".post-tags a, .meta-category a").joinToString { it.text() }
        thumbnail_url = document.selectFirst(".thumbnail img, .entry-content img")?.attr("abs:src")

        // Try to parse Artists/Groups from description
        description?.let { desc ->
            if (author.isNullOrBlank()) {
                author = desc.lineSequence()
                    .find { it.contains("Artists", ignoreCase = true) || it.contains("Artist", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
            }
            if (artist.isNullOrBlank()) {
                artist = desc.lineSequence()
                    .find { it.contains("Artists", ignoreCase = true) || it.contains("Artist", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
            }
        }

        status = SManga.UNKNOWN
    }

    // ============================== Chapter List ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaTitle = document.selectFirst("h1.single-post-title, h1.entry-title")?.text() ?: "Chapter 1"

        val chapter = SChapter.create().apply {
            name = mangaTitle
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = document.selectFirst("time.published")?.attr("datetime")?.let {
                try {
                    dateFormat.parse(it)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            } ?: 0L
        }
        return listOf(chapter)
    }

    override fun chapterListSelector(): String = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException()

    // ============================== Page List ==============================
    override fun pageListParse(document: Document): List<Page> {
        return document.select(".entry-content img, .entry img, .gallery-icon img")
            .mapNotNull { it.attr("abs:src").takeIf { src -> src.isNotBlank() } }
            .distinct()
            .filterNot {
                it.contains("logo", ignoreCase = true) ||
                    it.contains("wp-content/uploads/", ignoreCase = true) && it.contains("-200x285") // Filter thumbnails
            }
            .mapIndexed { i, url -> Page(i, "", url) }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================
    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
