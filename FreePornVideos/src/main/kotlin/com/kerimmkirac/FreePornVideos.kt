// Based on the FreePornVideos extension by @kerimmkirac.

package com.kerimmkirac

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class FreePornVideos : MainAPI() {
    override var mainUrl              = "https://www.freepornvideos.xxx"
    override var name                 = "Free Porn Videos+"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)

    // Cada entrada vira uma seção da home e um chip de filtro no Kototoro.
    // As listas de tags / estúdios / atrizes ficam em Listas.kt (gerado por tools/gerar_listas.py).
    private fun entradasDaHome(): Array<Pair<String, String>> {
        val fixas = listOf(
            "$mainUrl/latest-updates" to "All Videos",
            "$mainUrl/most-popular/week" to "Most Popular",

            // Listas de entidades (atrizes / sites): paginadas ao vivo pelo site
            "$mainUrl/models" to "Pornstars - Most Viewed",
            "$mainUrl/models/top-rated" to "Pornstars - Top Rated",
            "$mainUrl/sites" to "Sites"
        )

        fun converte(linhas: List<String>, caminho: (String) -> String, rotulo: (String) -> String) =
            linhas.mapNotNull { linha ->
                val partes = linha.split("|", limit = 2)
                if (partes.size < 2) null else "$mainUrl/${caminho(partes[0])}" to rotulo(partes[1])
            }

        val tags = converte(Listas.tags, { "categories/$it" }, { it })
        val estudios = converte(Listas.estudios, { it }, { "Studio: $it" })
        val atrizes = converte(Listas.atrizes, { "models/$it" }, { "Pornstar: $it" })

        return (fixas + tags + estudios + atrizes)
            .distinctBy { it.first }   // mesma URL só uma vez
            .distinctBy { it.second }  // mesmo nome de chip só uma vez
            .toTypedArray()
    }

    override val mainPage = mainPageOf(*entradasDaHome())

    // ---------------------------------------------------------------- constantes / helpers

    // Qualquer página de listagem de vídeos (atriz, categoria, canal, rede) usa div.item com a.thumb_title
    private val seletorListagem = "div.item:has(a.thumb_title)"

    // Páginas de listagem de entidades (atrizes, sites): /models/, /sites/ ...
    private val raizesEntidades = setOf("models", "models/top-rated", "sites")
    private val regexEntidade = Regex("""/(models|sites|networks)/[^/?]+/?$""")

    // Quantas páginas de uma atriz/site/categoria viram "episódios" ao abrir o card
    private val maxPaginasColecao = 5

    private class Pagina(val itens: List<SearchResponse>, val temProxima: Boolean)
    private class FonteVideo(val url: String, val label: String)

    // Fontes de vídeo já lidas no load(), pra loadLinks não baixar a página de novo (validade curta: os links expiram)
    private val cacheFontes = ConcurrentHashMap<String, Pair<Long, List<FonteVideo>>>()
    private val validadeCacheMs = 5 * 60 * 1000L

    // Pra cada texto de busca, em quais tipos (models/categories/sites/networks) ele existe de verdade
    private val cacheDireto = ConcurrentHashMap<String, List<String>>()

    fun String?.createSlug(): String? {
        return this?.let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }
            ?.filter { it.isWhitespace() || it.isLetterOrDigit() }
            ?.trim()
            ?.replace("\\s+".toRegex(), "-")
            ?.lowercase()
    }

    private fun Document.temProxima(): Boolean =
        selectFirst(".pagination li.next a[href]") != null

    private fun posterDe(el: Element, seletor: String): String? {
        for (img in el.select(seletor)) {
            val src = img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            if (src != null) return fixUrlNull(src)
        }
        return null
    }

    // ---------------------------------------------------------------- cards

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            if (selectFirst("div.img iframe") != null) return null // anúncio

            val linkElement = selectFirst("a.thumb_title") ?: return null
            val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return null
            val title = linkElement.selectFirst("strong.title")?.text()?.takeIf { it.isNotBlank() } ?: return null
            val poster = posterDe(this, "img.thumb") ?: return null

            // "100%" -> 0..10
            val nota = selectFirst("div.rating")?.text()?.filter { it.isDigit() }?.toIntOrNull()

            return newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
                if (nota != null) this.score = Score.from10((nota / 10f).toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("FPV", "Error in toSearchResult: ${e.message}")
            return null
        }
    }

    // Cards de atrizes e sites: <a class="item" href=".../models/x/"> com foto, nome e nº de vídeos
    private fun Document.toEntidades(): List<SearchResponse> {
        return select("a.item:has(img)").mapNotNull { a ->
            try {
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                if (!regexEntidade.containsMatchIn(href)) return@mapNotNull null

                val nome = a.selectFirst("strong.title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val total = a.selectFirst("div.videos")?.text()?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
                val poster = posterDe(a, "img.thumb") ?: posterDe(a, "img")

                newMovieSearchResponse(if (total != null) "$nome ($total)" else nome, href, TvType.NSFW) {
                    this.posterUrl = poster
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("FPV", "Error in toEntidades: ${e.message}")
                null
            }
        }.distinctBy { it.url }
    }

    private suspend fun buscaLista(url: String, seletor: String): Pagina {
        return try {
            val doc = app.get(url).document
            Pagina(doc.select(seletor).mapNotNull { it.toSearchResult() }, doc.temProxima())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("FPV", "Erro ao abrir $url: ${e.message}")
            Pagina(emptyList(), false)
        }
    }

    // ---------------------------------------------------------------- home

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val caminho = request.data.removePrefix(mainUrl).trim('/')
        val ehEntidade = caminho in raizesEntidades

        val document = try {
            app.get("${request.data}/$page/").document
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Na primeira página o erro aparece pro usuário; depois dela é só "acabou a lista"
            if (page == 1) throw e
            Log.d("FPV", "Fim da lista em ${request.data} (página $page): ${e.message}")
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = emptyList(), isHorizontalImages = !ehEntidade),
                hasNext = false
            )
        }

        val home = if (ehEntidade) document.toEntidades()
        else document.select(seletorListagem).mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = !ehEntidade // fotos de atrizes ficam melhores na vertical
            ),
            hasNext = home.isNotEmpty() && document.temProxima()
        )
    }

    // ---------------------------------------------------------------- busca

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchquery = query.createSlug() ?: ""
        val pagina = if (page < 1) 1 else page

        // 1) Texto igual ao nome de atriz/categoria/canal/rede -> vídeos dela(e).
        //    Na 1ª página testa os 4 tipos; nas seguintes só os que existiam de verdade.
        val diretas = if (searchquery.isNotBlank()) {
            val tipos = cacheDireto[searchquery] ?: listOf("models", "categories", "sites", "networks")
            val resultados = tipos.amap { tipo ->
                tipo to buscaLista("$mainUrl/$tipo/$searchquery/$pagina/", seletorListagem)
            }
            if (pagina == 1) {
                cacheDireto[searchquery] = resultados.filter { it.second.itens.isNotEmpty() }.map { it.first }
            }
            resultados.map { it.second }
        } else emptyList()

        // 2) Busca normal do site (título, código numérico etc.)
        val daBusca = buscaLista(
            "$mainUrl/search/$searchquery/$pagina/",
            "#custom_list_videos_videos_list_search_result_items > div.item"
        )

        val todos = (diretas.flatMap { it.itens } + daBusca.itens).distinctBy { it.url }
        val hasNext = daBusca.temProxima || diretas.any { it.temProxima }
        return newSearchResponseList(todos, hasNext = hasNext)
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse {
        return if (url.contains("/videos/")) loadVideo(url) else loadColecao(url)
    }

    private fun Document.fontesDeVideo(): List<FonteVideo> =
        select("video source[src]").mapNotNull {
            val src = it.attr("src").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
            FonteVideo(fixUrl(src), it.attr("label"))
        }

    private suspend fun loadVideo(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document

        if (cacheFontes.size > 30) cacheFontes.clear()
        cacheFontes[url] = System.currentTimeMillis() to document.fontesDeVideo()

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

    // Atriz / site / rede / categoria (card da home ou do "Pornstars"): vira uma "série" onde cada vídeo é um episódio
    private suspend fun loadColecao(url: String): LoadResponse {
        val base = url.substringBefore("?").trimEnd('/')
        val primeira = app.get("$base/").document

        val titulo = primeira.selectFirst("div.headline h1")?.text()?.trim()
            ?.replace(Regex("""'s Videos$"""), "")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: primeira.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: base.substringAfterLast('/').replace('-', ' ')

        // Maior número de página que aparece na paginação (o botão "Last" já aponta pra ela)
        val totalPaginas = primeira.select(".pagination a[href]")
            .mapNotNull { Regex("""/(\d+)/?$""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1
        val paginasLidas = minOf(totalPaginas, maxPaginasColecao)

        val primeiros = primeira.select(seletorListagem).mapNotNull { it.toSearchResult() }
        val demais = if (paginasLidas > 1) {
            (2..paginasLidas).toList().amap { p -> buscaLista("$base/$p/", seletorListagem).itens }.flatten()
        } else emptyList()

        val videos = (primeiros + demais).distinctBy { it.url }

        val episodios = videos.mapIndexed { i, v ->
            newEpisode(v.url) {
                this.name = v.name
                this.posterUrl = v.posterUrl
                this.episode = i + 1
            }
        }

        val poster = fixUrlNull(primeira.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: videos.firstOrNull()?.posterUrl

        val plot = buildString {
            append("${videos.size} vídeos carregados")
            if (totalPaginas > paginasLidas) {
                append(" (primeiras $paginasLidas de $totalPaginas páginas — use a busca pra ver o resto)")
            }
        }

        return newTvSeriesLoadResponse(titulo, url, TvType.NSFW, episodios) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val agora = System.currentTimeMillis()
        val doCache = cacheFontes[data]?.takeIf { agora - it.first < validadeCacheMs }?.second
        val fontes = doCache ?: app.get(data).document.fontesDeVideo()
        if (fontes.isEmpty()) return false

        // Resolve os redirects de todas as qualidades ao mesmo tempo, só com HEAD (sem baixar o vídeo)
        fontes.amap { fonte ->
            val finalUrl = try {
                app.head(fonte.url, referer = data, allowRedirects = true).url
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("FPV", "HEAD falhou pra ${fonte.label}, usando a URL original: ${e.message}")
                fonte.url
            }

            val ehM3u8 = finalUrl.substringBefore("?").trimEnd('/').endsWith(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = "FPV",
                    name = "FPV ${fonte.label}".trim(),
                    url = finalUrl,
                    type = if (ehM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = getQualityFromName(fonte.label)
                }
            )
        }

        return true
    }
}
