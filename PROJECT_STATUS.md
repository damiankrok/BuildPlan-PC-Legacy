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

- STAGE-013C marcowki signature features — **korekta zakończona, brama wizualna
  OWNER-a otwarta ponownie.** Odpowiedź na drugi przegląd: model był poprawny
  wymiarowo i nadal nie był *tym* domem.

  **Cechy rozpoznawcze.** Dwie, obie odrysowane z czterech aktualnych elewacji
  (skalibrowanych na 25,38 px/m i zapisanych jako `ELEVATION_CALIBRATION`).
  *Rama podcienia szczytowego* — policzki plus dwa pasy biegnące po połaci,
  wszystko w płaszczyźnie czoła podcienia; wcześniej trójkąt nad podcieniem był
  otwarty i portal czytał się jak dziura. *Opaska międzykondygnacyjna* — jedna
  pozioma linia na +3,06 przez czoła obu balkonów i cały garaż. Obie mają własne
  identyfikatory i własne nazwy; rama ma zasięg `WholeBuilding` i zostaje
  w każdym stanie widoczności.

  **Garaż w kompozycji.** Stropodach garażu sięga teraz +3,06 zamiast 2,72, więc
  góra garażu i opaska domu są jedną linią — na elewacji frontowej to ten sam
  pas pikseli nad garażem i nad wejściem. Wysokość 0,54 m nie jest już
  założeniem wizualizacyjnym: to różnica między zwymiarowanymi 252 w garażu
  a podanym poziomem +3,06, więc oba końce pasa są poziomami ze źródła.

  **Siatka odniesienia.** `PresentationGrid` rysuje płaszczyznę rastrową pod
  modelem — metr i co piąta linia mocniejsza. Wyłącznie prezentacja: bez
  `BuildingElementId`, niewybieralna, nieukrywalna, nieznana w `geometry/`
  i `domain/`. To nie jest teren ani działka.

  **Kamera i sterowanie.** Presetów dziesięć (doszły `SIGNATURE_FACADE`,
  `GARAGE_RELATION`, `SITE_CONTEXT`). Dolny limit pochylenia podniesiony z −70°
  do −12°, bo pod modelem kamera gubiła orientację. Etykiety chipów
  jednowierszowe, rzędy z marginesem na końcach.

  50 elementów i 95 prymitywów (32 ściany, 28 płyt, 2 połacie, 18 paneli
  szczytowych, 15 szyb). Wiarygodność: `SOURCE_EXACT` 22, `SOURCE_TRACED`
  7 nazwanych cech + 73 linie siatki, `DISPLAY_ASSUMPTION` 11 — o jedno mniej,
  bo grubość stropodachu garażu przestała być zgadywana. Renderer bez zmian
  wersji: Filament 1.75.1, `debugImplementation`, release nadal bez `.so`
  Filamenta. W repozytorium nadal żadnego obrazu ARCHON — cztery elewacje
  odmierzone poza worktree i tam zostawione.

  **Czego to nadal nie jest:** dokumentacji budowlanej. Nie ma komina, materiałów
  elewacji, drzwi wewnętrznych poza jednymi ani grubości połaci — a więc i pasa
  podrynnowego, który obie elewacje boczne pokazują na 0,24 m wzdłuż obu
  długich fasad. Wszystko zapisane w `MarcowkiSourceEvidence.notModelled`.
  Analizatora nie ma: STAGE-013C spisał tylko jego kontrakt wejść i wyzwalaczy
  pytań w `ARCHITECTURE.md`.

