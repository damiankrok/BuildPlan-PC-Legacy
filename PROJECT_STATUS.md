# Status projektu

## COMPLETE

- STAGE-001 shell — powłoka aplikacji Android (App Shell, nawigacja, ekrany
  placeholderowe)
- STAGE-002 domain model — kanoniczny model domenowy MVP
- STAGE-002A 3D domain alignment — `BuildingElement` ma jeden logiczny zakres
  (BUILDING/FLOOR) i relację 0..N do pomieszczeń; ściana dzielona to jeden
  element; czyste zapytania ukrywania kondygnacji/dachu i izolacji pomieszczenia;
  semantyka `Cost.amount` (brutto/zapłacone) i `Cost.stageId` (klasyfikacja)
- STAGE-010A reference structured building — jeden deterministyczny projekt
  referencyjny (dwie kondygnacje, 18 pomieszczeń, podane powierzchnie)
  w źródłach `debug`, wraz z proweniencją trzymaną poza domeną. Nie jest to
  prawda produktu ani dane użytkownika; release go nie kompiluje. Ten zbiór
  nadal nie ma geometrii i nie dostanie jej bez źródła rzutu.
- STAGE-011 minimal geometry contract — pakiet `geometry` (metry, Y w górę, rzut
  w XZ, współrzędne lokalne): `PlanPoint`, `ModelPoint`, `WallGeometry`,
  `SlabGeometry`, `RoofFacetGeometry`, `BuildingGeometry`, `LocalBounds`.
  Złączenie z domeną wyłącznie po `BuildingElementId`, jeden element może mieć
  wiele prymitywów (dach = dwie połacie), walidacja względem kanonicznego
  `Building`. Jeden most z semantycznej selekcji do geometrii —
  `primitivesOf(elements)` — reguły ukrywania i izolacji zostają w domenie.
  Syntetyczny dom demonstracyjny (`geometry/demo/SyntheticDemoHouse`) w źródłach
  `debug`; jego liczby są wymyślone i **nie** są rekonstrukcją projektu ARCHON+.

- STAGE-012 direct Filament renderer spike — syntetyczny dom demonstracyjny
  renderowany interaktywnie przez Google Filament
  (`com.google.android.filament:filament-android:1.75.1`, Apache-2.0), wyłącznie
  w źródłach `debug`. Udowodnione: wgranie geometrii na GPU, deterministyczne
  mapowanie `BuildingElementId` → encje (dach dwuspadowy = jedno id, dwie
  połacie), kadrowanie z `BuildingGeometry.bounds`, orbita/skala/przesuwanie,
  ukrycie dachu, ukrycie poddasza, ukrycie obu, przywrócenie, picking
  rozstrzygający dokładny `BuildingElementId` (tło = brak elementu) oraz
  stabilność po odtworzeniu Activity. Widoczność rozstrzyga nadal domena, most
  do geometrii to nadal `primitivesOf`; renderer nie ma własnej reguły.
  Wariant release jest bez zmian — nadal pokazuje zarezerwowany placeholder.
  **To kandydat na renderer, nie renderer produkcyjny.**
  Zgodność 16 KB sprawdzona na zbudowanym APK (wszystkie `LOAD` = 2^14,
  `zipalign -P 16` przechodzi, `GNU_RELRO` obecne) **i zweryfikowana w runtime**
  na lokalnym obrazie API 36 o stronie 16 KB — bez zastrzeżeń. Kontrole natywne
  trzeba powtarzać po każdej zmianie wersji renderera.

## Następny krok

STAGE-013 — produkcjonizacja renderera: docelowy host, skompilowany `.filamat`
zamiast kompilacji materiału na urządzeniu, izolacja pomieszczenia w UI
i strojenie gestów.

## Czego nadal NIE ma

Produkcyjnej architektury renderera, izolacji pomieszczenia w UI, parsera rzutów,
geometrii drzwi/okien/schodów, persystencji i danych rzeczywistych.

## OPEN

- persistence
- real project UI/data
- 3D renderer productionization (STAGE-013)
- costs UI
- domain model extensions (User, Document, Vendor, Contractor)
- authentication
- stages UI
- timeline
- statistics
- budget calculations (spent / remaining / overrun)
- cost allocation rules (STAGE-015)

## PARKED

- parser projektu
- OCR
- benchmarki
- PRO
- wykonawcy
- analiza rynku

## OWNER LATER TEST PACK

Ocena subiektywna, do wykonania przez właściciela produktu. Nie blokuje
technicznego PASS.

- ogólny feeling UI
- estetyka dark mode
- wygoda szuflady nawigacyjnej
- wielkości tekstu
- spacing
- ergonomia na telefonie trzymanym w jednej ręce
- wygląd Dashboardu i proporcje miejsca na model 3D
- zachowanie na realnym urządzeniu

## Do potwierdzenia przez OWNER-a

- `applicationId` `com.buildplan.app` jest decyzją roboczą i wymaga
  potwierdzenia przed publikacją w Google Play.
- Czy koszt może mieć kwotę ujemną (korekta / faktura korygująca). Model na
  razie tego nie ogranicza.
- Tryb firmowy: rozbicie kwoty na netto/VAT. W pierwszym MVP `Cost.amount` to
  kwota brutto, faktycznie zapłacona, i nie ma pól podatkowych.
- DEC-COST-ALLOCATION-001 (zasady podziału kosztów) pozostaje otwarta do
  STAGE-015. Obecny kształt `CostAllocation` jest prowizoryczny.
