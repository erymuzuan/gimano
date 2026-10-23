# Manotu marketing images

Generated with the built-in image generation tool from [the brief](../image-prompts.md). PNG background artwork; headlines are added separately. All depicted people are fictional illustrations.

Final sizes follow the explicit pixel dimensions in the brief: squares 1024×1024, verticals 1024×1792, and panoramas 1536×804. The vertical pixel specification takes precedence over the approximate 9:16 label. Generated files were resampled to these delivery sizes with macOS `sips`.

| File | Pixels |
|---|---|
| [ig-01-balik-kampung.png](ig-01-balik-kampung.png) | 1024×1024 |
| [ig-02-privacy-phone.png](ig-02-privacy-phone.png) | 1024×1024 |
| [ig-03-community-flood.png](ig-03-community-flood.png) | 1024×1024 |
| [ig-04-open-source.png](ig-04-open-source.png) | 1024×1024 |
| [tt-01-cover.png](tt-01-cover.png) | 1024×1792 |
| [tt-02-raya-traffic.png](tt-02-raya-traffic.png) | 1024×1792 |
| [fb-01-cover.png](fb-01-cover.png) | 1536×804 |
| [fb-02-mertua.png](fb-02-mertua.png) | 1536×804 |

## Prompts used

Each entry records the complete generation prompt and subsequent edits used for the final image. All calls used an opaque background (`transparent_background: false`).

### ig-01-balik-kampung.png

Generation prompt:

```text
Use case: ads-marketing. Background artwork, no text, letters, numbers, logos, watermarks, brand marks, car badges, fuel-station logos, real app UIs, or trademarked buildings. Generic Malaysian settings, fictional illustrated people of diverse Malaysian backgrounds in modest everyday clothing; no real identifiable people or celebrities. Brand palette cobalt blue #0047AB, pale cobalt #E3ECFA / #8FB0E8, deep navy #0B1B3A, coral #FF6B4A, warm sunlight where appropriate. Clean optimistic modern Malaysian feel. Phone screens show only abstract map with blue route line and requested symbolic icons. Leave calm negative space at top third or one side for later headline overlay. No text is to be rendered.
Output: single PNG artwork, 1024×1024 pixels. Use this explicit pixel canvas size from the brief.
Primary request: Flat vector illustration, golden-hour drive on a winding kampung road lined with coconut palms and paddy fields, a small family car heading toward a wooden kampung house on stilts, a traditional wau bulan kite flying high in the sky like a guiding arrow, cobalt sky gradient, coral sun, soft clouds, warm and nostalgic, generous empty sky at top
```

Edit prompt (applied to the preceding output):

```text
Remove EVERY car logo, badge, emblem and little lettering/model marking from ALL vehicles, including wheel centers and trunk lids. Replace with smooth uninterrupted paint matching the car body. Keep license plates completely blank. There must be absolutely no letters, logos, symbols, badges or wordmarks on any part of any car. Preserve all other scene elements, colors, composition and aspect ratio. Render in clean flat vector illustration style with simplified shapes and flat color areas.
```

### ig-02-privacy-phone.png

Generation prompt:

```text
Use case: ads-marketing. Background artwork, no text, letters, numbers, logos, watermarks, brand marks, car badges, fuel-station logos, real app UIs, or trademarked buildings. Generic Malaysian settings, fictional illustrated people of diverse Malaysian backgrounds in modest everyday clothing; no real identifiable people or celebrities. Brand palette cobalt blue #0047AB, pale cobalt #E3ECFA / #8FB0E8, deep navy #0B1B3A, coral #FF6B4A, warm sunlight where appropriate. Clean optimistic modern Malaysian feel. Phone screens show only abstract map with blue route line and requested symbolic icons. Leave calm negative space at top third or one side for later headline overlay. No text is to be rendered.
Output: single PNG artwork, 1024×1024 pixels. Use this explicit pixel canvas size from the brief.
Primary request: Soft 3D clay-render style, a hand holding a smartphone; the screen shows an abstract map with three small location pins (house, heart, mosque dome shapes) enclosed by a glowing cobalt shield with a padlock; tiny dotted lines trying to leave the phone are blocked by the shield; pale cobalt background, coral accents, calm and trustworthy
```

