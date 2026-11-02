# FPV+

Only for users aged 18 or older.

CloudStream / Kototoro provider for freepornvideos.xxx.

## Instalação

Adicione este repositório no app:

`https://raw.githubusercontent.com/2danie2l/fpv/refs/heads/main/repo.json`

## Recursos

- Chips de filtro: tags populares (`Tag: ...`) e principais redes (`Studio: ...`), mais as listas Pornstars e Sites
- Busca com filtros (digite na busca):
  - `tag: milf`
  - `studio: brazzers`
  - `atriz: abella danger`
  - Aceita nome parcial (procura nas listas do `Listas.kt`)
- Busca normal por título, atriz, categoria, canal ou rede
- Abrir atriz/site/rede lista os vídeos (primeiras 5 páginas)
- Todas as qualidades disponíveis (2160p/720p/480p...)

Baseado na extensão FreePornVideos de @kerimmkirac.

## Atualizar as listas usadas na busca com filtros (tags, estúdios, atrizes)

Na raiz do repositório, com Python 3 instalado:

```
python tools/gerar_listas.py --atrizes 400 --estudios 300
```

Isso reescreve `Listas.kt`. Depois é só commit + push.
