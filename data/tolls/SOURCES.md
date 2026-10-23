# Malaysian toll tariff sources

All sources accessed 2026-10-23. Every fare in `my-toll-rates.csv` comes from one of these. No fare is estimated.

## Primary (official)
- Lembaga Lebuhraya Malaysia (LLM) toll calculator JSON API, the backend of the "Kalkulator Tol" on https://www.llm.gov.my/
  - Highways: `https://www.llm.gov.my/Calculator/getHighway`
  - Entry plazas: `https://www.llm.gov.my/Calculator/getPlazaEnter/{highwayId}`
  - Exit plazas: `https://www.llm.gov.my/Calculator/getPlazaExit/{highwayId}/{entryId}`
  - Fare: `https://www.llm.gov.my/Calculator/getTollRate/{highwayId}/{entryId}/{exitId|null}` (each CSV row links to its exact call)
  - Class codes: 0 = motorcycle, 1 = car, 2/3 = lorries, 4 = taxi, 5 = bus. For Penang Bridge (id 5) the codes shift: 1 = motorcycle, 2 = car.
- Grand Saga (Cheras–Kajang): https://grandsaga.com.my/about-cheras-kajang-highway/ (Batu 9 tolls Kajang-bound only, Batu 11 tolls KL-bound only, both RM1.30 class 1)

## Corroboration / effective dates
- autobuzz.my, 2015-10-13: LDP RM2.10, SPRINT Damansara RM2.00 / Pantai RM2.50 / Bukit Kiara RM3.00, effective 2015-10-15. https://autobuzz.my/2015/10/highways-see-hike-toll-rates-ldp-sprint-penchala-link-included/
- paultan.org, 2022-10-19: Prolintas cut (AKLEH RM2.13, GCE RM1.75, LKSA RM1.56/RM0.83, SILK RM1.66), effective 2022-10-20. https://paultan.org/2022/10/19/kadar-tol-lebuhraya-akleh-guthrie-lksa-dan-silk-turun-antara-8-15-mulai-20-okt-2022-prolintas/
- NST, 2022-12-31: Besraya RM1.85 from 2023-01-01; LEKAS Ampangan open-system rate unchanged. https://nst.com.my/amp/news/nation/2022/12/865914/updated-lekas-and-besraya-motorists-usher-new-year-cheaper-tolls
- paultan.org, 2023-12-08: SPE RM3.50, collection from 2023-12-03. https://paultan.org/2023/12/08/lebuhraya-setiawangsa-pantai-kadar-tol-mula-dilaksanakan-rm3-50-untuk-kereta-persendirian/
- carz.com.my: DASH RM2.30 from 2022-12-01. https://carz.com.my/2022/11/dash-toll-collection-starts-tomorrow-ensure-tng-credit-is-sufficient
- carz.com.my: SUKE Alam Damai RM2.30 from 2023-06-30. https://carz.com.my/2023/06/suke-expressway-phase-2-toll-collection-starts-today-rm230-for-cars
- Kosmo, 2026-08-06: LATAR Eco Grandeur RM1.15, collection from midnight. https://www.kosmo.com.my/2026/08/06/kutipan-tol-persimpangan-eco-grandeur-bermula-malam-ini/
- paultan.org, 2021-10-15: JKSB quotes RM5.74 class 1 on the Second Penang Bridge. https://paultan.org/2021/10/15/guna-jambatan-sultan-abdul-halim-muadzam-shah-jika-masuk-pulau-pinang-elak-kesesakan-jksb/
- Utusan, 2018-11-02: motorcycle tolls abolished on both Penang bridges and the Second Link from 2019-01-01. https://www.utusan.com.my/nasional/2018/11/tol-motosikal-mansuh-kenaikan-tol-dalam-bandar-beku/
- penang-traveltips.com: Penang bridges tolled mainland to island only. https://www.penang-traveltips.com/second-penang-bridge.htm
- NST, 2020-01-17: PLUS 18% cut (e.g. Jalan Duta to Skudai RM38.50). https://www.nst.com.my/amp/news/nation/2020/01/557347/toll-rates-reduction-plus-highways-starting-feb-1
- motorist.my, "List of Malaysia highway toll rates [2026 edition]" (updated 2026-05-22): cross-check of open-plaza class 1 rates. https://www.motorist.my/article/2829/list-of-malaysia-highway-toll-rates-plus-klk-lpt-and-others
