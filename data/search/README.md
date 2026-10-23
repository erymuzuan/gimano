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
