# Moderation word lists

Used by `Gimano.Core.Community.TextFilter` to reject community place names/descriptions and
review texts that contain profanity or slurs, in English (`en.txt`) and Malay (`ms.txt`).

- One entry per line; `#` starts a comment. Entries may be multi-word (`haram jadah`).
- Keep entries **unambiguous**: words that are also legitimate place-name or food words in
  Malaysia (e.g. *babi* in "Bak Kut Teh babi", *anjing* in a pet shop, *butt* in "Butterworth")
  are deliberately left out. A false positive blocks a real business name.
- Matching runs on a **normalised** form of the text, the same for the list and the input:
  1. Unicode NFKD, diacritics removed, lower-case.
  2. Common leetspeak/obfuscation folded: `0→o 1→i 3→e 4→a 5→s 7→t 8→b @→a $→s !→i`.
  3. Everything except letters becomes a space, so `f.u.c.k` becomes `f u c k`, which the
     joined-letters pass catches.
  4. Runs of the same letter are collapsed to one (`fuuuck → fuck`, `siaaal → sial`). The list is
     collapsed the same way.
  5. An entry matches a **whole token**, or a run of adjacent single letters joined together
     (for spaced-out spelling). Multi-word entries match token sequences. Substrings inside
     other words do not match: "Scunthorpe" and "Butterworth" are safe.
- Links (`http://`, `www.`, `something.com/.my/...`) are rejected in names and stripped from review texts.

Additions go through code review like any other change.
