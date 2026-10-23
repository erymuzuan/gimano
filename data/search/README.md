# Search aliases (`aliases.csv`)

Edited by hand (and by the translation agent via PR). The API embeds the file at build time and, in Docker, reads
`/data/search/aliases.csv` (config `Search:AliasesPath`). It re-reads the file when it changes (checked every minute), or
immediately with `POST /api/v1/admin/search/aliases/reload` (X-Admin-Key). No re-import is needed: aliases are applied to the
query, not the index.

## Columns

| column | meaning |
|---|---|
| `alias` | what people type. Matched as whole words after normalisation (lower case, accents folded, apostrophes dropped, `&` → `dan`, punctuation/hyphens → space). |
| `canonical` | for `brand`/`rename`/`dialect`: the name to search instead (the original text is searched too). For `category`: one or more category ids from docs/api.md, separated by `\|` (e.g. `surau\|mosque`). |
| `kind` | `brand` (short name/nickname of a chain), `rename` (old name → current name, abbreviations of institutions), `dialect` (regional/colloquial words and abbreviations, e.g. `jln` → `jalan`), `category` (the whole query means "nearest X"). |
| `locale` | `ms`, `en`, or a dialect tag such as `ms-kelantan`, `ms-sabah`. Informational. |
| `note` | free text (source, date of a rename). |

Rules:
- One row per alias→canonical pair; an alias may appear on several rows (e.g. `kk` → both "klinik kesihatan" and
  "Kota Kinabalu"); every variant is searched.
- `category` rows apply only when the **whole** query (or the part left after an area name, e.g. "minyak shah alam") is
  the phrase.
- Lines starting with `#` are comments. Quote a field with `"` if it contains a comma.
- Keep it factual: a `rename` needs a real rename (put the year in `note`).

## Review of `docs/i18n/search-aliases-proposed.csv` (Antigravity, 341 rows)

Merged 164 rows (marked "(Antigravity)" in `note`): 91 category words and 73 brand/rename/abbreviation rows.
Dialect rows whose canonical is a category (mesjid, balaisah, wat, pomen, kodai, cucuk duit…) were stored as `category`
rows, because a `dialect` row rewrites words in the query rather than picking a category. Not taken, and why:

- **Already covered**: rows that duplicate an existing alias, or that normalisation already handles (familymart / family mart,
  7 eleven, 99speedmart, mr diy, dominos, eco shop, lotuss, mcdonalds, a&w) or that rewrite a name to itself (kfc, petronas,
  shell, maybank, ikea, starbucks…).
- **No such category**: `marketplace` (pasar malam/tani/pagi/basah/minggu, tamu, nat, pasar karat, bazar ramadan, market,
  meket) and `restaurant:mamak` (mamak/nasi kandar are already restaurant words). Fire stations (bomba), land offices,
  childcare (taska), waterfalls/beaches (air terjun, jeram, lata, pantai, pata) and car batteries aren't imported into those
  categories yet, so those words would return the wrong places.
- **Ambiguous as a whole query**: kk (klinik kesihatan *and* Kota Kinabalu: kept as two rewrites), pasti and kemas (also
  everyday words), madrasah (a school as well as a surau), sib, bk, cbdt, caring, nsks, five, and expressway nicknames that
  are common English words (plus, elite, dash, silk, sprint, smart).
- **Not verified**: dialect spellings we couldn't confirm (sejid, sijik, spital, sepital, kepci, kede, kede runcit,
  kedai pati, tesen bas, basuh kete, cucioto, pangkalan, epot, opis kerajaan, polok, port makan) and university rows that
  map an abbreviation to the word "university" (uitm, um, ukm, upm; kept as renames to the full names). chatime → Tealive
  was skipped because Chatime trades again in Malaysia. ciq → "Sultan Iskandar Building" doesn't match the OSM name.
  Re-propose any of these with a source.
