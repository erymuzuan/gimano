# Gimano design tokens: "Cobalt"

Shared by Android (`ui/theme/GimanoTokens.kt`) and iOS (`DesignSystem/Tokens.swift`).
Chosen option: **D · Cobalt**. Cobalt-blue brand, pale-cobalt surfaces, rounded
**duotone** icons, a coral report button, and Nunito type.

Guiding rule: **big icons, little text.** Glanceable while driving. Every icon
button carries an accessibility label even when no text is shown.

## Colour

| Token | Light | Dark | Use |
|---|---|---|---|
| `brand` | `#0047AB` | `#5B8DEF` | primary buttons, own-location puck, ETA clock accent |
| `brandOn` | `#FFFFFF` | `#071430` | text/icons on brand |
| `brandInk` | `#0A2E6E` | `#BFD3F7` | icon strokes (duotone outline) |
| `brandTint` | `#8FB0E8` | `#3A5A99` | icon fills (duotone fill, 60 % opacity) |
| `brandMist` | `#E3ECFA` | `#16233D` | icon tiles, selected chips |
| `report` | `#FF6B4A` | `#FF8266` | report FAB, report accents |
| `reportOn` | `#FFFFFF` | `#2A0A03` | |
| `reportTint` | `#FFD3C7` | `#7A2E1C` | report icon fill |
| `background` | `#F5F8FD` | `#0B1220` | screen background |
| `surface` | `#FFFFFF` | `#121B2E` | sheets, cards, FABs |
| `surfaceAlt` | `#EEF3FC` | `#1A2540` | search field, secondary buttons |
| `outline` | `#C9D6EE` | `#2A3A5C` | sheet handle, dividers |
| `text` | `#0B1B3A` | `#EEF3FC` | primary text |
| `textMuted` | `#3A4A66` | `#A9B6CF` | labels, addresses |
| `navBanner` | `#0A1F4D` | `#0A1F4D` | turn-by-turn banner (deep cobalt) |
| `navBannerOn` | `#FFFFFF` | `#FFFFFF` | |
| `routeActive` | `#0047AB` | `#5B8DEF` | selected route line |
| `routeAlt` | `#A9C2EE` | `#2F4777` | alternative routes |
| `traffic1` | `#F5C518` | same | light slowdown |
| `traffic2` | `#FFB020` | same | moderate |
| `traffic3` | `#E5484D` | same | heavy |
| `traffic4` | `#7F1D1D` | same | standstill |
| `speeding` | `#E5484D` | same | speedometer above limit |
| `mapBackground` | `#EEF2F8` | `#0E1626` | map style land colour |

### Report-type tiles (duotone: tile / fill / stroke)

| Type | Tile | Fill | Stroke |
|---|---|---|---|
| traffic (Jam) | `#FFEBD6` | `#FFB870` | `#8A4300` |
| police | `#E3ECFA` | `#8FB0E8` | `#0A2E6E` |
| accident (Crash) | `#FFE2E2` | `#F5A3A3` | `#8B1A1A` |
| hazard | `#FFF1CC` | `#FFD466` | `#7A5200` |
| closure (Closed) | `#ECEEF1` | `#C3C8D0` | `#2F3640` |
| blocked_lane (Lane) | `#EEE6FF` | `#C4B1F7` | `#4C2A9E` |
| weather / flood | `#DDF4F2` | `#8EDCD3` | `#0E5A54` |
| speed camera | `#E3ECFA` | `#8FB0E8` | `#0A2E6E` |
| map_issue (Map) | `#E3ECFA` | `#8FB0E8` | `#0A2E6E` |
| animal | `#E8F5D6` | `#B7DC7F` | `#3F6212` |

## Iconography: rounded duotone

- 24-unit grid, **2.0 stroke** (1.9 at 60 px+), round caps and joins, closed shapes
  filled with the matching tint at **60 % opacity**.
- Gimano's own icon set (shared SVG path data in
  `docs/icons/gimano-icons.json`): home, work, fuel, food, surau, parking, ev,
  toll, recent, police, accident, hazard, traffic, closure, lane, flood, camera,
  map, search, mic, sound, layers, menu, report, close.
  Android: ship as VectorDrawables. iOS: ship as an asset catalog of SVG symbols
  or draw with `Path`. Don't substitute Material Symbols or SF Symbols for these.

## Sizes (dp / pt)

| Element | Size | Icon | Radius |
|---|---|---|---|
| Report FAB | 84 | 46 | 28 |
| Map FABs (menu, sound, layers) | 64 | 30 | menu 22, others round |
| Search field | 64 high | 30 | 22 |
| Home shortcut tile (4-col grid) | 76 | 42 | 24 |
| Report-type tile (3-col grid) | 104 | 60 | 32 |
| Bottom sheet | top radius 28, handle 40×5 | | |
| Speedometer | 72 round | | |

Labels under tiles: **one word**, Nunito ExtraBold 13–15. No sheet titles such as
"What do you see?". The icons carry the meaning.

## Type

**Nunito** (SIL OFL, bundled in both apps).
`navDistance 44/black`, `etaClock 34/extrabold`, `title 22/extrabold`,
`body 17/semibold`, `label 13–15/extrabold`, `caption 14/semibold`.

## Spacing

4/8/12/16/24/32, screen gutter 16, grid gaps 10–14.
Shadows: FAB `0 3 10 rgba(11,27,58,.16)`, report FAB `0 6 16 rgba(255,107,74,.45)`.