Edit prompt (applied to the preceding output):

```text
Change this image to soft 3D clay render style with a plain pale cobalt studio background, removing the entire city, mosque and buildings in the background. Preserve the hand, phone, abstract map route, three icon pins, glowing shield and padlock, blocked dotted lines. No text or letters.
```

### ig-03-community-flood.png

Generation prompt:

```text
Use case: ads-marketing. Background artwork, no text, letters, numbers, logos, watermarks, brand marks, car badges, fuel-station logos, real app UIs, or trademarked buildings. Generic Malaysian settings, fictional illustrated people of diverse Malaysian backgrounds in modest everyday clothing; no real identifiable people or celebrities. Brand palette cobalt blue #0047AB, pale cobalt #E3ECFA / #8FB0E8, deep navy #0B1B3A, coral #FF6B4A, warm sunlight where appropriate. Clean optimistic modern Malaysian feel. Phone screens show only abstract map with blue route line and requested symbolic icons. Leave calm negative space at top third or one side for later headline overlay. No text is to be rendered.
Output: single PNG artwork, 1024×1024 pixels. Use this explicit pixel canvas size from the brief.
Primary request: Flat illustration, rainy Kuala Lumpur evening, a flooded road ahead with a warning cone, drivers in different cars kindly pointing / waving to warn each other, glowing coral warning triangle hovering above the flood like an app alert, reflections on wet asphalt, cobalt-navy dusk palette
Strict visual restrictions: every vehicle has completely plain unmarked bodywork and blank license plates. Skyline must be invented simple rectangular blocks only, NO twin towers, skybridges, needle towers or recognizable landmarks. Use illustrations rather than photographic people. Keep the requested style.
Make a clean flat graphic illustration with simple shapes and restrained details. The alert is an empty coral triangular outline, without any punctuation or symbol within it.
```

Edit prompt (applied to the preceding output):

```text
Remove EVERY car logo, badge, emblem and little lettering/model marking from ALL vehicles, including wheel centers and trunk lids. Replace with smooth uninterrupted paint matching the car body. Keep license plates completely blank. There must be absolutely no letters, logos, symbols, badges or wordmarks on any part of any car. Preserve all other scene elements, colors, composition and aspect ratio. Render in clean flat vector illustration style with simplified shapes and flat color areas.
```

### ig-04-open-source.png

Generation prompt:

```text
Use case: ads-marketing. Background artwork, no text, letters, numbers, logos, watermarks, brand marks, car badges, fuel-station logos, real app UIs, or trademarked buildings. Generic Malaysian settings, fictional illustrated people of diverse Malaysian backgrounds in modest everyday clothing; no real identifiable people or celebrities. Brand palette cobalt blue #0047AB, pale cobalt #E3ECFA / #8FB0E8, deep navy #0B1B3A, coral #FF6B4A, warm sunlight where appropriate. Clean optimistic modern Malaysian feel. Phone screens show only abstract map with blue route line and requested symbolic icons. Leave calm negative space at top third or one side for later headline overlay. No text is to be rendered.
Output: single PNG artwork, 1024×1024 pixels. Use this explicit pixel canvas size from the brief.
Primary request: Flat isometric illustration, diverse Malaysian volunteers around a big table assembling a giant paper map of Peninsular Malaysia like a puzzle, laptops showing abstract code lines (no readable text), a wau kite overhead, cobalt and pale-blue palette with coral highlights, collaborative and open
```

### tt-01-cover.png

Generation prompt:

