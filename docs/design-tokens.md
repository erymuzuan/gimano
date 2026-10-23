# Gimano design tokens

Shared by Android (`ui/theme/GimanoTokens.kt`) and iOS (`DesignSystem/Tokens.swift`).
Gimano's look is its own: a jungle-green brand, a coral report button and
rounded "pebble" shapes. Familiar *layout*, distinct *identity*.

## Colour

| Token | Light | Dark | Use |
|---|---|---|---|
| `brand` | `#0B8F6A` | `#2BC79A` | primary buttons, links, own-location puck |
| `brandOn` | `#FFFFFF` | `#04241B` | text on brand |
| `report` | `#FF6B4A` | `#FF8266` | report FAB, report sheet accents |
| `reportOn` | `#FFFFFF` | `#2A0A03` | icon on report FAB |
| `surface` | `#FFFFFF` | `#141B24` | sheets, cards |
| `surfaceAlt` | `#F2F4F7` | `#1E2733` | search pill, secondary buttons |
| `text` | `#111827` | `#F3F4F6` | primary text |
| `textMuted` | `#6B7280` | `#9CA3AF` | addresses, metadata |
| `navBanner` | `#0F1720` | `#0F1720` | turn-by-turn banner |
| `navBannerOn` | `#FFFFFF` | `#FFFFFF` | |
| `routeActive` | `#1E6BFF` | `#4D8BFF` | selected route line |
| `routeAlt` | `#9DB8F5` | `#3A4F7A` | alternative routes |
| `traffic1` | `#F5C518` | same | light slowdown |
| `traffic2` | `#FF8A00` | same | moderate |
| `traffic3` | `#E53935` | same | heavy |
| `traffic4` | `#7F1D1D` | same | standstill |
| `speeding` | `#E53935` | same | speedometer when above limit |

Report type colours (tile backgrounds): traffic `#FF8A00`, police `#2563EB`,
accident `#E53935`, hazard `#F59E0B`, closure `#6B7280`, blocked_lane `#8B5CF6`,
map_issue `#0EA5E9`, weather `#14B8A6`, animal `#84CC16`.

## Shape & spacing

- Radius: `sm 8`, `md 14`, `lg 20`, `pill 999`. Report FAB is 72×72, radius 22.
- Round FABs 56 dp/pt, white surface, shadow `0 2 8 rgba(0,0,0,.18)`.
- Spacing scale 4/8/12/16/24/32. Screen gutter 16.
- Bottom sheet top radius 20, grab handle 36×4.

## Type

System fonts (Roboto / SF Pro).
`etaClock 34/bold`, `navDistance 40/bold`, `title 22/semibold`,
`body 17/regular`, `caption 14/regular`, `button 17/semibold`.

## Iconography

Original line icons (Material Symbols on Android, SF Symbols on iOS) and
Gimano's own flat report illustrations, all original work. No third-party
mascots or avatars. Drivers show as small brand-coloured "pebble" dots with a
mood emoji.
