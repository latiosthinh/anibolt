package com.animetv.scraper

import com.animetv.model.Anime
import com.animetv.model.AnimeDetail
import com.animetv.model.Episode
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

object HhpandaScraper {

    private const val REAL_URL = "https://hhpanda.st"
    const val SUGGEST_MIN_CHARS = 3
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Closes idle keep-alive sockets (and their TLS state) while the app is in the background. */
    fun releaseConnections() = client.connectionPool.evictAll()

    private fun fetch(url: String): Document {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "vi,en-US;q=0.7,en;q=0.3")
            .build()
        return client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Content unavailable (${resp.code})" }
            Jsoup.parse(resp.body?.string() ?: throw Exception("Empty response"), url)
        }
    }

    fun fetchDetail(slug: String): AnimeDetail {
        val url = "$REAL_URL/$slug"
        val doc = fetch(url)

        val title = doc.selectFirst("h1.movie_name")?.text()?.trim().orEmpty()
            .ifBlank { doc.select("meta[property=og:title]").attr("content").substringBefore(" - Xem ") }
        // The info block carries the 300x450 card art; og:image is a larger portrait fallback.
        val posterUrl = doc.selectFirst(".info-movie img.film-poster-img")?.attr("src").orEmpty()
            .ifBlank { doc.select("meta[property=og:image]").attr("content") }
        val description = doc.select("meta[name=description]").attr("content")
        val genres = doc.select(".list_cate a, .movie-detail-genre a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val info = doc.selectFirst(".hh3d-info")
        val views = info?.selectFirst(".new-ep")?.text()?.trim().orEmpty()
        // "Lượt xem: 2.7M Đang chiếu" -> "Đang chiếu"
        val status = info?.select("div")?.lastOrNull()?.ownText()?.trim().orEmpty()
        val rating = doc.selectFirst(".kk-star-ratings[data-payload]")?.attr("data-payload")
            ?.let { runCatching { JSONObject(it).optString("score") }.getOrNull() }
            .orEmpty()
            .ifBlank { doc.select("meta[itemprop=ratingValue]").attr("content") }

        val anime = Anime(
            title = title,
            slug = slug,
            url = url,
            posterUrl = posterUrl,
            rating = rating,
            originalTitle = doc.selectFirst(".org_title")?.text()?.trim().orEmpty(),
        )

        val episodes = mutableListOf<Episode>()
        val episodeLinks = doc.select("#vietsub a[href], .episode-list a[href]").ifEmpty {
            doc.select(".episodes-list li a, .list-episode a, .halim-list-episode a, .halim-list-eps a[href]")
        }
        episodeLinks.forEach { a ->
            val href = a.absUrl("href")
            val text = a.text().trim()
            val num = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            episodes.add(Episode(number = num, title = text, url = href))
        }
        episodes.sortByDescending { it.number }

        return AnimeDetail(
            anime = anime,
            description = description,
            genres = genres,
            episodes = episodes,
            status = status,
            views = views,
            latestEpisode = doc.selectFirst(".hh3d-new-ep .new-ep")?.text()?.trim().orEmpty(),
        )
    }

    /** The provider's "Mới cập nhật" page; qfilm's "Phim mới cập nhật" hero row. */
    fun fetchUpdated(): List<Anime> = fetchUpdated(1).items

    /** One page of the full "Mới cập nhật" listing (20 titles per page, newest first). */
    fun fetchUpdated(page: Int): ListingPage = fetchListingPage("$REAL_URL/moi-cap-nhat${pagePath(page)}", page)

    fun search(query: String, page: Int = 1): ListingPage {
        // WordPress puts the page before the query: /page/2?s=...
        val url = "$REAL_URL${pagePath(page)}/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return fetchListingPage(url, page)
    }

    /**
     * Title suggestions from the provider's own live search (the dropdown under its search box).
     * Accent-insensitive, at most six titles; the provider answers only from [SUGGEST_MIN_CHARS] characters.
     */
    fun suggest(query: String): List<Anime> {
        val body = FormBody.Builder()
            .add("action", "dox_ajax_live_search")
            .add("search", query)
            .build()
        val req = Request.Builder()
            .url("$REAL_URL/wp-admin/admin-ajax.php")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val html = client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Suggestions unavailable (${resp.code})" }
            resp.body?.string().orEmpty()
        }
        return Jsoup.parseBodyFragment(html, REAL_URL).select("li a[href]").mapNotNull { link ->
            val href = link.attr("href")
            if (href == REAL_URL || !href.startsWith(REAL_URL)) return@mapNotNull null
            Anime(
                title = link.selectFirst(".label")?.text()?.trim().orEmpty().ifBlank { link.attr("title") },
                slug = href.removePrefix(REAL_URL).trim('/'),
                url = href,
                posterUrl = link.selectFirst("img")?.attr("src").orEmpty(),
                originalTitle = link.selectFirst(".enName")?.text()?.trim().orEmpty(),
            )
        }.filter { it.title.isNotBlank() && it.slug.isNotBlank() }.distinctBy { it.slug }
    }

    fun fetchGenre(slug: String, page: Int = 1): ListingPage {
        require(slug.matches(Regex("[a-z0-9-]+")))
        return fetchListingPage("$REAL_URL/the-loai/$slug${pagePath(page)}", page)
    }

    private fun pagePath(page: Int): String {
        require(page >= 1)
        return if (page == 1) "" else "/page/$page"
    }

    /**
     * A listing page plus the provider's page count, read from its pagination links
     * ("1 2 3 … 25 Sau"): the highest page number shown, never less than the current page.
     */
    private fun fetchListingPage(url: String, page: Int): ListingPage {
        val doc = try {
            fetch(url)
        } catch (e: IllegalStateException) {
            // Past the last page WordPress answers 404: an empty page, not a failure.
            if (page > 1 && e.message?.contains("(404)") == true) return ListingPage(emptyList(), page, page - 1)
            throw e
        }
        val items = parseListing(doc)
        val lastShown = doc.select("a.page-numbers, span.page-numbers")
            .mapNotNull { it.text().replace(".", "").trim().toIntOrNull() }
            .maxOrNull() ?: 1
        // Past the end the provider serves an empty page: the previous one was the last.
        if (items.isEmpty() && page > 1) return ListingPage(items, page, minOf(lastShown, page - 1))
        return ListingPage(items, page, maxOf(lastShown, page))
    }

    private fun parseListing(doc: Document): List<Anime> {
        return doc.select("article.item, article.grid-item, .halim-post-box").mapNotNull { el ->
            val link = el.select("a[href]").first() ?: return@mapNotNull null
            val href = link.attr("href")
            if (href == REAL_URL || !href.startsWith(REAL_URL)) return@mapNotNull null
            val slug = href.removePrefix(REAL_URL).trim('/')
            Anime(
                title = el.selectFirst(".entry-title, h3")?.text() ?: link.attr("title"),
                slug = slug,
                url = href,
                posterUrl = el.select("img").attr("src")
                    .ifEmpty { el.select("img").attr("data-src") },
                originalTitle = el.selectFirst(".original_title")?.text()?.trim().orEmpty(),
                badge = el.selectFirst(".episode")?.text()?.trim().orEmpty(),
                quality = el.selectFirst(".status")?.text()?.trim().orEmpty(),
            )
        }.distinctBy { it.slug }
    }
}

/** One page of a provider listing; [totalPages] is at least [page]. */
data class ListingPage(val items: List<Anime>, val page: Int, val totalPages: Int)