```text
Use case: ads-marketing. Background artwork, no text, letters, numbers, logos, watermarks, brand marks, car badges, fuel-station logos, real app UIs, or trademarked buildings. Generic Malaysian settings, fictional illustrated people of diverse Malaysian backgrounds in modest everyday clothing; no real identifiable people or celebrities. Brand palette cobalt blue #0047AB, pale cobalt #E3ECFA / #8FB0E8, deep navy #0B1B3A, coral #FF6B4A, warm sunlight where appropriate. Clean optimistic modern Malaysian feel. Phone screens show only abstract map with blue route line and requested symbolic icons. Leave calm negative space at top third or one side for later headline overlay. No text is to be rendered.
Output: single PNG artwork, 1024×1792 pixels. Use this explicit pixel canvas size from the brief.
Primary request: Cinematic vertical night scene, generic Malaysian city skyline and elevated highways with light trails, a giant translucent wau bulan kite made of glowing cobalt light rising above the city like a navigation arrow, empty space in the upper third, moody navy sky, coral light accents
Strict visual restrictions: every vehicle has completely plain unmarked bodywork and blank license plates. Skyline must be invented simple rectangular blocks only, NO twin towers, skybridges, needle towers or recognizable landmarks. Use illustrations rather than photographic people. Keep the requested style.
```

Edit prompt (applied to the preceding output):

```text
Remove EVERY car logo, badge, emblem and little lettering/model marking from ALL vehicles, including wheel centers and trunk lids. Replace with smooth uninterrupted paint matching the car body. Keep license plates completely blank. There must be absolutely no letters, logos, symbols, badges or wordmarks on any part of any car. Preserve all other scene elements, colors, composition and aspect ratio.
```

Edit prompt (applied to the preceding output):

```text
Edit only the vehicles: replace all visible cars with uninterrupted glowing light trails. Keep highways, city, sky, giant luminous kite and composition exactly unchanged. No cars or vehicle emblems remain. No text, logos or watermark.
```

### tt-02-raya-traffic.png

Generation prompt:

```text
Use case: ads-marketing. Background artwork, no text, letters, numbers, logos, watermarks, brand marks, car badges, fuel-station logos, real app UIs, or trademarked buildings. Generic Malaysian settings, fictional illustrated people of diverse Malaysian backgrounds in modest everyday clothing; no real identifiable people or celebrities. Brand palette cobalt blue #0047AB, pale cobalt #E3ECFA / #8FB0E8, deep navy #0B1B3A, coral #FF6B4A, warm sunlight where appropriate. Clean optimistic modern Malaysian feel. Phone screens show only abstract map with blue route line and requested symbolic icons. Leave calm negative space at top third or one side for later headline overlay. No text is to be rendered.
Output: single PNG artwork, 1024×1792 pixels. Use this explicit pixel canvas size from the brief.
Primary request: Vertical illustration, festive Hari Raya balik kampung traffic on a North–South expressway at sunrise, long line of cars, ketupat and pelita decorations along the roadside, one car taking a calm alternative route shown as a glowing cobalt line through palm-oil estates, hopeful mood
Strict visual restrictions: every vehicle has completely plain unmarked bodywork and blank license plates. Skyline must be invented simple rectangular blocks only, NO twin towers, skybridges, needle towers or recognizable landmarks. Use illustrations rather than photographic people. Keep the requested style.
```

Edit prompt (applied to the preceding output):

```text
Remove EVERY car logo, badge, emblem and little lettering/model marking from ALL vehicles, including wheel centers and trunk lids. Replace with smooth uninterrupted paint matching the car body. Keep license plates completely blank. There must be absolutely no letters, logos, symbols, badges or wordmarks on any part of any car. Preserve all other scene elements, colors, composition and aspect ratio.
```

Edit prompt (applied to the preceding output):

```text
Precisely remove the small circular or oval badges in the centers of the tailgates of the white cars (and any other car). In particular the nearest white car still has a circular emblem just above its license plate. Paint over those circles with seamless plain white body paint; the tailgate should have absolutely no circles or ovals or markings. Preserve everything else exactly.
```

Edit prompt (applied to the preceding output):

```text
Replace all of the cars in the traffic jam on the left half with simple flat rectangular cartoon cars viewed from behind, with smooth coral or cobalt bodywork, rear windows and two tail lights only. No badges, circles, ovals, logos or license plate lettering on any car. Keep the family blue car on the right, scenery, sunrise, decorations and composition unchanged.
```

### fb-01-cover.png

Generation prompt:

