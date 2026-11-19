package com.fpvplus.allpornstream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class AllPornStream : MainAPI() {
    override var mainUrl        = "https://allpornstream.com"
    override var name           = "All Porn Stream+"
    override val hasMainPage    = true
    override var lang           = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    // Cada entrada vira um chip E uma seção da home no Kototoro: manter a lista CURTA.
    // O resto (atrizes, estúdios, outras tags) fica acessível pela busca: "atriz: nome", "tag: nome".
    private val tagsPopulares = listOf(
        "european", "tit-worship", "straight-hair", "anal", "twosome-straight", "onlyfans", "living-room"
    )

    override val mainPage = mainPageOf(
        *(
            listOf("$mainUrl/posts" to "Latest") +
                tagsPopulares.map { slug ->
                    "$mainUrl/categories/$slug" to "Tag: " + slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
                }
            ).toTypedArray()
    )

    // ---------------------------------------------------------------- helpers

    private class Pagina(val itens: List<SearchResponse>, val temProxima: Boolean)
    private class Fontes(val hls: List<Pair<Int, String>>, val iframes: List<Pair<String, String>>)
    private class Post(
        val titulo: String?,
        val descricao: String?,
        val duracaoSeg: Int?,
        val data: String?,
        val categorias: List<String>,
        val atores: List<String>,
        val estudio: String?,
        val fontes: Fontes
    )

    // true = mostra o tempo de carregamento no começo da sinopse (teste de lentidão)
    private val diagnostico = true

    // Só 3 requisições por vez (evita "connection abort" quando o Kototoro pede várias seções juntas)
    private val limiteRequisicoes = Semaphore(3)

    // Fontes lidas no load() para o loadLinks não baixar a página pesada (~1,8 MB) de novo (5 min)
    private val cacheFontes = ConcurrentHashMap<String, Pair<Long, Fontes>>()
    private val validadeCacheMs = 5 * 60 * 1000L

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun String?.createSlug(): String? {
        return this?.let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }
            ?.filter { it.isWhitespace() || it.isLetterOrDigit() }
            ?.trim()
            ?.replace("\\s+".toRegex(), "-")
            ?.lowercase()
    }

    // Página 1 sem "page" (a forma mais segura); páginas seguintes com page=N
    private fun paginaUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base + (if (base.contains("?")) "&" else "?") + "page=$page"
    }

    // Existe o link "próxima página" na paginação?
    private fun Document.temProxima(): Boolean =
        selectFirst("a[aria-label=\"Go to next page\"]") != null

    // As imagens passam por /api/images?src=<URL ORIGINAL CODIFICADA>: usa a URL original
    private fun urlOriginal(src: String): String? {
        if (src.isBlank() || src.startsWith("data:")) return null
        if (src.contains("/api/images")) {
            val m = Regex("[?&]src=([^&]+)").find(src)
            if (m != null) {
                return try {
                    fixUrlNull(URLDecoder.decode(m.groupValues[1], "UTF-8"))
                } catch (e: Exception) {
                    fixUrlNull(src)
                }
            }
        }
        return fixUrlNull(src)
    }

    private fun imagemDe(img: Element): String? {
        val candidatos = listOf(
            img.attr("src"),
            img.attr("data-src"),
            img.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ").orEmpty()
        )
        for (c in candidatos) {
            val u = urlOriginal(c)
            if (u != null) return u
        }
        return null
    }

    // ---------------------------------------------------------------- título: "Site - Atores - Título"

    private val regexEstudioNoTitulo = Regex("""^\s*\[[^\]]*]\s*""")
    private val regexDataNoFim = Regex("""\s*/\s*(?:\d{1,2}\.\d{1,2}\.\d{4}|\d{4}-\d{2}-\d{2})\s*\)?\s*$""")
    private val regexAtoresETitulo = Regex("""^([^(]*?)\s*\((.+)$""")

    // Formato comum: "[Estúdio] Ator1, Ator2 (Título / data)". Aceita títulos fora do padrão sem quebrar.
    private fun tituloLimpo(original: String): String {
        val temEstudio = regexEstudioNoTitulo.containsMatchIn(original)
        var s = original.replace(regexEstudioNoTitulo, "").replace(regexDataNoFim, "").trim()
        if (temEstudio) {
            val m = regexAtoresETitulo.find(s)
            if (m != null && m.groupValues[1].isNotBlank()) {
                var t = m.groupValues[2].trim()
                if (t.count { it == ')' } > t.count { it == '(' }) t = t.removeSuffix(")").trim()
                if (t.isNotBlank()) s = t
            }
        }
        return s.ifBlank { original.trim() }
    }

    private fun montaTitulo(original: String, estudio: String?, atores: List<String>): String {
        val partes = listOfNotNull(estudio?.trim()) + atores + listOf(tituloLimpo(original))
        return partes.filter { it.isNotBlank() }.joinToString(" - ").ifBlank { original }
    }

    // ---------------------------------------------------------------- cards

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val caminho = attr("data-href").ifBlank { selectFirst("a[href^=/post/]")?.attr("href").orEmpty() }
            if (caminho.isBlank()) return null
            val href = fixUrlNull(caminho) ?: return null

            val original = attr("data-title").ifBlank { selectFirst("h2")?.text().orEmpty() }.trim()
            if (original.isBlank()) return null

            // Estúdio e atores pelos links do card (o nome entre colchetes pode diferir do nome do link)
            val estudio = selectFirst("a[data-ga-category=thumbnail_studio]")?.let { a ->
                a.attr("aria-label").removePrefix("producer:").trim().ifBlank { a.text().trim() }
            }?.takeIf { it.isNotBlank() }

            // Cada ator aparece 2x (círculo com iniciais + nome): agrupa pelo link e fica com o texto mais longo
            val atores = select("a[data-ga-category=thumbnail_actor]")
                .groupBy { it.attr("href") }
                .mapNotNull { (_, links) ->
                    links.map { it.text().trim() }.maxByOrNull { it.length }?.takeIf { it.isNotBlank() }
                }

            val img = selectFirst("[data-poster] img") ?: selectFirst("img")
            val poster = img?.let { imagemDe(it) } ?: return null

            return newMovieSearchResponse(montaTitulo(original, estudio, atores), href, TvType.NSFW) {
                this.posterUrl = poster
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("APS", "Erro em toSearchResult: ${e.message}")
            return null
        }
    }

    private suspend fun buscaLista(url: String): Pagina {
        return try {
            val doc = limiteRequisicoes.withPermit { app.get(url).document }
            val itens = doc.select("div[data-thumb-id]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            Pagina(itens, doc.temProxima())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("APS", "Erro ao abrir $url: ${e.message}")
            Pagina(emptyList(), false)
        }
    }

    // ---------------------------------------------------------------- home

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = paginaUrl(request.data, page)
        val pagina = try {
            val doc = limiteRequisicoes.withPermit { app.get(url).document }
            Pagina(doc.select("div[data-thumb-id]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }, doc.temProxima())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Na página 1 o erro aparece pro usuário; depois dela é só "acabou a lista"
            if (page <= 1) throw e
            Log.d("APS", "Fim da lista em ${request.data} (página $page): ${e.message}")
            Pagina(emptyList(), false)
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = pagina.itens, isHorizontalImages = true),
            hasNext = pagina.itens.isNotEmpty() && pagina.temProxima
        )
    }

    // ---------------------------------------------------------------- busca

    private val regexFiltro = Regex(
        """^\s*(tags?|categoria|categorias|category|atriz|actress|actor|pornstar|model)\s*:\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val palavrasAtriz = setOf("atriz", "actress", "actor", "pornstar", "model")

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val pagina = if (page < 1) 1 else page

        // "atriz: nome" -> /actors/<slug>   |   "tag: nome" -> /categories/<slug> (plano B: /posts?category=Nome)
        val m = regexFiltro.find(query)
        if (m != null) {
            val ehAtriz = m.groupValues[1].lowercase() in palavrasAtriz
            val termo = m.groupValues[2].trim()
            val slug = termo.createSlug().orEmpty()
            if (slug.isNotBlank()) {
                val base = if (ehAtriz) "$mainUrl/actors/$slug" else "$mainUrl/categories/$slug"
                var r = buscaLista(paginaUrl(base, pagina))
                if (r.itens.isEmpty() && !ehAtriz) {
                    r = buscaLista(paginaUrl("$mainUrl/posts?category=${enc(termo)}", pagina))
                }
                return newSearchResponseList(r.itens, hasNext = r.temProxima)
            }
        }

        val r = buscaLista(paginaUrl("$mainUrl/posts?search=${enc(query.trim())}", pagina))
        return newSearchResponseList(r.itens, hasNext = r.temProxima)
    }

    // ---------------------------------------------------------------- leitura do JSON embutido (Next.js)

    private val regexPush = Regex("self\\.__next_f\\.push\\(\\[1,\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"\\]\\)")
    private val regexEscape = Regex("""\\(["\\/nrtbf]|u[0-9a-fA-F]{4})""")
    private val regexTexto = Regex("\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"")
    private val regexNome = Regex("\"item_name\":\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"")
    private val regexIframe = Regex("\"embed_url\":\"([^\"]+)\",\"file_code\":\"[^\"]*\",\"hosting_provider\":\"([^\"]*)\",\"status_code\":(\\d+)")
    private val regexHls = Regex("\\[\\s*\"?(\\d+)\"?\\s*,\\s*\"([^\"]+)\"\\s*]")

    // Desfaz as barras de um texto JS/JSON: \" -> "  \\ -> \  \n -> quebra de linha  \uXXXX -> caractere
    private fun String.desescapa(): String = regexEscape.replace(this) { m ->
        val g = m.groupValues[1]
        when {
            g.startsWith("u") -> g.substring(1).toInt(16).toChar().toString()
            g == "n" -> "\n"
            g == "r" -> "\r"
            g == "t" -> "\t"
            g == "b" -> "\b"
            g == "f" -> "\u000C"
            else -> g
        }
    }

    // Junta todos os pedaços self.__next_f.push([1,"..."]) num texto só (um objeto pode vir dividido em vários)
    // Otimização: só desescapa a partir do pedaço que tem "initialPost" (a página tem ~1,8 MB, quase tudo é
    // dado de outros vídeos) e para depois de ~300 KB.
    private fun juntaFluxo(html: String): String {
        val sb = StringBuilder()
        var achou = false
        for (m in regexPush.findAll(html)) {
            val bruto = m.groupValues[1]
            if (!achou) {
                if (!bruto.contains("initialPost")) continue
                achou = true
            }
            sb.append(bruto.desescapa())
            if (sb.length > 300_000) break
        }
        if (sb.isNotEmpty()) return sb.toString()

        // Reserva: método completo
        val partes = regexPush.findAll(html).map { it.groupValues[1].desescapa() }.toList()
        return if (partes.isEmpty()) html.replace("\\\"", "\"") else partes.joinToString("")
    }

    // Tira os <script> do HTML antes do Jsoup (eles são quase toda a página e não servem pra ler o resto)
    private fun semScripts(html: String): String {
        val sb = StringBuilder(html.length / 4)
        var i = 0
        while (true) {
            val a = html.indexOf("<script", i)
            if (a < 0) {
                sb.append(html, i, html.length)
                break
            }
            sb.append(html, i, a)
            val b = html.indexOf("</script>", a)
            if (b < 0) break
            i = b + 9
        }
        return sb.toString()
    }

    private fun String.campoTexto(chave: String): String? =
        Regex("\"" + Regex.escape(chave) + "\":\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"")
            .find(this)?.groupValues?.get(1)?.desescapa()

    private fun String.campoNumero(chave: String): Int? =
        Regex("\"" + Regex.escape(chave) + "\":(\\d+)").find(this)?.groupValues?.get(1)?.toIntOrNull()

    // Pega o bloco {...} ou [...] que vem logo depois de "chave": (respeitando aspas e barras)
    private fun String.blocoApos(chave: String, abre: Char, fecha: Char): String? {
        val i = indexOf("\"$chave\":")
        if (i < 0) return null
        val depois = i + chave.length + 3
        val ini = indexOf(abre, depois)
        if (ini < 0 || substring(depois, ini).isNotBlank()) return null
        var prof = 0
        var dentro = false
        var esc = false
        for (k in ini until length) {
            val c = this[k]
            if (dentro) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') dentro = false
            } else if (c == '"') {
                dentro = true
            } else if (c == abre) {
                prof++
            } else if (c == fecha) {
                prof--
                if (prof == 0) return substring(ini, k + 1)
            }
        }
        return null
    }

    private fun lePost(html: String): Post {
        val fluxo = juntaFluxo(html)
        val i = fluxo.indexOf("\"initialPost\"")
        val seg = if (i >= 0) fluxo.substring(i) else fluxo

        val categorias = seg.blocoApos("categories", '[', ']')
            ?.let { b -> regexTexto.findAll(b).map { it.groupValues[1].desescapa() }.toList() }.orEmpty()
        val atores = seg.blocoApos("actors", '[', ']')
            ?.let { b -> regexNome.findAll(b).map { it.groupValues[1].desescapa() }.toList() }.orEmpty()
        val estudio = seg.blocoApos("producers", '[', ']')
            ?.let { b -> regexNome.find(b)?.groupValues?.get(1)?.desescapa() }

        // Players: HLS direto (lista de [qualidade, url]) + iframes (só status 200, sem repetir)
        val urls = seg.blocoApos("video_urls", '{', '}') ?: seg
        val hls = urls.blocoApos("hls", '[', ']')
            ?.let { b -> regexHls.findAll(b).map { it.groupValues[1].toInt() to it.groupValues[2].desescapa() }.toList() }
            .orEmpty().distinctBy { it.second }
        val iframes = regexIframe.findAll(urls)
            .filter { it.groupValues[3] == "200" }
            .map { it.groupValues[1].desescapa() to it.groupValues[2] }
            .distinctBy { it.first }
            .toList()

        return Post(
            titulo = seg.campoTexto("video_title"),
            descricao = seg.campoTexto("video_description")?.trim()?.takeIf { it.isNotBlank() },
            duracaoSeg = seg.campoNumero("video_duration"),
            data = seg.campoTexto("item_publish_date"),
            categorias = categorias,
            atores = atores.distinct(),
            estudio = estudio?.takeIf { it.isNotBlank() },
            fontes = Fontes(hls, iframes)
        )
    }

    // ---------------------------------------------------------------- load

    private fun capitaliza(s: String): String =
        s.trim().split(" ").filter { it.isNotBlank() }.joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }

    override suspend fun load(url: String): LoadResponse {
        val t0 = System.currentTimeMillis()
        val html = app.get(url).text
        val t1 = System.currentTimeMillis()
        val document = Jsoup.parse(semScripts(html), url)
        val post = lePost(html)
        val t2 = System.currentTimeMillis()
        // DIAGNÓSTICO TEMPORÁRIO: aparece no começo da sinopse. Quando terminar o teste, trocar por false.
        val diag = if (diagnostico) "Tempo: baixar a página ${t1 - t0} ms (${html.length / 1024} KB) | ler os dados ${t2 - t1} ms" else null

        if (cacheFontes.size > 30) cacheFontes.clear()
        cacheFontes[url] = System.currentTimeMillis() to post.fontes

        val tituloOriginal = post.titulo?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Sem título"
        val title = montaTitulo(tituloOriginal, post.estudio, post.atores)

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { urlOriginal(it) }

        // O Kototoro só mostra "tags": atores + estúdio + categorias (limitadas, pois podem ser ~70)
        val tags = (post.atores + listOfNotNull(post.estudio) + post.categorias.take(15).map { capitaliza(it) })
            .filter { it.isNotBlank() }.distinct()

        val data = post.data?.take(10)?.takeIf { it.length == 10 }
        val year = data?.take(4)?.toIntOrNull()
        val plot = listOfNotNull(
            diag,
            data?.let { "Published: $it" },
            post.descricao
        ).joinToString("\n\n").ifBlank { null }

        // Duração em minutos; se vier null, tenta o JSON-LD (PT27M8S)
        var segundos = post.duracaoSeg
        if (segundos == null) {
            val md = Regex("\"duration\"\\s*:\\s*\"PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?\"").find(html)
            if (md != null) {
                segundos = (md.groupValues[1].toIntOrNull() ?: 0) * 3600 +
                    (md.groupValues[2].toIntOrNull() ?: 0) * 60 +
                    (md.groupValues[3].toIntOrNull() ?: 0)
            }
        }
        val minutos = segundos?.takeIf { it > 0 }?.let { (it + 30) / 60 }

        // "Related Videos": acha o título da seção e sobe até o bloco que tem cards
        val relacionados = try {
            val titulo = document.select("h2, h3").firstOrNull { it.text().contains("Related Videos", ignoreCase = true) }
            var bloco: Element? = titulo
            var n = 0
            while (bloco != null && n < 6 && bloco.select("div[data-thumb-id]").isEmpty()) {
                bloco = bloco.parent()
                n++
            }
            (bloco?.select("div[data-thumb-id]") ?: document.select("div[data-thumb-id]"))
                .mapNotNull { it.toSearchResult() }
                .filter { it.url != url }
                .distinctBy { it.url }
                .take(12)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = relacionados
            if (minutos != null) this.duration = minutos
            addActors(post.atores)
        }
    }

    // ---------------------------------------------------------------- links

    // Espelhos conhecidos que o CloudStream talvez não reconheça [NÃO TESTADO]: reescreve pro domínio principal
    private fun espelhoPrincipal(u: String): String? {
        val host = Regex("""https?://([^/]+)/""").find(u)?.groupValues?.get(1) ?: return null
        val novo = when {
            host.contains("mxdrop") || (host.contains("mixdrop") && host != "mixdrop.ag") -> "mixdrop.ag"
            host.contains("dsvplay") -> "doodstream.com"
            else -> return null
        }
        return u.replace(host, novo).takeIf { it != u }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val agora = System.currentTimeMillis()
        val fontes = cacheFontes[data]?.takeIf { agora - it.first < validadeCacheMs }?.second
            ?: lePost(app.get(data).text).fontes

        var achou = false
        val entrega: (ExtractorLink) -> Unit = {
            achou = true
            callback(it)
        }

        // 1) HLS direto (melhor opção, sem extrator). Referer = página do post [precisa conferir no app]
        for ((qualidade, url) in fontes.hls) {
            entrega(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = qualidade
                }
            )
        }

        // 2) Players por iframe (já filtrados: só status 200, sem repetir)
        for ((embed, provedor) in fontes.iframes) {
            try {
                var ok = loadExtractor(embed, data, subtitleCallback, entrega)
                if (!ok) {
                    val espelho = espelhoPrincipal(embed)
                    if (espelho != null) ok = loadExtractor(espelho, data, subtitleCallback, entrega)
                }
                if (!ok) Log.d("APS", "Nenhum extrator para $provedor: $embed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("APS", "Falha em $provedor: ${e.message}")
            }
        }

        return achou
    }
}
