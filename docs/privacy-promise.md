# The Manotu privacy promise

Marketing copy (Bahasa Melayu + English) for the website, app stores, onboarding and social
posts. Every claim here is enforced by the rules in [`AGENTS.md`](../AGENTS.md) and the design in
[`privacy-and-backup.md`](privacy-and-backup.md). Keep the two in sync; never claim more than
the product does.

## Tagline

- **BM:** *Ke mana pun anda pergi, rahsia anda kekal milik anda.*
- **EN:** *Wherever you're going, it stays your business.*

## Short version (app store / onboarding card)

**BM**
> Rumah, tempat kerja, kampung, rumah mertua: semua tempat kegemaran anda disimpan dalam telefon
> anda sahaja. Manotu tidak menyimpan data peribadi anda di pelayan kami. Sandaran pergi terus ke
> Google Drive atau iCloud **anda sendiri**, dan boleh dikunci dengan kata laluan yang hanya anda tahu.

**EN**
> Home, work, kampung, your in-laws' house: your places live on your phone, not on our servers.
> Manotu never stores your personal data. Backups go straight to **your own** Google Drive or
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
5. **No ads, no trackers, open source.** Manotu is MIT-licensed. Anyone can check the code on
   GitHub.
   *Tiada iklan, tiada penjejak, kod sumber terbuka.*

## We never know who you are

*Kami tidak pernah tahu siapa anda.* Your contributions are credited to a random pseudonym that only your
phone (and your own backup) can sign for. We never ask for your email, phone number, name or Google/Apple login
to use Manotu. If *you choose* to leave an email or WhatsApp number on a feedback or crash report, it's used only
to reply about that report and then deleted. Your
reputation (Baharu, Perak, Emas) describes your public work, not you.

## What we do keep (be upfront)

Only what you choose to share with everyone (road reports, public places, reviews and photos,
and feedback), credited to your pseudonym and its reputation counters. You can delete your account at any time; your public contributions are then
anonymised.

*Kami hanya menyimpan apa yang anda kongsi secara terbuka: laporan jalan, tempat awam, ulasan,
gambar dan maklum balas.*

**Event invitation links (owner-approved exception, 24 Oct 2026).** When you share an event with a short link, our
server keeps the event plan **end-to-end encrypted**, without the key: the key is in the part of the link after `#`,
which never reaches our servers, so we can't read the plan. It is deleted 14 days after the event ends (at most 400
days), when you end the link, or when you delete your account.

*Pautan jemputan acara: pelan acara disimpan di pelayan kami dalam bentuk tersulit hujung ke hujung, tanpa kuncinya.
Kami tidak dapat membacanya. Ia dipadam 14 hari selepas acara tamat (paling lama 400 hari), apabila anda menamatkan
pautan, atau apabila anda memadam akaun.*

## Usage notes for marketing

- Do **not** say "we never store your email": we do, briefly, if you volunteer it on a feedback/crash report.
  Say "we never ask for your email to use Manotu".
- Do **not** say "we collect no data at all": we do receive public contributions and anonymous
  speed pings.
- Do **not** say "nothing about your events is on our servers": short invitation links keep an encrypted copy we can't
  read until the event is over. Say "end-to-end encrypted; we can't read it".
- Do **not** promise "military-grade" or similar. Say "end-to-end encrypted with a passphrase only you
  know" (libsodium Argon2id + XChaCha20-Poly1305), and only for passphrase-protected backups.
- "Open source" claims link to https://github.com/erymuzuan/manotu.
- Store listings: keep the Google Play Data safety form and Apple's privacy nutrition label consistent with
  this page (data not collected: location history, contacts, search history; data shared
  voluntarily: user content).
- The Google Play build has **no Google Drive backup** (owner decision 2026-10-24): on the Play listing say
  "back up to a file", never Google Drive. Drive backup stays in the website APK (manotu.app).