```text
Use case: ads-marketing. Background artwork, no text, letters, numbers, logos, watermarks, brand marks, car badges, fuel-station logos, real app UIs, or trademarked buildings. Generic Malaysian settings, fictional illustrated people of diverse Malaysian backgrounds in modest everyday clothing; no real identifiable people or celebrities. Brand palette cobalt blue #0047AB, pale cobalt #E3ECFA / #8FB0E8, deep navy #0B1B3A, coral #FF6B4A, warm sunlight where appropriate. Clean optimistic modern Malaysian feel. Phone screens show only abstract map with blue route line and requested symbolic icons. Leave calm negative space at top third or one side for later headline overlay. No text is to be rendered.
Output: single PNG artwork, 1536×804 pixels. Use this explicit pixel canvas size from the brief.
Primary request: Wide panoramic flat illustration, Malaysian landscape from city to kampung left-to-right: city towers, highway, palm estates, kampung house and padi field, a cobalt route line connecting them with small coral pins, a wau kite in the sky, space on the left half for a headline
Strict visual restrictions: every vehicle has completely plain unmarked bodywork and blank license plates. Skyline must be invented simple rectangular blocks only, NO twin towers, skybridges, needle towers or recognizable landmarks. Use illustrations rather than photographic people. Keep the requested style.
```

Edit prompt (applied to the preceding output):

```text
Remove EVERY car logo, badge, emblem and little lettering/model marking from ALL vehicles, including wheel centers and trunk lids. Replace with smooth uninterrupted paint matching the car body. Keep license plates completely blank. There must be absolutely no letters, logos, symbols, badges or wordmarks on any part of any car. Preserve all other scene elements, colors, composition and aspect ratio. Render in clean flat vector illustration style with simplified shapes and flat color areas.
```

Edit prompt (applied to the preceding output):

```text
Precisely remove the small circular or oval badges in the centers of the tailgates of the white cars (and any other car). In particular the nearest white car still has a circular emblem just above its license plate. Paint over those circles with seamless plain white body paint; the tailgate should have absolutely no circles or ovals or markings. Preserve everything else exactly.
```

Edit prompt (applied to the preceding output):

```text
Remove the large white SUV in the lower left entirely and fill that area with the continuation of the empty gray road and lane markings. Keep all other elements unchanged. No text or logos.
```

### fb-02-mertua.png

Generation prompt:

```text
Use case: ads-marketing. Background artwork, no text, letters, numbers, logos, watermarks, brand marks, car badges, fuel-station logos, real app UIs, or trademarked buildings. Generic Malaysian settings, fictional illustrated people of diverse Malaysian backgrounds in modest everyday clothing; no real identifiable people or celebrities. Brand palette cobalt blue #0047AB, pale cobalt #E3ECFA / #8FB0E8, deep navy #0B1B3A, coral #FF6B4A, warm sunlight where appropriate. Clean optimistic modern Malaysian feel. Phone screens show only abstract map with blue route line and requested symbolic icons. Leave calm negative space at top third or one side for later headline overlay. No text is to be rendered.
Output: single PNG artwork, 1536×804 pixels. Use this explicit pixel canvas size from the brief.
Primary request: Warm flat illustration, a young couple arriving at the in-laws' terrace house, grandparents waving at the gate with a cat, the car parked, a phone in the wife's hand showing a pin labelled only by a small heart icon, late-afternoon light, cobalt and coral palette, humorous and heartwarming
Strict visual restrictions: every vehicle has completely plain unmarked bodywork and blank license plates. Skyline must be invented simple rectangular blocks only, NO twin towers, skybridges, needle towers or recognizable landmarks. Use illustrations rather than photographic people. Keep the requested style.
```

Edit prompt (applied to the preceding output):

```text
Remove EVERY car logo, badge, emblem and little lettering/model marking from ALL vehicles, including wheel centers and trunk lids. Replace with smooth uninterrupted paint matching the car body. Keep license plates completely blank. There must be absolutely no letters, logos, symbols, badges or wordmarks on any part of any car. Preserve all other scene elements, colors, composition and aspect ratio. Render in clean flat vector illustration style with simplified shapes and flat color areas.
```

