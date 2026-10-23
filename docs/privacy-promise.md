# The Gimano privacy promise

Marketing copy (Bahasa Melayu + English) for the website, app stores, onboarding and social
posts. Every claim here is enforced by the rules in [`AGENTS.md`](../AGENTS.md) and the design in
[`privacy-and-backup.md`](privacy-and-backup.md). Keep the two in sync; never claim more than
the product does.

## Tagline

- **BM:** *Gi mano pun, rahsia anda kekal milik anda.*
- **EN:** *Wherever you're going, it stays your business.*

## Short version (app store / onboarding card)

**BM**
> Rumah, tempat kerja, kampung, rumah mertua: semua tempat kegemaran anda disimpan dalam telefon
> anda sahaja. Gimano tidak menyimpan data peribadi anda di pelayan kami. Sandaran pergi terus ke
> Google Drive atau iCloud **anda sendiri**, dan boleh dikunci dengan kata laluan yang hanya anda tahu.

**EN**
> Home, work, kampung, your in-laws' house: your places live on your phone, not on our servers.
> Gimano never stores your personal data. Backups go straight to **your own** Google Drive or
> iCloud, optionally locked with a passphrase only you know.

## The promise, in 5 points

1. **Your places stay on your phone.** Favourites, recents, private places and planned drives are
   stored on your device only.
   *Tempat kegemaran, sejarah carian dan tempat peribadi disimpan dalam telefon anda sahaja.*
2. **Your backup, your cloud.** Back up to your own Google Drive, iCloud or a file. We never see it.
   *Sandaran ke Google Drive, iCloud atau fail anda sendiri. Kami tidak dapat melihatnya.*
3. **Lock it with your own key.** Optional end-to-end encryption with a passphrase only you know.
   *Kunci sandaran dengan kata laluan yang hanya anda tahu.*
4. **Traffic help without tracking you.** While you navigate, your phone shares anonymous speed
   points that are turned into road speeds within minutes. We don't keep your trips.
   *Data kelajuan tanpa nama digunakan untuk trafik. Perjalanan anda tidak disimpan.*
5. **No ads, no trackers, open source.** Gimano is MIT-licensed. Anyone can check the code on
   GitHub.
   *Tiada iklan, tiada penjejak, kod sumber terbuka.*

## We never know who you are

*Kami tidak pernah tahu siapa anda.* Your contributions are credited to a random pseudonym that only your
phone (and your own backup) can sign for. We never ask for your email, phone number, name or Google/Apple login
to use Gimano. If *you choose* to leave an email or WhatsApp number on a feedback or crash report, it's used only
to reply about that report and then deleted. Your
reputation (Baharu, Perak, Emas) describes your public work, not you.

## What we do keep (be upfront)

Only what you choose to share with everyone (road reports, public places, reviews and photos,
and feedback), credited to your pseudonym and its reputation counters. You can delete your account at any time; your public contributions are then
anonymised.

*Kami hanya menyimpan apa yang anda kongsi secara terbuka: laporan jalan, tempat awam, ulasan,
gambar dan maklum balas.*

## Usage notes for marketing

- Do **not** say "we never store your email": we do, briefly, if you volunteer it on a feedback/crash report.
  Say "we never ask for your email to use Gimano".
- Do **not** say "we collect no data at all": we do receive public contributions and anonymous
  speed pings.
- Do **not** promise "military-grade" or similar. Say "end-to-end encrypted with a passphrase only you
  know" (libsodium Argon2id + XChaCha20-Poly1305), and only for passphrase-protected backups.
- "Open source" claims link to https://github.com/erymuzuan/manotu.
- Store listings: keep the Google Play Data safety form and Apple's privacy nutrition label consistent with
  this page (data not collected: location history, contacts, search history; data shared
  voluntarily: user content).
