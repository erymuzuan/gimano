# Manotu Localization Glossary (EN ↔ BM)

This glossary is the source of truth for English (`en`) and Bahasa Melayu (`ms` / `ms-MY`) copy across the Android (`android/app/src/main/res/values*/strings.xml`, `ManeuverFormatter.kt`, `Formatters.kt`) and iOS (`ios/Gimano/Resources/Localizable.xcstrings`, `Formatters.swift`) apps.

---

## 1. Tone & Style Rules

1. **Pronoun rule (non-negotiable):** Always use **"anda"** for "you" / "your". Never use **"kamu"**, **"awak"**, or **"engkau"**.
   - When natural in Bahasa Melayu, drop redundant possessive pronouns altogether (e.g., *"Tempat disimpan dalam telefon ini"* rather than *"Tempat anda disimpan dalam telefon anda"*).
2. **Driver-first brevity:** Navigation banners, turn instructions, map pills, and action buttons are glanced at while driving. Keep labels to **1–3 words** and buttons/chips under **~24 characters**.
   - Prefer active, imperative root verbs on buttons: `Simpan` (not *Simpankan*), `Kongsi` (not *Kongsikan*), `Sandar` / `Pulih` on tight buttons, `Cari` (not *Carikan*).
3. **Friendly, everyday Malaysian BM:** Use standard Bahasa Melayu as spoken and read on Malaysian road signs (JKR / LLM terminology) rather than overly formal/literary or Indonesian-flavoured phrasing:
   - Use **Lebuhraya** (highway/expressway), **Susur keluar** (exit), **Bulatan** (roundabout), **Pusingan U** (U-turn), **Plaza tol** (toll plaza), **Kesesakan** (traffic jam), **Lubang jalan** (pothole), **Lencongan** / **Laluan alternatif** (detour/alternative route).
   - Use **Telefon** (not *Panggil*) for calling a phone number, and **Dalam giliran** (not *Beratur*) for queued items.

---

## 2. When to Keep Terms As-Is (Untranslated / Shared Across Locales)

### Keep in English (even in Bahasa Melayu UI)
Drivers in Malaysia universally recognise these abbreviations and technical/brand terms; translating them makes the UI harder to scan at a glance:
- **Navigation & road abbreviations:** `ETA` (or `Tiba` when used as an arrival prefix), `GPS`, `km`, `m`, `km/h` (or `km/j`), `R&R` (Rehat & Rawat).
- **Vehicle & charging:** `EV` (as in `Pengecas EV` / `EV charger`), `Touch 'n Go`, `RFID`, `PlusMiles`.
- **Tech & connectivity:** `Wi-Fi`, `Google Drive`, `iCloud`, `iOS`, `Android`, `GitHub`, `OpenStreetMap`, `ODbL`, `JSON`, `PIN`, `URL`.
- **Short status badges:** `Live` (map traffic pill), `Demo`, `2D`, `3D`, `Auto`, `Spam` (or `Spam / palsu`).

### Keep in Bahasa Melayu (even in English UI)
These culturally specific Malaysian terms and Manotu community tier names stay in Bahasa Melayu in **both** English and Malay locales:
- **Reputation / trust tiers:**
  - `Baharu` — New contributor tier (never translate as *"New"* in tier badges).
  - `Perak` — Silver contributor tier (never translate as *"Silver"*).
  - `Emas` — Gold contributor tier (never translate as *"Gold"*).
- **Malaysian place & family concepts:**
  - `Kampung` — Hometown / ancestral village quick-save chip and favourite icon.
  - `Rumah Mertua` — In-laws' home quick-save chip (used alongside *"In-laws"* in English explanation prose, e.g. *"Home, work, kampung, your in-laws' house"*).
  - `Surau` — Islamic prayer room (distinct from `Masjid` / `Mosque`).
  - `Mamak` — Mamak stall / restaurant (`Kedai mamak`).

---

## 3. Core Term Tables

### 3.1 Navigation & Maneuvers

