// Based on the FreePornVideos extension by @kerimmkirac.


package com.kerimmkirac

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class FreePornVideos : MainAPI() {
    override var mainUrl              = "https://www.freepornvideos.xxx"
    override var name                 = "Free Porn Videos+"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates" to "All Videos",
        "$mainUrl/most-popular/week" to "Most Popular",
        "$mainUrl/networks/mom-lover" to "Mom Lover",
        "$mainUrl/networks/brazzers-com" to "Brazzers",
        "$mainUrl/networks/mylf-com" to "MYLF",
        "$mainUrl/networks/adult-time" to "Adult Time",
        "$mainUrl/networks/rk-com" to "Reality Kings",
        "$mainUrl/categories/jav-uncensored" to "Jav",
        "$mainUrl/categories/milf" to "MILF"

    )

   override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
      val document = app.get("${request.data}/$page/").document
    
    
    val home = document.select("div.item").mapNotNull {
        try {
            it.toSearchResult()
        } catch (e: Exception) {
            
            null
        }
    }

    return newHomePageResponse(
        list = HomePageList(
            name = request.name,
            list = home,
            isHorizontalImages = true
        ),
        hasNext = true
    )
}

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            if (selectFirst("div.img iframe") != null) return null

            val linkElement = selectFirst("a.thumb_title") ?: return null
            val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return null
            val title = linkElement.selectFirst("strong.title")?.text()?.takeIf { it.isNotBlank() } ?: return null

            var posterUrl: String? = null
            for (img in select("img.thumb")) {
                img.attr("data-src").takeIf { it.isNotBlank() }?.let {
                    posterUrl = it; break
                }
                img.attr("src").takeIf { it.isNotBlank() && !it.contains("data:image") }?.let {
                    posterUrl = it; break
                }
            }
            if (posterUrl == null) return null
            return newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.d("FPV", "Error in toSearchResult: ${e.message}")
            return null
        }
    }

    fun String?.createSlug(): String? {
        return this?.filter { it.isWhitespace() || it.isLetterOrDigit() }
            ?.trim()
            ?.replace("\\s+".toRegex(), "-")
            ?.lowercase()
    }

    private suspend fun buscaLista(url: String, seletor: String): List<SearchResponse> {
        return try {
            app.get(url).document.select(seletor).mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            Log.d("FPV", "Erro ao abrir $url: ${e.message}")
            emptyList()
        }
    }

    // Qualquer página de listagem do site (atriz, categoria, canal, rede) usa div.item com a.thumb_title
    private val seletorListagem = "div.item:has(a.thumb_title)"

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchquery = query.createSlug() ?: ""
        val pagina = if (page < 1) 1 else page

        // 1) Texto igual ao nome de atriz/categoria(tag)/canal/rede do site -> mostra os vídeos dela(e)
        val diretas = if (searchquery.isNotBlank()) {
            listOf("models", "categories", "sites", "networks")
                .amap { tipo -> buscaLista("${mainUrl}/$tipo/$searchquery/$pagina/", seletorListagem) }
                .flatten()
        } else emptyList()

        // 2) Busca normal do site (título, código numérico etc.)
        val daBusca = buscaLista("${mainUrl}/search/$searchquery/$pagina/", "#custom_list_videos_videos_list_search_result_items > div.item")

        val todos = (diretas + daBusca).distinctBy { it.url }
        return newSearchResponseList(todos, hasNext = todos.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document

        val title = document.selectFirst("div.headline > h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Sem título"

        val poster = fixUrlNull(
            document.selectFirst("[property='og:image']")?.attr("content")
                ?: document.selectFirst("video[poster]")?.attr("poster")
                ?: document.selectFirst("div[poster]")?.attr("poster")
        )

        // Tags (categorias) - inclui as escondidas atrás do botão "+"
        val tags = document.select("div.block-details a.btn_tag").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
            .ifEmpty { document.select("a.btn_tag").map { it.text().trim() }.filter { it.isNotBlank() }.distinct() }

        val actors = document.select("div.block-details a.btn_model").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
            .ifEmpty { document.select("a.btn_model").map { it.text().trim() }.filter { it.isNotBlank() }.distinct() }

        val canal = document.selectFirst("a.btn_sponsor")?.text()?.trim()
        val rede  = document.selectFirst("a.btn_sponsor_group")?.text()?.trim()

        // O site não tem campo de descrição: mostra Canal/Rede/Tags na sinopse
        val descricaoSite = document.selectXpath("//div[contains(text(), 'Description:')]/em").text().trim()
        val description = descricaoSite.ifBlank {
            buildString {
                if (!canal.isNullOrBlank()) append("Canal: $canal\n")
                if (!rede.isNullOrBlank()) append("Rede: $rede\n")
                if (tags.isNotEmpty()) append("Tags: ${tags.joinToString(", ")}")
            }.trim()
        }

        val recommendations = document.select("div#list_videos_related_videos_items div.item").mapNotNull { it.toSearchResult() }

        val year = Regex("""\b((?:19|20)\d{2})\s*$""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // Nota = likes / (likes + dislikes) numa escala de 0 a 10
        val likes    = document.selectFirst("a.rate-like + span.count")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val dislikes = document.selectFirst("a.rate-dislike + span.count")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val rating = if (likes != null && dislikes != null && likes + dislikes > 0)
            (likes * 10f / (likes + dislikes)).toString() else null

        // Duração está num script: $('.vjs-duration-display').html('46:30')
        val rawDuration = Regex("""vjs-duration-display'\)\.html\('([\d:]+)'""").find(response.text)?.groupValues?.get(1)
            ?: document.selectXpath("//span[contains(text(), 'Duration')]/em").text().trim()
        val partes = rawDuration.split(":").mapNotNull { it.trim().toIntOrNull() }
        val duration = when (partes.size) {
            3 -> partes[0] * 60 + partes[1]
            2 -> partes[0] + if (partes[1] >= 30) 1 else 0
            1 -> partes[0]
            else -> 0
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
            this.score           = Score.from10(rating)
            this.duration        = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
    val document = app.get(data).document
    
    document.select("video source").forEach { res ->
        val redirectUrl = res.attr("src")
        val quality = res.attr("label")
        
        try {
            
            val response = app.get(redirectUrl, allowRedirects = true)
            val finalUrl = response.url
            
            callback.invoke(
                newExtractorLink(
                    source = "FPV",
                    name = "FPV",
                    url = finalUrl,
                    
                    type = if (finalUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ){
                    this.referer = data
                    this.quality = getQualityFromName(quality)
                }
            )
        } catch (e: Exception) {
            Log.d("FPV", "Error getting final URL for $quality: ${e.message}")
        }
    }

    return true
}}
