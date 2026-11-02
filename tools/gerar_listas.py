#!/usr/bin/env python3
"""
Gera FreePornVideos/src/main/kotlin/com/kerimmkirac/Listas.kt com as listas de
tags, estúdios (sites/redes) e atrizes que viram os chips de filtro do Kototoro.

Uso (na raiz do repositório, só precisa de Python 3, sem instalar nada):
    python tools/gerar_listas.py
    python tools/gerar_listas.py --atrizes 500 --estudios 400

Ele pede as páginas ao site devagar (pausa entre cada uma). Rode de vez em quando
pra atualizar. Depois é só commitar o Listas.kt e fazer push.
"""
import argparse
import datetime
import html
import os
import re
import sys
import time
import urllib.error
import urllib.request

BASE = "https://www.freepornvideos.xxx"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
DESTINO = os.path.join("FreePornVideos", "src", "main", "kotlin",
                       "com", "kerimmkirac", "Listas.kt")
IGNORAR_SLUGS = {"top-rated", "title", "total-videos", "most-popular",
                 "latest-updates", "most-viewed", "title-az", "title-za"}
# Página /sites/: cada site é uma seção <div class="headline"><h2>Nome</h2><a class="more" href=".../sites/slug/">
SECAO_RE = re.compile(
    r'<h[23][^>]*>([^<]*)</h[23]>\s*<a\b[^>]*class="[^"]*\bmore\b[^"]*"[^>]*href="([^"]+)"', re.S | re.I)
NOME_LIXO = re.compile(r"^(see all|view all|more|all videos)\b", re.I)
A_RE = re.compile(r"<a\b([^>]*)>(.*?)</a>", re.S | re.I)


def baixar(url, tentativas=3):
    for i in range(tentativas):
        try:
            req = urllib.request.Request(
                url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", "ignore")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            print(f"  HTTP {e.code} em {url} (tentativa {i + 1})", file=sys.stderr)
        except Exception as e:  # rede, timeout...
            print(f"  erro em {url}: {e} (tentativa {i + 1})", file=sys.stderr)
        time.sleep(3 * (i + 1))
    return None


def _atributo(attrs, nome):
    m = re.search(r'\b' + nome + r'\s*=\s*"([^"]*)"', attrs)
    return html.unescape(m.group(1)).strip() if m else ""


def _texto(fragmento):
    t = html.unescape(re.sub(r"<[^>]+>", " ", fragmento))
    return re.sub(r"\s+", " ", t).strip()


def extrair(pagina, tipos):
    """Retorna lista de (caminho, nome), ex.: ('models/abella-danger', 'Abella Danger')."""
    regex = re.compile(r"^https?://[^/]+/(%s)/([^/?#]+)/?$" % "|".join(tipos))
    achados = {}
    # 1) Seções da página /sites/ (o nome verdadeiro está no h2, o link "See All N Videos" não serve de nome)
    for h2, href in SECAO_RE.findall(pagina):
        m = regex.match(html.unescape(href).strip())
        if not m:
            continue
        tipo, slug = m.groups()
        nome = _texto(h2).replace("|", "/").strip()
        if nome and slug not in IGNORAR_SLUGS:
            achados[f"{tipo}/{slug}"] = nome
    # 2) Demais links (cards de atrizes, barra lateral, lista de categorias...)
    for attrs, interno in A_RE.findall(pagina):
        m = regex.match(_atributo(attrs, "href"))
        if not m:
            continue
        tipo, slug = m.groups()
        # slug só de números = link de paginação (/models/2/), exceto em categorias (/categories/69/)
        if (slug.isdigit() and tipo != "categories") or slug in IGNORAR_SLUGS:
            continue
        nome = _atributo(attrs, "title")
        if not nome:
            t = re.search(r'<strong[^>]*class="title"[^>]*>(.*?)</strong>', interno, re.S)
            nome = _texto(t.group(1)) if t else ""
        if not nome:
            nome = _texto(interno)
        if not nome:
            alt = re.search(r'alt="([^"]*)"', interno)
            nome = html.unescape(alt.group(1)).strip() if alt else ""
        nome = re.sub(r"\s*\(\d+\)\s*$", "", nome).replace("|", "/").strip()
        if not nome or NOME_LIXO.match(nome):
            continue
        chave = f"{tipo}/{slug}"
        if chave not in achados:  # o nome vindo do h2 da seção tem prioridade
            achados[chave] = nome
    return list(achados.items())


def coletar(caminho, tipos, limite, pausa, max_paginas=400):
    """Percorre /caminho/, /caminho/2/, ... até juntar `limite` itens ou as páginas acabarem."""
    total = {}
    for n in range(1, max_paginas + 1):
        url = f"{BASE}/{caminho}/" if n == 1 else f"{BASE}/{caminho}/{n}/"
        pagina = baixar(url)
        if pagina is None:
            break
        antes = len(total)
        for chave, nome in extrair(pagina, tipos):
            total.setdefault(chave, nome)
        print(f"  {caminho} página {n}: {len(total)} itens")
        if len(total) == antes or len(total) >= limite:
            break
        time.sleep(pausa)
    return list(total.items())[:limite]


def kt(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def gerar_kotlin(tags, estudios, atrizes):
    def bloco(itens, remover_prefixo=None):
        linhas = []
        for chave, nome in itens:
            if remover_prefixo:
                chave = chave.split("/", 1)[1] if chave.startswith(remover_prefixo) else chave
            linhas.append(f'        "{kt(chave)}|{kt(nome)}"')
        return ",\n".join(linhas)

    data = datetime.date.today().isoformat()
    return f'''// ARQUIVO GERADO por tools/gerar_listas.py em {data} - não edite na mão.
// Cada linha é "caminho|Nome". Viram os chips de filtro do Kototoro.

package com.kerimmkirac

object Listas {{
    // /categories/<slug>
    val tags = listOf(
{bloco(tags, "categories/")}
    )

    // /sites/<slug> ou /networks/<slug>
    val estudios = listOf(
{bloco(estudios)}
    )

    // /models/<slug>
    val atrizes = listOf(
{bloco(atrizes, "models/")}
    )
}}
'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--atrizes", type=int, default=300, help="quantas atrizes (mais vistas) incluir")
    ap.add_argument("--estudios", type=int, default=300, help="quantos sites/redes incluir")
    ap.add_argument("--pausa", type=float, default=1.5, help="segundos entre páginas")
    ap.add_argument("--saida", default=DESTINO)
    args = ap.parse_args()

    print("Categorias...")
    tags = coletar("categories", ("categories",), 10_000, args.pausa, max_paginas=1)
    tags.sort(key=lambda x: x[1].lower())

    print("Estúdios (redes e sites)...")
    estudios = {}
    for caminho in ("networks", "sites"):
        for chave, nome in coletar(caminho, ("networks", "sites"), args.estudios, args.pausa):
            estudios.setdefault(chave, nome)
    estudios = list(estudios.items())[:args.estudios]

    print("Atrizes (ordem: mais vistas)...")
    atrizes = coletar("models", ("models",), args.atrizes, args.pausa)

    if not tags:
        sys.exit("Não consegui baixar as categorias. Confira sua internet / se o site abre no navegador.")

    os.makedirs(os.path.dirname(args.saida), exist_ok=True)
    with open(args.saida, "w", encoding="utf-8") as f:
        f.write(gerar_kotlin(tags, estudios, atrizes))
    print(f"\nPronto! {len(tags)} tags, {len(estudios)} estúdios, {len(atrizes)} atrizes -> {args.saida}")
    print("Agora faça commit e push do Listas.kt.")


if __name__ == "__main__":
    main()