| English (`en`) | Bahasa Melayu (`ms`) | Context & Notes |
|---|---|---|
| Go / Start | Mula | Primary button to start turn-by-turn navigation |
| Preview | Pratonton | Route preview button |
| Stop / End drive | Tamat / Tamatkan perjalanan | `Tamat` on compact nav strip; `Tamatkan perjalanan` on confirmation sheet |
| Overview | Gambaran keseluruhan | Route overview toggle |
| Re-centre / Recenter | Pusatkan semula | Map camera recenter button (`Pusatkan` on narrow chip) |
| Mute / Unmute | Senyap / Bunyikan | Voice guidance toggle (`Suara dimatikan` / `Suara dihidupkan` for accessibility) |
| Head toward %s / Depart | Mulakan perjalanan / Menuju ke %s | Initial departure instruction |
| Continue / Continue straight | Teruskan / Terus sahaja | Continue on current road (`Terus di %s`) |
| Turn left / Turn right | Belok kiri / Belok kanan | Standard 90° turn |
| Slight left / Slight right | Belok sedikit ke kiri / Belok sedikit ke kanan | Gentle fork or curve |
| Bear left / Bear right | Kekal ke kiri / Kekal ke kanan | Prefer `Kekal ke kiri/kanan` or `Belok sedikit ke kiri/kanan` (avoid *Condong*, which means physical tilting) |
| Sharp left / Sharp right | Belok tajam ke kiri / Belok tajam ke kanan | Acute turn |
| Keep left / Keep right | Ikut kiri / Ikut kanan | Lane / fork keeping instruction |
| Make a U-turn | Buat pusingan U | U-turn instruction (`Buat pusingan U di %s`) |
| Enter the roundabout | Masuk ke bulatan | Roundabout entry |
| At the roundabout, take the %s exit | Di bulatan, ambil susur keluar ke-%s | Roundabout with ordinal exit number (`pertama`, `kedua`, `ketiga`, `ke-4`…) |
| Exit the roundabout | Keluar dari bulatan | Roundabout exit |
| Take exit %s / Take the exit | Ambil susur keluar %s / Ambil susur keluar | Highway ramp / slip road |
| Merge / Merge onto %s | Masuk ke lorong / Gabung ke %s | Highway merge (avoid intransitive *Bergabung* alone when sharing key with data merge) |
| Take the ferry | Naik feri | Ferry crossing |
| You have arrived | Anda telah tiba | Arrival voice & banner prompt |
| Arrive at %s | Tiba di %s | Destination arrival banner |
| On the left / On the right | di sebelah kiri / di sebelah kanan | Arrival side qualifier |
| Then | Kemudian | Next-maneuver strip prefix |
| Recalculating… / Rerouting… | Mengira semula laluan… | Off-route recalculation banner |
| Finding route… / Calculating route… | Mencari laluan… / Mengira laluan… | Route calculation loading state |
| Waiting for GPS… | Mencari isyarat GPS… | Compact turn-banner title while acquiring fix |
| Fastest / Alternative / Shorter | Paling pantas / Alternatif / Lebih dekat | Route option tags |
| Typical traffic / Light traffic / Heavier than usual | Trafik biasa / Trafik lancar / Lebih sesak daripada biasa | Route traffic summary |
| Avoid tolls | Elak tol | Route preference toggle |
| Avoid ferries | Elak feri | Route preference toggle |
| Avoid highways | Elak lebuhraya | Route preference toggle |
| Toll / Toll-free | Bertol / Tanpa tol | Route card pill |
| Free flow | Lancar | Traffic speed pill when road is clear |
| Best time to leave | Masa terbaik bertolak | Hourly ETA planner action & sheet title |
| Leave now / Leave at | Bertolak sekarang / Bertolak pada | Departure time selector |
| Planned drives | Perjalanan dirancang | Local departure reminder feature |
| Share live drive / Stop sharing | Kongsi perjalanan langsung / Henti kongsi | End-to-end encrypted live ETA sharing |

### 3.2 Road Reports & Subtypes

