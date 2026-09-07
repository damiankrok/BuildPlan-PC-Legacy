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

- STAGE-013 marcowki 3d reference model — **PASS techniczny, brama wizualna
  OWNER-a otwarta.** Pierwszy model o kształcie pochodzącym ze źródła:
  `reference/visual/MarcowkiVisualModelV1` w źródłach `debug`, odrysowany
  z aktualnej strony ARCHON „Dom w marcówkach (GE)" i jej aktualnych rzutów oraz
  przekroju. 32 elementy budynku i 42 prymitywy (28 ścian, 4 płyty, 2 połacie
  dachu, 8 paneli szczytowych i skosów) na kanonicznych identyfikatorach
  `MarcowkiReferenceProject` (kanoniczny zbiór **nie został zmieniony** — nadal
  nie ma geometrii), plus strefy rzutu dla wszystkich 18 pomieszczeń.
  Każda nietrywialna liczba ma etykietę `SOURCE_EXACT` (19) / `SOURCE_TRACED`
  (26) / `DISPLAY_ASSUMPTION` (4); dwie strefy otwartej kuchni i holu są
  oznaczone `TRACE_UNCERTAIN`, bo źródło nie rysuje między nimi granicy.
  Zgodności niezależne: 8,27 m wysokości = +7,95 kalenicy nad −0,32 terenu;
  150,57 m² dachu odtworzone przy 40° i odrysowanym wysięgu szczytowym 1,00 m
  (co ustaliło kierunek kalenicy); 131,16 m² zabudowy wobec 130,6 m² z dwóch
  odrysowanych prostokątów.
  Renderer bez zmian — bezpośredni Filament 1.75.1, `debugImplementation`,
  wariant release nadal pokazuje placeholder. Host debugowy dostał przełącznik
  `Marcówki / Syntetyczny` (Marcówki domyślnie), pięć deterministycznych
  presetów widoku i deweloperski panel wiarygodności. Syntetyczny dom zostaje
  jako fikstura regresyjna renderera.
  **Czego to nie jest:** dokumentacji budowlanej ani modelu technicznego. Otworów
  (drzwi, okna) nie ma — to jawny dług wierności. Strefy pomieszczeń poddasza są
  systematycznie 20–25 % większe od podanych, bo źródło podaje powierzchnię
  użytkową po odjęciu skosów; to różnica dachu, a nie błąd odrysu.
  W repozytorium nie ma żadnego obrazu ani HTML-a ARCHON — materiał źródłowy był
  przejściowy, poza worktree.

## Następny krok

GATE-3D-SHAPE-01 — OWNER porównuje model z rzutami ARCHON i decyduje, czy odrys
jest wystarczająco wierny. Dopiero po tej ocenie STAGE-014 (izolacja
pomieszczenia). PASS techniczny STAGE-013 **nie jest** akceptacją wizualną.

## Czego nadal NIE ma

Produkcyjnej architektury renderera, izolacji pomieszczenia w UI, parsera rzutów,
geometrii drzwi/okien/schodów, persystencji i danych rzeczywistych. Model
Marcówek jest odrysem walidacyjnym, a nie geometrią techniczną: nie ma otworów,
schodów, kominów ani rzędnych konstrukcyjnych.

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
