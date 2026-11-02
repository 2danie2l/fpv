# FPV+

Only for users aged 18 or older.

CloudStream / Kototoro provider for freepornvideos.xxx.

## Instalação

Adicione este repositório no app:

`https://raw.githubusercontent.com/2danie2l/fpv/refs/heads/main/repo.json`

## Recursos

- Home com ordenações, atrizes (Pornstars), sites, redes e categorias populares
- Busca por título, atriz, categoria, canal ou rede (qualquer categoria funciona pela busca)
- Abrir atriz/site/rede lista os vídeos (primeiras 5 páginas)
- Todas as qualidades disponíveis (2160p/720p/480p...)

Baseado na extensão FreePornVideos de @kerimmkirac.

## Atualizar as listas de filtro (tags, estúdios, atrizes)

Na raiz do repositório, com Python 3 instalado:

```
python tools/gerar_listas.py --atrizes 400 --estudios 300
```

Isso reescreve `Listas.kt`. Depois é só commit + push.
