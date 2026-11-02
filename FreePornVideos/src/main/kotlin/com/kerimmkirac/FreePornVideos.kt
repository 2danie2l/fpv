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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class FreePornVideos : MainAPI() {
    override var mainUrl              = "https://www.freepornvideos.xxx"
    override var name                 = "Free Porn Videos+"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)

    // Cada entrada vira uma seção da home E um chip de filtro no Kototoro.
    // IMPORTANTE: sem nenhum chip selecionado o Kototoro carrega as seções da home, então
    // esta lista precisa ficar pequena. Tudo que não está aqui continua acessível pela busca
    // (ver "Busca com filtros" abaixo) e pelas listas "Pornstars" e "Sites".
    private val tagsPopulares = listOf(
        "milf", "big-tits", "big-ass", "blowjob", "anal", "lesbian", "threesome", "latina",
        "asian", "ebony", "mature", "creampie", "pov", "jav-uncensored", "amateur", "hardcore",
        "massage", "cosplay", "interracial", "blonde", "brunette", "redhead", "big-cock", "stepmom"
    )
    private val estudiosPrincipais = listOf(
        "networks/brazzers-com", "networks/mylf-com", "networks/blacked", "networks/teamskeet-com",
        "networks/bangbros", "networks/nubiles-porn-com", "networks/adult-time", "networks/tushy-com",
        "networks/private", "networks/woodman-casting", "networks/naughtyamerica-com",
        "networks/mom-lover", "networks/rk-com", "networks/mofos-com"
    )

    private fun entradasDaHome(): Array<Pair<String, String>> {
        val fixas = listOf(
            "$mainUrl/latest-updates" to "All Videos",
            "$mainUrl/most-popular/week" to "Most Popular",
            "$mainUrl/models" to "Pornstars - Most Viewed",
            "$mainUrl/models/top-rated" to "Pornstars - Top Rated",
            "$mainUrl/sites" to "Sites"
        )

        val nomesTags = Listas.tags.mapNotNull { par(it) }.toMap()
        val nomesEstudios = Listas.estudios.mapNotNull { par(it) }.toMap()

        val tags = tagsPopulares.map { slug ->
            "$mainUrl/categories/$slug" to "Tag: ${nomesTags[slug] ?: slug.replace('-', ' ')}"
        }
        val estudios = estudiosPrincipais.map { caminho ->
            "$mainUrl/$caminho" to "Studio: ${nomesEstudios[caminho] ?: caminho.substringAfter('/').replace('-', ' ')}"
        }

        return (fixas + tags + estudios)
            .distinctBy { it.first }
            .distinctBy { it.second }
            .toTypedArray()
    }

    override val mainPage = mainPageOf(*entradasDaHome())

    // ---------------------------------------------------------------- constantes / helpers

    // Qualquer página de listagem de vídeos (atriz, categoria, canal, rede) usa div.item.
    // Nem todo div.item é vídeo (tem o cabeçalho da atriz e anúncios): toSearchResult() devolve null pra esses.
    private val seletorListagem = "div.item"

    // Páginas de listagem de entidades (atrizes, sites): /models/, /sites/ ...
    private val raizesEntidades = setOf(
        "models", "models/top-rated", "models/title",
        "sites", "sites/top-rated", "sites/total-videos", "sites/title-az", "sites/title-za"
    )
    private val regexEntidade = Regex("""/(models|sites|networks)/[^/?]+/?$""")

    // Quantas páginas de uma atriz/site/categoria viram "episódios" ao abrir o card
    private val maxPaginasColecao = 5

    private class Pagina(val itens: List<SearchResponse>, val temProxima: Boolean)

    // "slug|Nome" (formato do Listas.kt) -> Pair(slug, Nome)
    private fun par(linha: String): Pair<String, String>? {
        val p = linha.split("|", limit = 2)
        return if (p.size == 2) p[0] to p[1] else null
    }

    // Se o app pedir várias seções ao mesmo tempo, só 3 requisições vão ao site por vez
    // (evita "Software caused connection abort" por excesso de conexões simultâneas)
    private val limiteRequisicoes = Semaphore(3)
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
            // O site usa lazy-load: no HTML cru o src é um gif base64 e a imagem real fica em data-original
            val src = listOf("data-original", "data-src", "data-lazy-src", "src")
                .map { img.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            if (src != null) return fixUrlNull(src)
        }
        return null
    }

    // ---------------------------------------------------------------- cards

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            if (selectFirst("iframe") != null) return null // anúncio

            // Listagens normais: a.thumb_title. A página /sites/ (vídeos de amostra) não tem essa classe,
            // então cai pro primeiro link que aponta pra /videos/.
            val linkElement = selectFirst("a.thumb_title")
                ?: select("a[href]").firstOrNull { it.attr("href").contains("/videos/") }
                ?: return null
            val href = fixUrlNull(linkElement.attr("href")?.takeIf { it.isNotBlank() }) ?: return null
            val title = linkElement.selectFirst("strong.title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: selectFirst("div.item-info strong.title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: linkElement.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return null
            val poster = posterDe(this, "img.thumb") ?: posterDe(this, "img") ?: return null

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

    // A página /sites/ NÃO é uma grade de cards: cada site é uma seção
    //   <div class="headline"><h2>Nome</h2><a class="more" href=".../sites/slug/"><span>See All 60 Videos</span></a></div>
    //   <div class="box"> ...4 vídeos de amostra... </div>
    // O nome vem do h2, o total do "See All N Videos" e a foto é a capa do primeiro vídeo de amostra.
    private fun Document.toSitesAgrupados(): List<SearchResponse> {
        return select("a.more[href]").mapNotNull { mais ->
            try {
                val href = fixUrlNull(mais.attr("href")) ?: return@mapNotNull null
                if (!regexEntidade.containsMatchIn(href)) return@mapNotNull null

                val cabecalho = mais.parent() ?: return@mapNotNull null
                val nome = cabecalho.selectFirst("h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val total = mais.text().filter { it.isDigit() }.takeIf { it.isNotBlank() }
                val poster = cabecalho.nextElementSibling()
                    ?.let { posterDe(it, "img.thumb") ?: posterDe(it, "img") }

                newMovieSearchResponse(if (total != null) "$nome ($total)" else nome, href, TvType.NSFW) {
                    this.posterUrl = poster
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("FPV", "Error in toSitesAgrupados: ${e.message}")
                null
            }
        }.distinctBy { it.url }
    }

    // Lista de atrizes (cards a.item) ou de sites (seções); tenta uma e cai pra outra
    private fun Document.toListaEntidades(): List<SearchResponse> =
        toEntidades().ifEmpty { toSitesAgrupados() }

    private suspend fun buscaLista(url: String, seletor: String): Pagina {
        return try {
            val doc = limiteRequisicoes.withPermit { app.get(url).document }
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
        // Diagnóstico: no logcat dá pra ver se o Kototoro pede todas as seções de uma vez ou só a do chip
        Log.d("FPV", "getMainPage '${request.name}' página $page")

        val document = try {
            limiteRequisicoes.withPermit { app.get("${request.data}/$page/").document }
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

        val home = if (ehEntidade) document.toListaEntidades()
        else document.select(seletorListagem).mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = !ehEntidade || caminho.startsWith("sites") // fotos de atrizes ficam melhores na vertical; capas de sites são vídeos (horizontais)
            ),
            hasNext = home.isNotEmpty() && document.temProxima()
        )
    }

    // ---------------------------------------------------------------- busca

    // ---- Busca com filtros: "tag: milf", "studio: brazzers", "atriz: abella danger"
    private enum class TipoFiltro { TAG, ESTUDIO, ATRIZ }

    private val regexFiltro = Regex(
        """^\s*(tags?|categoria|categorias|studio|estudio|estúdio|site|rede|network|atriz|pornstar|model)\s*:\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    private fun String.normalizado(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    private fun parseFiltro(query: String): Pair<TipoFiltro, String>? {
        val m = regexFiltro.find(query) ?: return null
        val tipo = when (m.groupValues[1].lowercase()) {
            "tag", "tags", "categoria", "categorias" -> TipoFiltro.TAG
            "atriz", "pornstar", "model" -> TipoFiltro.ATRIZ
            else -> TipoFiltro.ESTUDIO
        }
        return tipo to m.groupValues[2].trim()
    }

    // Procura o termo nas listas completas (Listas.kt) e devolve até 3 caminhos ("models/x", "networks/y"...)
    private fun candidatos(tipo: TipoFiltro, termo: String): List<String> {
        val alvo = termo.normalizado()
        val base: List<Pair<String, String>> = when (tipo) {
            TipoFiltro.TAG -> Listas.tags.mapNotNull { par(it) }.map { "categories/${it.first}" to it.second }
            TipoFiltro.ESTUDIO -> Listas.estudios.mapNotNull { par(it) }
            TipoFiltro.ATRIZ -> Listas.atrizes.mapNotNull { par(it) }.map { "models/${it.first}" to it.second }
        }
        val achados = base
            .filter { it.second.normalizado().contains(alvo) }
            .sortedBy {
                val n = it.second.normalizado()
                when { n == alvo -> 0; n.startsWith(alvo) -> 1; else -> 2 }
            }
            .take(3)
            .map { it.first }
        if (achados.isNotEmpty()) return achados

        // Não está nas listas: tenta o texto como slug direto no site
        val slug = termo.createSlug() ?: return emptyList()
        return when (tipo) {
            TipoFiltro.TAG -> listOf("categories/$slug")
            TipoFiltro.ESTUDIO -> listOf("networks/$slug", "sites/$slug")
            TipoFiltro.ATRIZ -> listOf("models/$slug")
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val pagina = if (page < 1) 1 else page

        parseFiltro(query)?.let { (tipo, termo) ->
            val paginas = candidatos(tipo, termo).amap { caminho ->
                buscaLista("$mainUrl/$caminho/$pagina/", seletorListagem)
            }
            return newSearchResponseList(
                paginas.flatMap { it.itens }.distinctBy { it.url },
                hasNext = paginas.any { it.temProxima }
            )
        }

        val searchquery = query.createSlug() ?: ""

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
