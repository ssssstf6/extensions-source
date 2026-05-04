package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

@Suppress("SpellCheckingInspection")
class Av1Encodes : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AV1Encodes"
    override val baseUrl = "https://av1encodes.com"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    // ─── Client Optimization ─────────────────────────────────────────────────
    override val client: OkHttpClient = network.client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .build()

    // ─── Headers ─────────────────────────────────────────────────────────────
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ══════════════════════════════════════════════════════════════════════════
    // CATALOG & SEARCH
    // ══════════════════════════════════════════════════════════════════════════
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/stats#top-downloads", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = parseStatsPage(doc)
        return AnimesPage(animes, false)
    }

    private fun parseStatsPage(doc: Document): List<SAnime> {
        val seen = mutableSetOf<String>()
        val animes = mutableListOf<SAnime>()
        var searchContext: Element = doc
        val header = doc.select("h1, h2, h3, h4, h5, h6").firstOrNull {
            it.text().contains("Top Downloads", ignoreCase = true)
        }

        if (header != null) {
            val sibling = header.nextElementSibling()
            searchContext = if (sibling != null && sibling.text().length > 20) {
                sibling
            } else {
                header.parent() ?: doc
            }
        }

        val items = searchContext.select("a[href*='/anime/'], div[class*='card'], div[class*='item'], li")
            .filter { el ->
                val text = el.text().trim()
                text.contains(Regex("""\[S\d""")) || text.length in 10..200
            }

        items.forEach { el ->
            val link = el.selectFirst("a[href*='/anime/']") ?: el.takeIf { it.tagName() == "a" && it.attr("href").contains("/anime/") }
            if (link != null) {
                val url = link.attr("href").let { if (it.startsWith("http")) it.removePrefix(baseUrl) else it }
                if (url.startsWith("/anime/") && seen.add(url)) {
                    animes.add(
                        SAnime.create().apply {
                            this.url = url
                            title = extractCleanTitle(el.text())
                            thumbnail_url = getListImageUrl(el)
                        }
                    )
                }
                return@forEach
            }
            val rawText = el.text().trim()
            val animeName = extractCleanTitle(rawText)
            val slug = animeName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            if (slug.length >= 3 && seen.add("/anime/$slug")) {
                animes.add(SAnime.create().apply {
                    url = "/anime/$slug"
                    title = animeName
                })
            }
        }
        fetchMissingCovers(animes)
        return animes
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/airing/sub?page=$page", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = parseUniversalList(response.asJsoup())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url.toString(), headers)
        }
        var typeValue = ""
        filters.forEach { if (it is TypeFilter) typeValue = TYPE_VALUES.getOrElse(it.state) { "" } }
        return when (typeValue) {
            "sub" -> GET("$baseUrl/airing/sub?page=$page", headers)
            "dual" -> GET("$baseUrl/airing/dual?page=$page", headers)
            else -> GET("$baseUrl/anime?page=$page", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseUniversalList(response.asJsoup())

    private fun parseUniversalList(doc: Document): AnimesPage {
        val animesMap = mutableMapOf<String, SAnime>()
        val elements = doc.select("a[href*='/anime/']")
        for (a in elements) {
            val href = a.attr("href").let { if (it.startsWith("http")) it.removePrefix(baseUrl) else it }
            val slug = href.substringAfter("/anime/").substringBefore("?")
            if (slug.length < 2 || href == "/anime/") continue
            val anime = animesMap.getOrPut(slug) { SAnime.create().apply { url = "/anime/$slug" } }
            var titleText = a.text().trim().takeIf { !it.contains(Regex("Episode|Ep", RegexOption.IGNORE_CASE)) } ?: a.attr("title").trim()
            if (titleText.isBlank()) {
                titleText = a.parents().firstOrNull { it.tagName() == "article" || it.className().contains("card") }
                    ?.selectFirst("h1, h2, h3, .title")?.text()?.trim() ?: ""
            }
            anime.title = extractCleanTitle(titleText)
            if (anime.thumbnail_url == null) anime.thumbnail_url = getListImageUrl(a)
        }
        val animes = animesMap.values.filter { it.title.isNotBlank() }.toList()
        fetchMissingCovers(animes)
        return AnimesPage(animes, doc.selectFirst("a[rel=next]") != null)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DETAILS & EPISODES
    // ══════════════════════════════════════════════════════════════════════════
    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val doc = response.asJsoup()
        title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        description = doc.selectFirst(".synopsis")?.text()?.trim()
        genre = doc.select(".genre-tag").joinToString { it.text().trim() }.ifBlank { null }
        thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body.string())
        val slug = response.request.url.encodedPath.split("/").last { it.isNotBlank() }
        val seasons = doc.select("[data-season]").map { it.attr("data-season") }.distinct().ifEmpty { listOf("1") }
        val encodedRes = prefQuality.replace(" ", "%20")
        val allEpisodes = mutableListOf<SEpisode>()

        for (season in seasons.sortedByDescending { it.toIntOrNull() ?: 0 }) {
            val epResponse = try { client.newCall(GET("$baseUrl/episodes/$slug/$season/$encodedRes", headers)).execute() } catch (_: Exception) { continue }
            if (!epResponse.isSuccessful) { epResponse.close(); continue }
            val filenames = extractFilenames(epResponse.body.string())
            filenames.forEach { filename ->
                allEpisodes.add(SEpisode.create().apply {
                    url = "/download/$slug/$season/$encodedRes/${URLEncoder.encode(filename, "UTF-8").replace("+", "%20")}"
                    name = buildEpisodeLabel(filename, season)
                    episode_number = parseEpisodeNumber(filename)
                })
            }
        }
        return allEpisodes.sortedByDescending { it.episode_number }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IP-SAFE VIDEO RESOLVER
    // ══════════════════════════════════════════════════════════════════════════
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return try {
            val encodedFilename = episode.url.substringAfterLast("/")
            
            // 1. Establish Session Token
            val tokenHeaders = headers.newBuilder().set("Accept", "*/*").set("Sec-Fetch-Mode", "cors").build()
            client.newCall(GET("$baseUrl/get_token", tokenHeaders)).execute().close()

            // 2. Fetch fresh Playback Data
            val ddlResponse = client.newCall(GET("$baseUrl/get_ddl/$encodedFilename", headers.newBuilder().set("Accept", "application/json").build())).execute()
            val ddl = json.decodeFromString<DdlResponse>(ddlResponse.body.string())
            if (!ddl.success) return emptyList()

            // 3. Forward session headers to player to maintain IP binding
            val videoHeaders = headers.newBuilder().set("Accept", "*/*").build()
            val videos = mutableListOf<Video>()
            
            val qualityLabel = "AV1 · ${ddl.fileSize ?: ""}"
            ddl.streamLink?.let { videos.add(Video(it, "$qualityLabel · Stream", it, headers = videoHeaders)) }
            ddl.downloadLink?.takeIf { it != ddl.streamLink }?.let { videos.add(Video(it, "$qualityLabel · Direct", it, headers = videoHeaders)) }
            
            videos
        } catch (e: Exception) { emptyList() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS & MODELS
    // ══════════════════════════════════════════════════════════════════════════
    private fun fetchMissingCovers(animes: List<SAnime>) = runBlocking {
        animes.filter { it.thumbnail_url == null }.map { anime ->
            async(Dispatchers.IO) {
                try {
                    client.newCall(GET(baseUrl + anime.url, headers)).execute().use { 
                        val html = it.body.string()
                        anime.thumbnail_url = Regex("""og:image" content="([^"]+)""").find(html)?.groupValues?.get(1)
                    }
                } catch (_: Exception) {}
            }
        }.awaitAll()
    }

    private fun extractFilenames(html: String): List<String> = Regex("""([a-zA-Z0-9_ \-\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE)
        .findAll(html).map { URLDecoder.decode(it.groupValues[1], "UTF-8") }.distinct().toList()

    private fun extractCleanTitle(raw: String): String = raw.replace(Regex("""\s*·\s*\d+\s*downloads.*|\[S\d+].*|\s*\[\d+p].*|\.(mkv|mp4)$""", RegexOption.IGNORE_CASE), "").trim()
    private fun buildEpisodeLabel(f: String, s: String) = Regex("""\[E(\d+)]\s*(.+?)\s*\[""").find(f)?.let { "S$s E${it.groupValues[1]} - ${it.groupValues[2]}" } ?: f.substringBeforeLast(".")
    private fun parseEpisodeNumber(f: String) = Regex("""\[E(\d+)]""").find(f)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
    private fun getListImageUrl(a: Element) = a.selectFirst("img")?.attr("abs:src")?.ifBlank { extractBg(a) }
    private fun extractBg(el: Element) = Regex("""url\(['"]?(.*?)['"]?\)""").find(el.attr("style"))?.groupValues?.get(1)?.let { if (it.startsWith("http")) it else "$baseUrl/$it" }

    override fun videoListParse(response: Response) = emptyList<Video>()
    override fun getFilterList() = AnimeFilterList(SortFilter(), TypeFilter())
    private class SortFilter : AnimeFilter.Select<String>("Sort", arrayOf("Latest", "A-Z", "Z-A"))
    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Sub", "Dual"))

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Resolution"
            entries = QUALITY_ENTRIES
            entryValues = QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val q = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(compareBy({ it.quality.contains(q) }, { it.quality.replace(Regex("\\D"), "").toIntOrNull() ?: 0 })).reversed()
    }

    @Serializable private data class DdlResponse(
        @SerialName("success") val success: Boolean,
        @SerialName("stream_link") val streamLink: String? = null,
        @SerialName("download_link") val downloadLink: String? = null,
        @SerialName("file_size") val fileSize: String? = null
    )

    companion object {
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1920 x 1080"
        private val QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val QUALITY_VALUES = arrayOf("1920 x 1080", "1280 x 720", "854 x 480", "640 x 360")
        private val TYPE_VALUES = arrayOf("", "sub", "dual")
    }
}
