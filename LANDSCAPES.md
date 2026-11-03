# Landscapes

The built-in backgrounds under Settings → Background → Landscapes.

## Drawn at build time

`tools/MakeLandscapes.java` draws four scenes (ridges at dusk, a mountain lake, a misty forest,
dunes) into `assets/landscape_drawn_*.jpg` on every build, from fixed seeds, so they live in
source rather than as binaries. They are not committed (`.gitignore`).

## Photos

Four CC0 photos from Wikimedia Commons, committed in their 1920 px versions. CC0 needs no
attribution; the sources are listed here so anyone can check them. Licence and author read from
the Commons file pages on 2026-11-03.

| File | Source | Author | Licence |
|---|---|---|---|
| `assets/landscape_photo_ciucas.jpg` | [Alone in the unspoilt wilderness (Unsplash)](https://commons.wikimedia.org/wiki/File:Alone_in_the_unspoilt_wilderness_(Unsplash).jpg) | David Marcu, via Unsplash | CC0 |
| `assets/landscape_photo_crete.jpg` | [Mount Ida chain, Messara plain from Phaistos, Crete](https://commons.wikimedia.org/wiki/File:Mount_Ida_chain_Messara_plain_from_Phaistos_Crete_Greece.jpg) | Jebulon | CC0 |
| `assets/landscape_photo_sanjuan.jpg` | [San Juan Valley](https://commons.wikimedia.org/wiki/File:San_Juan_Valley.jpg) | Wilfredor | CC0 |
| `assets/landscape_photo_scuol.jpg` | [Scuol-Motta Naluns, 15-09-2023 (actm.) 09](https://commons.wikimedia.org/wiki/File:Scuol-Motta_Naluns,_15-09-2023._(actm.)_09.jpg) | Agnes Monkelbaan | CC0 |

The names shown in the app are the `ls_*` strings in `res/values*/strings.xml`.
