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

- STAGE-013B marcowki model correction — **korekta zakończona, brama wizualna
  OWNER-a otwarta ponownie.** Odpowiedź na cztery uwagi z pierwszego przeglądu.

  **Ciągłość modelu.** Każdy stan widoczności jest podzbiorem jednego
  kanonicznego `MarcowkiVisualModelV1.geometry`, a warstwa zdjęta jest nadal
  rysowana — jako przygaszona siatka bez testu głębi. Dach zdjęty z domu wisi
  nad nim jako szkielet, więc bryła nie zmienia się między kadrami, zmienia się
  tylko to, ile z niej jest lite. Zbiór usunięty liczony jako dopełnienie
  odpowiedzi domeny, nie jako druga reguła w rendererze.

  **Otwory.** Dwanaście otworów z wykazu stolarki na obu rzutach: 470/230,
  300/230, 110/230, 105/210, 100/210, 90/230, 275/225, 140/140 na parterze,
  2 × 234/303 i 270/320 w szczytach, plus jedne nieopisane drzwi garaż–kotłownia
  odmierzone z hatchu. Wszystkie skonfrontowane z lukami w hatchu i zgodne co do
  2 cm. Dziura jest cięta w ścianie (`WallOpening`), skrzydło jest osobnym
  elementem `WINDOW`/`DOOR` z własną szybą (`OpeningPanelGeometry`). Nadproża
  przeszkleń szczytowych biegną po połaci — podana wysokość jest tam maksimum,
  bo inaczej otwór wychodziłby przez dach. Trzy okna połaciowe 78/118
  odrysowane co do pozycji, ale rysowane **na** połaci, nie wycinane w niej.

  **Schody.** Element `STAIRS` na parterze, bez przypisanego pomieszczenia (na
  parterze źródło żadnego nie nazywa). Trzy biegi wokół prostokątnego trzonu,
  17 stopni po 0,18 m między dwoma podanymi poziomami. Kierunek ustalony
  strzałką rzutu poddasza, nie zgadnięty. Strop nad parterem pocięty wokół
  klatki, więc bieg nie przechodzi przez litą płytę.

  **Bryła zewnętrzna.** Główna korekta: **podcienie szczytowe**. Ściany
  okapowe biegną metr za każdy szczyt na obu kondygnacjach (odrysowane z hatchu
  obu rzutów), tworząc portal z policzkami, balkonem na poddaszu i cofniętym
  przeszkleniem. Do tego balustrady balkonów, zadaszenie przed garażem
  i podesty w podcieniach.

  **Prezentacja.** Do każdej siatki pieczony drugi bufor indeksów — kontur
  wszystkich krawędzi, każda raz — rysowany osobnym materiałem `UNLIT` nad
  bryłą odsuniętą `setPolygonOffset`. Szyby ciemniejsze i chłodniejsze od muru,
  kolor po typie prymitywu, nie po `BuildingElementKind`. Presetów siedem
  (doszły `FACADE_OPENINGS` i `STAIRS_VIEW`).

  47 elementów i 89 prymitywów (30 ścian z balustradami, 28 płyt z 17 stopniami,
  2 połacie, 14 paneli szczytowych, 15 szyb). Wiarygodność: `SOURCE_EXACT` 22,
  `SOURCE_TRACED` 4 nazwane cechy + 71 linii siatki, `DISPLAY_ASSUMPTION` 12 —
  każde założenie z uzasadnieniem i sprawdzane testem `M013-18`.
  Renderer bez zmian: Filament 1.75.1, `debugImplementation`, release nadal bez
  `.so` Filamenta. W repozytorium nadal żadnego obrazu ARCHON — tym razem
  doszły cztery elewacje i dwie wizualizacje, wszystkie odmierzone poza worktree.

  **Czego to nadal nie jest:** dokumentacji budowlanej. Komina nie ma — widać go
  na wizualizacjach, ale żaden rzut nie rysuje szybu dającego się odróżnić od
  szaf kreskowanych tak samo, a komin postawiony z renderu byłby wymyśloną
  współrzędną. Materiałów elewacji nie ma. Drzwi wewnętrznych poza jednymi nie
  ma. Wszystko trzy zapisane w `MarcowkiSourceEvidence.notModelled`.

## Następny krok

GATE-3D-SHAPE-01 **ponownie** — OWNER porównuje poprawiony model z rzutami
i wizualizacjami ARCHON i decyduje, czy wierność jest wystarczająca. Dopiero po
tej ocenie STAGE-014 (izolacja pomieszczenia). Zieleń techniczna STAGE-013B
**nie jest** akceptacją wizualną.

## Czego nadal NIE ma

Produkcyjnej architektury renderera, izolacji pomieszczenia w UI, parsera rzutów,
wycinania otworów w połaci dachu, persystencji i danych rzeczywistych. Model
Marcówek jest odrysem walidacyjnym, a nie geometrią techniczną: ma otwory
i schody z wykazów i rzutów, ale nie ma komina, materiałów, konstrukcji ani
rzędnych konstrukcyjnych.

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