| English (`en`) | Bahasa Melayu (`ms`) | Context & Notes |
|---|---|---|
| Report / Report on road | Lapor / Lapor keadaan jalan | Main FAB & sheet title |
| Traffic / Traffic jam | Trafik / Kesesakan | Category chip (`Trafik`) and report label (`Kesesakan`) |
| Moderate / Heavy / Standstill | Sederhana / Sesak teruk / Tidak bergerak | Traffic jam severity subtypes |
| Police | Polis | Police / enforcement report |
| Roadblock / Speed camera / Patrol | Sekatan jalan / Kamera laju / Peronda | Police subtypes |
| Accident | Kemalangan | Crash / collision report |
| Minor / Major / Other lane | Kecil / Teruk / Lorong sebelah | Accident subtypes (`Lorong bertentangan` also acceptable) |
| Hazard | Bahaya | Road hazard category |
| Pothole | Lubang jalan | Hazard subtype |
| Object on road / Debris | Objek di atas jalan | Hazard subtype (`Objek di jalan` on compact tile) |
| Stopped vehicle / Stopped | Kenderaan berhenti / Berhenti | Full label vs compact subtype tile (`Berhenti`) |
| Works / Roadworks | Kerja jalan | Road construction / maintenance |
| Flood | Banjir | Flooded road report |
| Passable / Impassable | Boleh dilalui / Tidak boleh dilalui | Flood subtypes (`Boleh lalu` / `Tak boleh lalu` on compact tiles) |
| Road closed / Closure | Jalan ditutup | Prefer active `Jalan ditutup` on both platforms |
| Still there / Thumbs up | Masih ada | Confirming an active road report |
| Not there / Gone | Tiada lagi | Dismissing a cleared road report |
| Ahead | Di hadapan | Proximity alert banner |

### 3.3 Places, Categories & Favourites

| English (`en`) | Bahasa Melayu (`ms`) | Context & Notes |
|---|---|---|
| Saved / Favourites | Tersimpan / Kegemaran | Bottom tab (`Tersimpan`), section header (`Kegemaran`) |
| Save / Saved | Simpan / Disimpan | Place card action button |
| Home | Rumah | Quick-access favourite slot |
| Work | Kerja | Quick-access favourite slot (`Tempat kerja` in prose) |
| Kampung | Kampung | Kept in Malay in both EN and MS |
| Rumah Mertua / In-laws' house | Rumah Mertua | Kept as `Rumah Mertua` on quick chips; `rumah mertua` in prose |
| Gym | Gim | Quick label for favourites |
| School | Sekolah | Quick label & place category |
| Just for me / Private | Untuk saya sahaja / Peribadi | On-device-only saved place visibility |
| Share with everyone / Public | Kongsi dengan semua / Awam | Community-contributed public place visibility |
| Add a place / New place | Tambah tempat / Tempat baharu | Map contribution action |
| Edit place / Suggest an edit | Sunting tempat / Cadangkan suntingan | Editing place details |
| Move the pin to the exact entrance | Gerakkan pin ke pintu masuk sebenar | Pin placement instruction |
| Dropped pin | Pin diletakkan | Map long-press pin title |
| Petrol / Fuel | Stesen minyak | Category (`Minyak` on 1-word shortcut chip) |
| EV charger / EV charging | Pengecas EV | Category & shortcut chip |
| Food / Restaurant | Makan / Restoran | Shortcut chip (`Makan`) vs specific category (`Restoran`) |
| Cafe | Kafe | Place category |
| Fast food | Makanan segera | Place category |
| Rest area (R&R) | R&R / Kawasan rehat | Highway rest stop |
| Toll plaza / Toll booth | Plaza tol | Highway toll |
| Mosque | Masjid | Place category |
| Surau | Surau | Prayer room (kept in both EN and MS) |
| Temple / Church | Kuil / Gereja | Place of worship categories |
| Hospital / Clinic / Pharmacy | Hospital / Klinik / Farmasi | Medical categories |
| Mall / Shopping mall | Pusat beli-belah | Use `Mall` or `Kompleks` if constrained to a narrow 1-line grid tile |
| Supermarket / Convenience store | Pasar raya / Kedai serbaneka | Retail categories |
| Car repair / Workshop | Bengkel kereta | Automotive category |
| Car wash | Cuci kereta | Automotive category |
| Parking | Tempat letak kereta / Parkir | Use `Parkir` on tight chips |
| Transit station / Airport | Stesen transit / Lapangan terbang | Transport categories |
| Government office | Pejabat kerajaan | Public service category |
| Opening hours / Open 24 hours | Waktu operasi / Buka 24 jam | Business hours |
| Open now / Closed | Buka sekarang / Tutup | Business status pill |
| Call / Website / Share | Telefon / Laman web / Kongsi | Place card action buttons (never *Panggil* for phone call) |