- STAGE-013D marcowki fidelity audit + bounded autofix — **korekta zakończona,
  brama wizualna OWNER-a nadal otwarta.** Odpowiedź na trzeci przegląd (model
  z cechami rozpoznawczymi nadal nie czytał się mocno jako ten projekt) po
  zestawieniu z pakietem referencyjnym OWNER-a (`references/01`, poza gitem)
  i ponownym odczycie rzutów, elewacji i przekroju ARCHON.

  **Pętla samoaudytu.** AUDIT-0 (10/22) → CORRECTION-1 (8 zmian) → AUDIT-1
  (21/22). Warunek stopu spełniony po pierwszej rundzie; **użyto jednej rundy
  korekt z dwóch dozwolonych**, CORRECTION-2 nie było. Wynik techniczny:
  `PASS_STAGE_013D_READY_FOR_OWNER_REVIEW` — co nadal nie jest akceptacją
  OWNER-a.

  **Co się zmieniło.** Policzki podcieni 0,64 m z rzutu poddasza i ściany
  okapowe jako trzy pryzmy jednego elementu; rama tej samej szerokości, ze
  skosem w narożu i głębokością (lico, podniebienie, wierzch) na obu szczytach;
  balkon, opaska i balustrada południowa od x = 3,39 m, podcień otwarty na dwie
  kondygnacje na zachód od tej linii, szczelina w policzkach zamknięta stropem
  wewnątrz policzków; szkło techniczne (`buildGlass`, alfa 0,38) dla szyb
  i balustrad, z rolą prezentacji `VisualSurfaceRole` po identyfikatorze poza
  domeną; balustrady 2 cm, południowa ze skrętem na wolnym końcu; pas okapowy
  0,24 m jako osobny element `WholeBuilding` bez zmiany połaci; dwa kominy
  ponad dachem z trzech widoków; cztery presety dowodowe. 53 elementy, 119
  prymitywów (43 ściany, 33 płyty, 10 połaci — 2 dachu i 8 ramy, 18 paneli
  szczytowych, 15 szyb). Wiarygodność: `SOURCE_EXACT` 22, `SOURCE_TRACED`
  11 nazwanych cech + 85 linii siatki, `DISPLAY_ASSUMPTION` 13.

  **Guardraile.** `Stage013DFidelityTest` C013D-01…15: jeden model pod każdym
  stanem, ukrywanie jako podzbiór, ramy i opaska rozdzielne, zasięg balkonu
  w tolerancji odrysu, rola szkła poza domeną, szkło przezroczyste, dach bez
  zmiany powierzchni, siatka tylko w rendererze, schody i otwory z 013B,
  brak rastrów w repo (`references/` w `.gitignore`), Filament 1.75.1 bez
  SceneView, brak analizatora, tylko `emulator-5570`, bramy jakości bez zmian.

  **Ograniczenia znane.** Picking przechodzi przez szkło (pass pickingu
  Filamenta pomija powierzchnie blendowane — sprawdzone na urządzeniu
  z `depthWrite` wyłączonym i włączonym); pas okapowy przy jednym kolorze czyta
  się z reliefu 4 cm, subtelniej niż ciemny pas na elewacji; trzony kominowe pod
  dachem, grubość połaci, pochwyt balustrady i ramy okien nadal nie istnieją;
  rzut parteru kreskuje policzki na 0,48 m, model niesie 0,64 m z rzutu
  poddasza na obu kondygnacjach. Natywne zależności bez zmian — pełne badanie
  16 KB ze STAGE-012 nie było powtarzane, sprawdzono tylko inwentarz `.so`
  w APK.

