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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val dateFormat: SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH),
) : ParsedHttpSource() {

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val scope = CoroutineScope(Dispatchers.IO)

    protected fun launchIO(block: () -> Unit) = scope.launch { block() }

    private var fetchCategoriesAttempts: Int = 0
    protected var categoryList: List<Pair<String, String>> = emptyList()

    protected open fun categoriesRequest() = GET(baseUrl, headers)

    protected open val categorySelector = "ul.megamenu li a, .post-tags a, .meta-category a, .tagcloud a"

    protected open val categoryUrlDelimiter = "/"

    protected open fun parseCategories(document: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val items = document.select(categorySelector)
        return buildList(items.size + 1) {
            add(Pair("All", ""))
            items.mapTo(this) {
                val text = it.text()
                val href = it.attr("abs:href")
                val path = try {
                    val http = href.toHttpUrl()
                    val segs = http.encodedPathSegments.filter { it.isNotEmpty() }
                    if (segs.size >= 2) {
                        "${segs[segs.size - 2]}/${segs.last()}"
                    } else {
                        segs.lastOrNull() ?: ""
                    }
                } catch (_: Exception) {
                    val cleanHref = it.attr("href").removeSuffix("/")
                    val parts = cleanHref.split(categoryUrlDelimiter).filter { it.isNotEmpty() }
                    if (parts.size >= 2) {
                        "${parts[parts.size - 2]}/${parts.last()}"
                    } else {
                        parts.lastOrNull() ?: ""
                    }
                }

                Pair(text, path)
            }
        }
    }

    protected fun fetchCategories() {
        if (fetchCategoriesAttempts < 3 && categoryList.isEmpty()) {
            try {
                categoryList = client.newCall(categoriesRequest()).execute()
                    .asJsoup()
                    .let(::parseCategories)
            } catch (_: Exception) {
            } finally {
                fetchCategoriesAttempts++
            }
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular / Latest ==============================
    protected fun pagePathSegment(page: Int): String = if (page > 1) "page/$page/" else ""

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/${pagePathSegment(page)}", headers)

    override fun popularMangaSelector() = "article.blog-entry, article.entry"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleLink = element.selectFirst(".blog-entry-title a, .entry-title a")
        titleLink?.let {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst(".thumbnail img, img")?.let { imageOrNull(it) }
    }

    override fun popularMangaNextPageSelector() = "ul.page-numbers a.next"

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ============================== Search ==============================
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val uriParts = mutableListOf<String>()
        filters.forEach { filter ->
            if (filter is UriPartFilter) {
                val part = filter.toUriPart()
                if (part.isNotEmpty()) uriParts.add(part)
            }
        }

        val base = baseUrl.removeSuffix("/")

        if (query.isNotEmpty()) {
            val urlBuilder = base.toHttpUrl().newBuilder()
            if (page > 1) urlBuilder.addPathSegments("page/$page")
            urlBuilder.addQueryParameter("s", query)
            return GET(urlBuilder.build(), headers)
        }

        // If one or more uriParts (category/tag) are provided, try variants on the real site
        if (uriParts.isNotEmpty()) {
            val tried = mutableListOf<String>()

            for (rawPart in uriParts) {
                val cleaned = rawPart.trim('/').trim()
                val candidates = mutableListOf<String>()
                if (cleaned.contains('/')) {
                    candidates.add(cleaned)
                } else {
                    candidates.add(cleaned)
                    candidates.add("genre/$cleaned")
                    candidates.add("category/$cleaned")
                    candidates.add("tag/$cleaned")
                }

                for (candidate in candidates) {
                    if (tried.contains(candidate)) continue
                    tried.add(candidate)

                    val sb = StringBuilder(base)
                    sb.append('/')
                    sb.append(candidate)
                    if (page > 1) {
                        if (!sb.endsWith('/')) sb.append('/')
                        sb.append("page/")
                        sb.append(page)
                        sb.append('/')
                    } else {
                        if (!sb.endsWith('/')) sb.append('/')
                    }

                    try {
                        val req = GET(sb.toString(), headers)
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) return req
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            // Fallback: use the first provided raw part with prefixed category as last resort
            val fallback = uriParts.first().trim('/').trim()
            val fb = StringBuilder(base)
            fb.append('/')
            fb.append(if (fallback.contains('/')) fallback else "category/$fallback")
            if (page > 1) {
                if (!fb.endsWith('/')) fb.append('/')
                fb.append("page/")
                fb.append(page)
                fb.append('/')
            } else {
                if (!fb.endsWith('/')) fb.append('/')
            }

            return GET(fb.toString(), headers)
        }

        // No query, no uriPart: return base or base/page/N
        return if (page > 1) GET("$base/page/$page/", headers) else GET("$base/", headers)
    }

    protected open val filterParam: String = "category_name"

    protected open val useTags: Boolean = true

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ============================== Manga Details ==============================
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.single-post-title, h1.entry-title, h2.single-post-title, h2.entry-title")!!.text()

        val contentElement = document.selectFirst(".entry-content, .entry")!!
        
        description = parseDescription(contentElement)

        genre = document
            .select(".meta-cat a[rel=\"category tag\"], .meta-category a")
            .joinToString { it.text() }

        thumbnail_url = document.selectFirst(".entry-header img, .thumbnail img")!!.attr("abs:src")

        author = parseAuthor(contentElement)

        status = SManga.UNKNOWN
    }

    private fun parseDescription(contentElement: Element): String {
        return contentElement.select("p")
            .eachText()
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun parseAuthor(contentElement: Element): String? {
        return contentElement.select("li").firstNotNullOfOrNull { li ->
            if (li.text().contains("Artists", ignoreCase = true)) {
                li.selectFirst("em")!!.text().trim()
            } else {
                null
            }
        }
    }

    // ============================== Chapter List ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaTitle = document.selectFirst("h1.single-post-title, h1.entry-title, h2.single-post-title, h2.entry-title")!!.text()

        val chapter = SChapter.create().apply {
            name = mangaTitle
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = document.selectFirst("time.published")!!.attr("datetime").let { dateStr ->
                try {
                    dateFormat.parse(dateStr)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }
        }

        return listOf(chapter)
    }

    override fun chapterListSelector(): String = throw UnsupportedOperationException()

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException()

    // ============================== Page List ==============================
    open fun imageOrNull(element: Element): String? {
        fun Element.hasValidAttr(attr: String): Boolean {
            val regex = Regex("""https?://.*""", RegexOption.IGNORE_CASE)
            return when {
                this.attr(attr).isNullOrBlank() -> false
                this.attr("abs:$attr").matches(regex) -> true
                else -> false
            }
        }

        return when {
            element.hasValidAttr("data-original") -> element.attr("abs:data-original")
            element.hasValidAttr("data-src") -> element.attr("abs:data-src")
            element.hasValidAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
            element.hasValidAttr("data-srcset") -> element.attr("abs:data-srcset").split(",").firstOrNull()?.trim()
            element.hasValidAttr("src") -> element.attr("abs:src")
            else -> null
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select(".entry-content img, .entry img, .gallery-icon img")
            .mapNotNull { imageOrNull(it) }
            .distinct()
            .filterNot { url ->
                url.contains("logo", ignoreCase = true) ||
                    (url.contains("wp-content/uploads/", ignoreCase = true) &&
                        (url.contains("-200x285") || url.contains("-150x") || url.contains("-100x")))
            }
            .mapIndexed { i, url -> Page(i, "", url) }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================
    override fun getFilterList(): FilterList {
        try {
            fetchCategories()
        } catch (_: Exception) {
        }

        if (categoryList.isEmpty()) return FilterList(Filter.Header("Categories unavailable"))

        val categories = mutableListOf<Pair<String, String>>()
        val tags = mutableListOf<Pair<String, String>>()
        val seenPaths = mutableSetOf<String>()

        for (pair in categoryList) {
            val name = pair.first
            val path = pair.second

            if (seenPaths.contains(path)) continue
            seenPaths.add(path)

            if (path.isBlank()) {
                categories.add(pair)
                continue
            }

            val isTag = path.startsWith("tag/", ignoreCase = true)
            if (isTag && !useTags) continue

            if (isTag) {
                tags.add(pair)
            } else {
                categories.add(pair)
            }
        }

        val filters = mutableListOf<Filter<*>>()
        if (categories.isNotEmpty()) filters.add(UriPartFilter("Category", categories.toTypedArray()))
        if (tags.isNotEmpty()) filters.add(UriPartFilter("Tag", tags.toTypedArray()))

        return if (filters.isEmpty()) FilterList(Filter.Header("Categories unavailable")) else FilterList(*filters.toTypedArray())
    }
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : Filter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart(): String = vals.getOrNull(state)?.second ?: ""
    }
}