### 3.4 Community, Reviews, Vetting & Reputation

| English (`en`) | Bahasa Melayu (`ms`) | Context & Notes |
|---|---|---|
| Contributions / My contributions | Sumbangan / Sumbangan saya | User's public reports, places, and reviews |
| Baharu | Baharu | Entry trust tier (stays `Baharu` in EN & MS) |
| Perak | Perak | Mid trust tier (stays `Perak` in EN & MS) |
| Emas | Emas | Top trust tier (stays `Emas` in EN & MS) |
| Unverified | Belum disahkan | Status badge on new community places (`Belum sah` if space-constrained) |
| Community verified / Verified | Disahkan komuniti / Disahkan | Place has reached verification threshold |
| Waiting for verification | Menunggu pengesahan | Contribution status in My Contributions |
| Published / Accepted | Diterbitkan / Diterima | Approved contribution status |
| Disputed / Flagged | Dipertikaikan / Dilaporkan | Flagged contribution status |
| Verify / Confirm | Sahkan | Community verification action |
| Yes, it's here | Ya, ada di sini | Vetting prompt positive button |
| Wrong pin / Pin is in wrong spot | Pin salah / Pin di lokasi salah | Vetting prompt pin-correction button |
| Doesn't exist / Not here | Tidak wujud / Tiada di sini | Vetting prompt negative button |
| Parked / Stop within 1 km to verify | Berhenti / Berhenti dalam 1 km untuk mengesahkan | Physical proximity requirement for vetting (avoid *Diletak*) |
| Write a review / Edit your review | Tulis ulasan / Sunting ulasan anda | Place review actions |
| Add photo | Tambah foto | Photo attachment action |
| Report / Flag | Laporkan | Flagging inappropriate/fake content |
| Spam or fake | Spam atau palsu | Flag reason |

### 3.5 Privacy, Backup & Identity

| English (`en`) | Bahasa Melayu (`ms`) | Context & Notes |
|---|---|---|
| Privacy & Data | Privasi & Data | Settings section header |
| Your places stay on your phone | Tempat anda kekal dalam telefon ini | Core privacy promise headline |
| Pseudonym / Your pseudonym | Nama samaran / Nama samaran anda | Random identity for public contributions |
| Recovery code (24 words) | Kod pemulihan (24 perkataan) | Ed25519 key recovery phrase |
| Backup & Restore | Sandaran & Pemulihan | Settings screen title |
| Back up / Restore | Sandar / Pulih | Button verbs (`Sandarkan` / `Pulihkan` in prose) |
| Passphrase | Frasa laluan | Distinct from account *kata laluan* (password); encrypts backups end-to-end |
| End-to-end encrypted | Disulitkan hujung ke hujung | Backup & live-drive encryption badge |
| Replace on phone / Merge | Ganti dalam telefon / Gabung | Restore conflict resolution options |
| Delete my public data | Padam data awam saya | Server-side `DELETE /me` action |
| Feedback & Bug Report | Maklum Balas & Laporan Pepijat | Feedback screen title |
| Crash reports | Laporan ranap | Opt-in local crash log submission |
| Queued | Dalam giliran | Pending network upload status (never *Beratur*) |

### 3.6 Search & Map Controls

| English (`en`) | Bahasa Melayu (`ms`) | Context & Notes |
|---|---|---|
| Search places, addresses… | Cari tempat, alamat… | Search bar placeholder |
| Search along route | Cari sepanjang laluan | Detour search header during navigation |
| Select on map / Choose on map | Pilih pada peta | Picking a point directly on the map canvas |
| Nearest | Terdekat | Distance-sorted results / nearest-first filter |
| Did you mean | Maksud anda | Search spell-correction / alias suggestion prompt |
| Recent / Recents | Terkini / Carian terkini | Local on-device search history |
| Clear / Clear history | Kosongkan / Kosongkan sejarah | Clearing local recents |
| No results found | Tiada hasil ditemui | Empty search state |
| Map controls / Map style | Kawalan peta / Gaya peta | Map appearance settings |
| Light / Dark / Auto | Cerah / Gelap / Auto | Theme & map style options |
| Recenter | Pusatkan | Map floating control |
| Compass / North up | Kompas / Utara di atas | Map orientation toggle |
