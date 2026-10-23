# Manotu brand masters

**Manotu** (formerly Gimano) is from the user's local dialect *"Ma'no tu?"*, literally "Where are you going?".
It's pronounced **MA**-no-tu, three syllables, with "Ma" slightly stressed.

Chosen 2026-10-23 on the design canvas: **icon 6, "Wau ringkas"** + **wordmark E, "Lengkung suara"**.

| File | Use |
|---|---|
| `manotu-icon.svg` | App icon with rounded tile (web, docs, store artwork) |
| `manotu-icon-square.svg`, `manotu-icon-ios-1024.png` | iOS App Store / app icon (square, no transparency; iOS applies the mask) |
| `manotu-icon-android-foreground.svg` | Android adaptive icon foreground (108 dp canvas, art inside the 66 dp safe zone); background `#0047AB` |
| `manotu-icon-mono.svg` | Android 13+ themed (monochrome) icon |
| `manotu-wordmark.svg` / `manotu-wordmark-white.svg` | Wordmark on light / on cobalt or dark backgrounds |
| `manotu-lockup.svg` | Icon + wordmark, horizontal |
| `make-brand.py` | Regenerates every SVG (fontTools + Nunito Black, OFL). Glyphs are outlined, so no font is needed to render |

## The marks

- **Icon (Wau ringkas):** the wau kite reduced to a heading chevron, a lighter second chevron (the wings), the moon
  crescent (*wau bulan*) and a coral centre dot (you, the current position).
- **Wordmark (Lengkung suara):** "ma" in cobalt under a coral intonation arc, because the voice rises on "Ma". The
  "o" is a destination ring with a coral dot. "n" and "tu" are in ink.

## Colours (unchanged Cobalt system, see `docs/design-tokens.md`)

Cobalt `#0047AB` · pale `#E3ECFA` · mid `#8FB0E8` · deep `#0A1F4D` · coral `#FF6B4A` (accent and actions only) · ink `#0B1B3A`.

## Don'ts

- Don't recolour the coral dot or arc, and don't add a third accent colour.
- Don't set the wordmark in live text with another font. Use the outlined SVG.
- Keep clear space of at least the height of the "o" around the wordmark. Don't use the icon below 16 px.