- STAGE-013E marcowki decomposition, structural fidelity & technical render —
  **korekta zakończona, brama wizualna OWNER-a nadal otwarta.** Odpowiedź na
  czwarty przegląd (schody nie z rzutu, dziwne linie w pełnej bryle, „Bez
  dachu" z obwiednią dachu, brak drzwi wewnętrznych, prezentacja zbyt surowa).
  Trzy ograniczone fazy audyt→poprawka: **A** (8 poprawek: profil dekompozycji
  `presentation/DecompositionProfile` z grupą `ROOF_ENVELOPE` dla ram i pasa
  okapowego; wycofanie widmowej siatki i drugiego materiału liniowego;
  krawędzie cech zamiast szwów w `BuildingRenderMesh`; schody 4+2+5+2+4
  z zabiegami; przerwy na wejście i wyjście schodów w dwóch ściankach; ścianka
  korytarz–pokój 3; dwanaście drzwi wewnętrznych z łuków na rzutach; ścianki
  poddasza cięte na linii stropu 2,66), **B** (rubryka I1–I12: przed 9/24, po
  22/24; eksperyment CLAY vs LINE_STUDY na urządzeniu, wybrany CLAY; jedna
  poprawka: odszumienie SSAO), **C** (ruch: jedna poprawka — kamera nie schodzi
  pod siatkę). Wynik techniczny:
  `PASS_STAGE_013E_READY_FOR_OWNER_REVIEW` — nie jest akceptacją OWNER-a.
  65 elementów, 139 prymitywów (51 ścian, 33 płyty, 10 połaci, 18 paneli,
  27 tafli). Wiarygodność: `SOURCE_EXACT` 22, `SOURCE_TRACED` 12 nazwanych
  cech + 109 linii siatki, `DISPLAY_ASSUMPTION` 14. Guardraile
  `Stage013EDecompositionTest` C013E-01…14; C013D-02 i M013-12 świadomie
  zaktualizowane (profil prezentacji, liczności). Filament nadal 1.75.1,
  `debugImplementation`, bez zmian natywnych. Dowody na `emulator-5570`
  poza repozytorium. Czas etapu przekroczył politykę 30 min (ok. 60 min
  łącznie z dowodami) — odnotowane, bez dodatkowej pętli.

- STAGE-013F modular monochrome roof tiles — **PASS techniczny, brama
  wizualna OWNER-a nadal otwarta.** Zlecone przez OWNER-a przed zamknięciem
  `GATE-3D-SHAPE-01-R5`: warstwa pokrycia dachu jako prezentacja, jeden styl
  `CURVED_TILE`. Kanoniczny dach bez zmian (dwie połacie, 150,4 m²); pokrycie
  to `presentation/RoofCover.kt` (`RoofCoverStyle`, `RoofCoverSpec`,
  `RoofCoverBlocker`, `RoofCoverProfile` obok modelu referencyjnego jak role
  szkła i dekompozycja) plus generator `render/filament/RoofCoverMesh.kt`
  ogólny względem połaci (baza z normalnej i najstromszego wzniesienia,
  rzędy od okapu do kalenicy, przycinanie do obrysu; sprawdzony na dachu
  syntetycznym z kalenicą wzdłuż X, trójkącie kopertowym, pulpicie obróconym
  o 30° i połaci płaskiej). Dachówka: 10 wierzchołków, 8 trójkątów, beczkowe
  wybrzuszenie, zaokrąglony ogon, przechył (głowa 0,01 m, ogon 0,03 m nad
  połacią), rzędy wyrównane. Marcówki: 2 encje (po jednej na połać),
  2016 dachówek, 20 160 wierzchołków, 15 984 trójkątów; kominy i okna
  połaciowe jako blokery z `LocalBounds` własnych prymitywów (+0,03 m), bez
  powtórzonych współrzędnych. Partia odpowiada identyfikatorem dachu, więc
  „Bez dachu" usuwa ją tą samą ścieżką bez żadnej nowej reguły; dotknięcie
  dachówki wybiera dach. Bez konturu na dachówkach, bez tekstury, ten sam
  materiał, kolor `RenderStyle.roofCover` o stopień ciemniejszy. Szew pod
  animację: `RoofCoverTile` (rząd, kolumna, porządek, faza) i sygnał `UV0`
  na wierzchołek — nic go dziś nie czyta. Nowy preset „Pokrycie dachu".
  Wiarygodność: `DISPLAY_ASSUMPTION` 15 (nowy „Moduł pokrycia dachu").
  Testy `RoofCoverMeshTest` TILE013F-01…12 (178 testów zielonych), lint
  i `assembleDebug` zielone; release bez zmian. Samoaudyt A1–A12: **23/24**
  po pierwszej implementacji (A7 = 1: kalenica bez gąsiora), korekta
  uznana za niepotrzebną, re-audyt `NOT_NEEDED`. Dowody na `emulator-5570`
  poza repozytorium (`D:\TRAVELAPPS\_stage013f_roof_tiles_evidence`).
  Odnotowane: w trakcie dowodów kompozytor emulatora zawiesił się po
  dotknięciach w strefie gestu nawigacji (stare warstwy zostały po
  `force-stop`); po `adb -s emulator-5570 reboot` cykl tło→pierwszy plan
  aplikacji przeszedł bez zastrzeżeń, więc nie jest to regresja renderera.

- STAGE-013G marcowki windows/stairs + modern shell — **PASS techniczny,
  brama wizualna OWNER-a nadal otwarta.** Odpowiedź na piąty przegląd
  (model „w miarę w porządku", ale przeszklenia jak dziury i schody
  w ścianie; powłoka nieakceptowalna). Dwie równorzędne części.

  **Model.** *Schody:* rzut parteru rysuje spiżarnię pod górnym biegiem —
  ściany istnieją w źródle i zostają, ale kończą się pod biegiem: ścianki
  parteru cięte na krawędziach stopni do spodu najniższego stopnia nad
  nimi minus 0,02 m (`DISPLAY_ASSUMPTION` „Ścianki pod biegiem schodów"),
  odcinki krótsze od grubości ścianki wchłaniane przez sąsiada. Trzy
  krawędzie klatki (7,47 / 6,46 / 6,18) wyprowadzone z lic ścian, na których
  leżą (7,45 / 6,47 / 6,16); licznik odrysu o trzy mniejszy. Guard `G013-01`:
  żaden pryzmat ściany nie przecina żadnego stopnia. *Okna:* ramy i słupki
  jako prezentacja obok modelu (`presentation/OpeningFrame.kt`,
  `render/filament/OpeningFrameMesh.kt`, generator ogólny względem tafli,
  skos w narożach, słupki co ≤ 1,20 m, brama bez skrzydeł, drzwi wewnętrzne
  bez ram); 14 siatek ram (11 otworów elewacyjnych + 3 okna połaciowe)
  z id tafli, lite, konturowane, pickowalne; kanoniczne tafle, dziury
  i ściany bez zmian (`G013-03..05`). 65 elementów, 140 prymitywów
  (52 ściany, 33 płyty, 10 połaci, 18 paneli, 27 tafli). Wiarygodność:
  `SOURCE_EXACT` 22, `SOURCE_TRACED` 12 cech + 106 linii siatki,
  `DISPLAY_ASSUMPTION` 17.

  **Powłoka.** Start = nagłówek projektu + model jako bohater na pozostałej
  wysokości (`ViewportMode.HERO`, dotknięcie otwiera model; release: rycina)
  + pasek osi czasu (pusty tor jako kształt) + jeden pasek pieniędzy
  z jedną akcją; cztery puste karty KPI i `MetricCard` usunięte. Szuflada
  pogrupowana (Projekt / Finanse / Zasoby, Ustawienia na dole; „Dashboard"
  → „Start"). Ekran modelu pełnoekranowy z dokiem: segmentowany stan
  widoczności, przewijane ujęcia, segmentowany styl, kamera, zwijane
  szczegóły (źródło modelu, gest, panel wiarygodności), pigułka wyboru nad
  viewportem. Kamera: pole widzenia po krótszym boku viewportu, marginesy
  presetów przekalibrowane; okładka viewportu do pierwszej klatki po
  kompilacji programów (`Material.compile` + `onReady`, zabezpieczenie 15 s).
  Audyt Mobbin: sześć zapytań, zasady (obiekt-bohater, pasek statusu zamiast
  siatki, szuflada z grupami, pusty stan zachowujący kształt, tor postępu
  pod bohaterem), bez kopiowania. Testy 183 zielone (`Stage013GCorrectionTest`
  G013-01..05, `AppSectionTest` grupy), lint i `assembleDebug`/`assembleRelease`
  zielone; Filament nadal 1.75.1, bez zmian natywnych. Dowody na
  `emulator-5570` poza repozytorium (`D:\TRAVELAPPS\_stage013g_evidence`).
  Samoaudyt: model A1–A5 5/5, UI B1–B8 po jednej poprawce 8/8, łączny
  `PASS_STAGE_013G_READY_FOR_OWNER_REVIEW` — nie jest akceptacją OWNER-a.
  Powłoka z tego etapu została przez OWNER-a **odrzucona** (model w karcie,
  ekran z prostokątnych bloków) i zastąpiona w STAGE-013H.

- STAGE-013H immersive 3D workspace + multi-skill UI/motion audit — **PASS
  techniczny, brama wizualna OWNER-a otwarta.** Odpowiedź na odrzucenie
  powłoki ze STAGE-013G (model w karcie, ekran z prostokątnych bloków).

  **Kompozycja.** Sekcje Start i Model 3D scalone w jedną przestrzeń
  roboczą `AppSection.Home` („Dom"): kanwa Filamenta od krawędzi do
  krawędzi, bez karty, kształtu i marginesu; szklana pigułka z szufladą
  i nazwą projektu u góry; pionowa szyna pięciu narzędzi na prawej krawędzi
  (Warstwy, Ujęcia, Styl, Wyśrodkuj kamerę, Źródło modelu) z panelem
  rozwijanym obok szyny; oś czasu jako szkło kotwiczące dolną krawędź,
  zwinięta do jednego wiersza i rozwijana do dwóch zdań z akcjami;
  inspektor wybranego elementu w lewym dolnym rogu. Jeden otwarty element
  chromu naraz (`WorkspaceChromeState`), gest wstecz zamyka go, potem
  zwalnia wybór, potem opuszcza ekran. Jeden silnik Filamenta zamiast dwóch.
  Release: ta sama przestrzeń z zarezerwowaną ryciną, bez szyny.

  **Szkło i ruch.** `GlassSurface`: tint 0,84 + obwódka, bez rozmycia (Filament
  na `SurfaceView` nie da się próbkować), lite dla dotyku, bez nowej
  zależności. `MotionPolicy`: 220/150/180 ms, kamera do 300 ms skalowana
  wielkością ruchu, ease-out na wyjściach, ease-in-out na kamerze; „Usuń
  animacje" (`ANIMATOR_DURATION_SCALE` = 0) obserwowane na żywo i honorowane
  cięciem — także w szufladzie, `NavHost` i wskaźniku wczytywania. Ujęcia
  pogrupowane (Bryła / Wnętrze / Elewacje / Detale); podróż kamery do ujęcia
  interpolowana i przerywalna palcem.

  **AUDIT-1 Impeccable** (niezależny agent, `audit android` + krytyka):
  przed 13/20 i heurystyki 18/32, 5 × P1 (dotyk przez szkło do kanwy, cele
  40/44 dp, kontrast ~3,5:1 na szkle, pięć fałszywych znaczników na osi,
  15 ujęć bez grup z „Bez dachu" w dwóch znaczeniach). Po: wszystkie P1
  i 12 P2 poprawione (m.in. wstecz zwalnia wybór, podpowiedzi glifów,
  `Ellipsis`, grupa TalkBack, jasne ikony pasków systemowych, jedno zdanie
  zamiast trzech kresek, komunikat wczytywania); zweryfikowane na
  urządzeniu: dotknięcie tytułu panelu nie zmienia wyboru, znacznik ujęcia
  znika po zmianie warstwy, skala czcionki 1,3 bez ucięć.

  **AUDIT-2 Emil Kowalski** (niezależny agent, `emil-design-eng`,
  `review-animations`, `improve-animations`): 14 pozycji w inwentarzu ruchu,
  0 × P0, 5 × P1 (dwie tafle nakładające się przy zmianie narzędzia,
  szewron i szczegół osi na dwóch zegarach, inspektor rosnący z narożnika
  ekranu zamiast ku dotkniętemu elementowi, ease-in na każdym wyjściu,
  700 ms crossfade `NavHost` poza polityką). Po: jedna tafla z crossfade
  treści i animowaną wysokością, pivot na wciśniętym przycisku szyny;
  szewron i szczegół na jednym `updateTransition`; inspektor pivotuje na
  punkcie dotknięcia w swoich granicach i gaśnie pod rozwiniętą osią;
  `exit()` = ease-out; `NavHost` na polityce; P2: dystans kamery mieszany
  geometrycznie, krzywa ease-in-out i czas 150–300 ms wg wielkości ruchu,
  halo szyny od 0,7 zamiast od zera, szuflada `snapTo` i statyczny pasek
  postępu pod „Usuń animacje". Świadomie zostawione: przeciągnięcie osi
  rozstrzygane na końcu gestu (bez śledzenia palca), twarde cięcia
  renderera przy zmianie warstwy i wyboru (poprawne jako feedback).

  **AUDIT-3 (weryfikacja obu soczewek po poprawkach, niezależny agent):**
  wszystkie 10 P1 z obu audytów potwierdzone jako naprawione (w tym idiom
  hit-testu szkła, kontrast 5,6:1, przestrzenie współrzędnych pivota
  inspektora); 0 × P0/P1; Impeccable po: 14/20 (Good), heurystyki 26/32.
  Sześć P2, z czego cztery poprawione w tej samej pętli: pivot panelu
  liczony w `graphicsLayer` z rozmiaru bieżącej klatki (bez skoku o klatkę
  i bez rekompozycji co klatkę), granice inspektora pamiętane przez cykl osi
  czasu, „Elewacje" → „Otwory elewacji" (grupa i opcja miały jedno słowo),
  znacznik ujęcia czyszczony po ręcznym obrocie kamery. Zostają jako dług:
  przeciągnięcie i dotknięcie scrimu szuflady animują pod „Usuń animacje"
  (ruch własny Material 3; wyłączenie gestów odebrałoby zamykanie scrimem),
  4 dp między przyciskami szyny. Do tego jedna poprawka poza listą: Material 3
  `ModalNavigationDrawer` nie zamykał się gestem wstecz i aplikacja wychodziła
  z otwartą szufladą — własny `BackHandler` w treści szuflady.

  Narzędzia: `frontend-design@claude-plugins-official` w zakresie projektu
  (`.claude/settings.json`), Impeccable 4.1.1 i skille Emila z `~/.claude/skills`,
  Mobbin MCP (4 zapytania: viewer 3D z narzędziami na krawędzi, pionowa szyna
  nad kanwą, dolny arkusz z torem nad mapą, pływający inspektor sceny 3D).
  Testy 195 zielone (`WorkspaceChromeStateTest`, `MotionPolicyTest` nowe),
  lint, `assembleDebug` i `assembleRelease` zielone; release bez `.so`
  Filamenta; Filament nadal 1.75.1, bez zmian natywnych. Model bez zmian
  (okna, schody, dachówki, „Bez dachu", „Bez poddasza", picking — te same
  ścieżki, tylko wywołane z szyny). Dowody na `emulator-5570` poza
  repozytorium (`D:\TRAVELAPPS\_stage013h_evidence\{baseline,first_pass,
  audit1,audit2,final}`, z klipami `screenrecord` dla przejść, których
  `screencap` nie łapie). Wynik: `PASS_STAGE_013H_READY_FOR_OWNER_REVIEW` —
  nie jest akceptacją OWNER-a.

- STAGE-023A multi-project analyzer prototype — **PARTIAL** (prototyp
  badawczy, nie produkt). Nowy czysto-JVM moduł `analyzer/` (jsoup 1.23.2)
  i debugowy **Analyzer Lab** (osobny wpis launchera w wariancie debug,
  uprawnienie `INTERNET` tylko debug): adres strony ARCHON → bezpieczne
  pobranie (allowlista hostów, rewalidacja przekierowań, blokada adresów
  prywatnych, limit 8 MB, bez crawlowania) → adapter witryny (fakty
  `SOURCE_EXACT` z proweniencją, tabele pomieszczeń, role rysunków, benchmarki
  kosztów) → pobranie rysunków do pamięci aplikacji (nigdy do repo) →
  analiza rzutu (tusz/struktura/kawałki ścian/przerwy/zewnętrze/obrys/
  regiony, dwa przebiegi, progi w metrach) → kalibracja po powierzchni
  zabudowy (pomieszczenia tylko sprawdzeniem) → dopasowanie pomieszczeń po
  powierzchni z jawną niejednoznacznością → szkielet prostoliniowy dachu
  (dwuspadowy i kopertowy jednym solverem, masy wtórne płaskie) → łańcuch
  pionowy z bramką wiarygodności → kandydat (kondygnacje, pomieszczenia
  z obwodem po odcinkach, ściany w pięciu klasach, otwory z `MISSING`
  wysokością, schody) → przedmiar (lica pomieszczeń osobno od ścian
  konstrukcyjnych, sufit płaski/skosy/użytkowa wg reguły wysokości,
  kubatura, połacie, kalenice, okapy, stolarka, elewacja) → porównanie ze
  stroną → braki, kompletność i pytania → deterministyczna migawka JSON
  (`Locale.ROOT`, obieg bajt w bajt). Żadnej zdalnej AI, żadnego OCR, żadnego
  tokenu konkretnego projektu w rdzeniu (`AnalyzerPurityTest`); kandydat nie
  dotyka `domain/` ani modelu Marcówek.

  **Projekt A** (`…-marcowkach-ge-m2fa281446a8ca`, dwuspadowy 40°, kolankowa
  130): adres bezpośredni; 24 fakty; oba rzuty 38,21 px/m `SOURCE_DERIVED`
  (residua 1,8 % i 0,8 %); 15 z 18 pomieszczeń z wielokątem (bez wiatrołapu,
  jednego pokoju i schodów); dach GABLE 148,3 m² vs 150,57 (−1,5 %); kalenica
  7,97, okap 4,68, strop poddasza 2,98 (źródło +3,06), parter 2,68 w świetle —
  wszystko `DISPLAY_ASSUMPTION`, bo teren i płyta są założone; porównanie:
  12 × STRONG, 10 × ACCEPTABLE, 2 × MISMATCH (suma pomieszczeń poddasza przez
  3 niedopasowane; elewacja brutto 148,6 vs 225,9); kompletność 65 %,
  14 pytań. **Projekt B** (adres OWNER-a z nieaktualnym sufiksem `…to`
  → przekierowanie → katalog → rdzeń sluga → strona kanoniczna
  `…-md6a1fa1493ad8`, każdy krok w migawce; kopertowy 38°, kolankowa 98,
  garaż dwustanowiskowy): 25 faktów; parter 36,60 px/m `TRACE_UNCERTAIN`
  (6,9 %), poddasze `CONFLICTING` (14,8 %) i tak zgłoszone; 14 z 20
  pomieszczeń; dach HIP, 10 połaci, 271,7 m² vs 246,7 (+10,2 %); okap
  odmierzony 1,09 m; strop poddasza 2,88, parter 2,58 (założenia z pytaniem);
  porównanie: 2 × STRONG, 15 × ACCEPTABLE, 6 × MISMATCH (sumy pomieszczeń,
  działowe, podłogi, elewacja); kompletność 63 %, 14 pytań. Oba biegi
  odtwarzalne z bufora źródeł poza repozytorium
  (`BUILDPLAN_ANALYZER_EVIDENCE_DIR=D:\TRAVELAPPS\_stage023a_evidence`),
  oba uruchomione też na żywo w Lab na `emulator-5570` (zrzuty w `device/`,
  migawki JSON w pamięci aplikacji). Uruchomienie na urządzeniu wykryło błąd,
  którego JVM nie pokazywała (nieucieczkowany `}` w wyrażeniu regularnym,
  odrzucany przez silnik Androida) — naprawiony.

  Iteracje (8, bez dziewiątej): 1 potok i adapter; 2 tusz pod znakiem
  wodnym, cienkie okna, tracer konturu; 3 przesegmentowanie i matcher grup;
  4 szkielet dachu (szczyty, podziały zerowe, wnęki, remis orientacji);
  5 łańcuch pionowy z okapem; 6 audyt generalizacji (test czystości,
  brak stałych pikselowych); 7 audyt przedmiaru i proweniencji (pytania
  o otwarte plany z nazwami pomieszczeń, otwór zewnętrzny tylko z zewnętrzem
  po jednej stronie, bramka wiarygodności pionu, klasy `LIGHT_EXTERIOR`
  i `PIER`, kreska obrysu pasa dachu przepuszczalna dla zalania, kawałki
  z zewnętrzem po obu stronach odrzucone, krótkie grube kawałki ścian od
  0,15 m); 8 regresja obu domów po każdej poprawce, dokumentacja.
  Testy `:analyzer:test` zielone: 35 offline i 8 ewaluacyjnych opt-in, lint,
  `testDebugUnitTest`, `assembleDebug` i `assembleRelease` zielone; release
  bez Lab, bez `INTERNET`, bez modułu analizatora i jsoup
  (`debugImplementation`), bez nowych `.so` (12 bibliotek Filamenta w debug
  bez zmian). Trzy strażnicze testy STAGE-013C/D/E („analizator nie może
  jeszcze istnieć") przestawione na nową granicę: rdzeń tylko w `:analyzer`,
  w aplikacji tylko `analyzer/lab/` i szew `SceneModel`, OCR/wizja/ML nadal
  zakazane. Znane ograniczenia w `ARCHITECTURE.md` (skrzydło poddasza B,
  schody 0 stref, wysokości otworów `MISSING`, elewacja brutto
  nieporównywalna). Wynik: `PARTIAL_STAGE_023A_MULTI_PROJECT_ANALYZER` —
  prototyp do oceny OWNER-a, nie funkcja produktu.
## Następny krok

GATE-3D-SHAPE-01-R7 (`RETEST_PENDING`) — OWNER ocenia immersyjną przestrzeń
roboczą ze STAGE-013H: dom jako kanwa ekranu bez karty, chrom przy krawędziach
(pigułka, szyna narzędzi, oś czasu), panele kontekstowe zamiast stałych
bloków, szkło i ruch; do tego nadal dwa warunki ze STAGE-013F (okna z ramami,
schody bez kolizji ze ścianą — bez zmian od STAGE-013G). Dopiero po tej
ocenie STAGE-014 (izolacja pomieszczenia). Zieleń techniczna STAGE-013H
**nie jest** akceptacją wizualną. Równolegle OWNER ocenia prototyp
analizatora ze STAGE-023A w debugowym Lab (oba projekty, pytania,
podgląd 3D kandydata); STAGE-023B, STAGE-024 ani STAGE-014 nie startują
automatycznie — wracają do koordynatora.

## Czego nadal NIE ma

Produkcyjnej architektury renderera, klas rozmiaru okna (tablet, poziom)
w przestrzeni roboczej, prawdziwego rozmycia tła pod szkłem, śledzenia palca
przy przeciąganiu osi czasu, izolacji pomieszczenia w UI, **produktowego
analizatora projektów** (STAGE-023A to prototyp badawczy w `analyzer/`
i debugowym Lab: bez wejścia w produkcie, bez odczytu tekstu z rysunków,
bez przejścia kandydata do modelu), wycinania otworów w połaci dachu, grubości połaci, gąsiora
na kalenicy, drugiego stylu pokrycia dachu, animacji dachówek, pickingu przez
szkło, profilu ramy okiennej i pochwytu balustrady, danych na osi czasu
i w księdze kosztów, ikon w szufladzie, persystencji i danych rzeczywistych.
Model Marcówek jest odrysem walidacyjnym, a nie geometrią techniczną: ma
otwory z ramami, schody, cechy rozpoznawcze elewacji, pas okapowy i kominy
ponad dachem z wykazów, rzutów, elewacji i przekroju, ale nie ma trzonów
kominowych, materiałów, konstrukcji ani rzędnych konstrukcyjnych.

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

- parser projektu (prototyp badawczy ze STAGE-023A istnieje; produktyzacja
  i drugi adapter witryny zaparkowane)
- OCR
- benchmarki
- PRO
- wykonawcy
- analiza rynku

## OWNER LATER TEST PACK

Ocena subiektywna, do wykonania przez właściciela produktu. Nie blokuje
technicznego PASS.

- ogólny feeling UI (immersyjna przestrzeń robocza ze STAGE-013H — do oceny)
- estetyka dark mode
- wygoda szuflady nawigacyjnej (grupy od STAGE-013G, wejście z pigułki od
  STAGE-013H — do oceny)
- wielkości tekstu
- spacing
- ergonomia na telefonie trzymanym w jednej ręce
- wygląd Startu: dom jako kanwa od krawędzi do krawędzi, szkło, szyna
  narzędzi, oś czasu (STAGE-013H — do oceny)
- czytelność szkła na jasnym tle studyjnym i w stylu liniowym
- czas do pierwszej klatki modelu na realnym urządzeniu (na emulatorze
  kilka sekund pod okładką)
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
