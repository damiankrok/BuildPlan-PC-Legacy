# Architektura

Dokument opisuje **wyłącznie decyzje faktycznie podjęte**. Nie jest planem
docelowej architektury.

## Stan na dziś

Jedna natywna aplikacja Android: powłoka UI (STAGE-001), kanoniczna warstwa
domenowa (STAGE-002, korekta własności elementów budynku w STAGE-002A) oraz
kontrakt geometrii budynku (STAGE-011). Do tego dwa zbiory danych w źródłach
`debug`: referencyjny projekt bez geometrii (STAGE-010A) i syntetyczny dom
demonstracyjny z geometrią (STAGE-011). Do tego **spike renderera na Google
Filament** (STAGE-012) — wyłącznie w źródłach `debug`, jako dowód wykonalności
i podstawa wyboru technologii, nie jako docelowy renderer produkcyjny. Na tym
rendererze stoi **model wizualny „Dom w marcówkach (GE)" V1** (STAGE-013,
poprawiany w STAGE-013B/C/D/E):
pierwsza geometria odrysowana z aktualnych rzutów ARCHON, z jawną klasyfikacją
wiarygodności każdej liczby, również w źródłach `debug` i przeznaczona do
wizualnej weryfikacji przez OWNER-a. Nad nim modułowe pokrycie dachu
(STAGE-013F) — prezentacja generowana z połaci, bez zmiany kanonicznego dachu.
Powłoka aplikacji jest od STAGE-013H **immersyjną przestrzenią roboczą**:
dom rysowany od krawędzi do krawędzi jako kanwa ekranu startowego, a chrom
(szklana pigułka, szyna narzędzi, oś czasu, inspektor) przy krawędziach
i na żądanie.
Obok tego **analizator projektów** (`analyzer/`, STAGE-023A/B/C) z produktowym
wejściem od STAGE-024 i — od STAGE-025 — **weryfikacją kandydata przez
człowieka**: nakładka decyzji nad niezmienną migawką analizy, pytania o korzenie
zamiast o wiersze przedmiaru, deterministyczne przeliczenie oraz pierwszy odczyt
elewacji i wizualizacji jako dowodu. Kandydat nadal nie zapisuje nic do domeny.
Brak backendu, persystencji, autoryzacji i wyceny.

## Decyzje

### Platforma i stack

- **Android native.** Nie Flutter, nie React Native, nie Compose Multiplatform.
- **Kotlin** jako jedyny język produkcyjny.
- **Jetpack Compose** jako warstwa UI, bez layoutów XML poza motywem systemowym.
- **Material 3** jako baza wizualna.
- **Navigation Compose** — jeden `NavHost`, jedna trasa na sekcję.
- **Gradle Kotlin DSL** + version catalog (`gradle/libs.versions.toml`).
- Jedna aplikacja, jeden moduł Gradle (`:app`). Podział na moduły nastąpi
  dopiero wtedy, gdy będzie realny powód.

### Wersje

Wersje przypięto jawnie do stabilnych wydań zgodnych z lokalnym środowiskiem
(AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.20, Compose UI 1.10.2, Material3 1.4.0).
**Nie użyto Compose BOM** — wersje Compose są przypięte bezpośrednio w version
catalogu, żeby build był deterministyczny. Lint zgłasza dostępność nowszych
wydań; aktualizacja to osobna, świadoma decyzja.

### Core library desugaring

Domena używa `java.time.LocalDate`, które w platformie jest dostępne dopiero od
API 26, a `minSdk` wynosi 24. Włączono `isCoreLibraryDesugaringEnabled` wraz
z `com.android.tools:desugar_jdk_libs`. Alternatywą było podniesienie `minSdk`
do 26, czyli porzucenie Androida 7 — to decyzja produktowa, nie techniczna,
więc jej nie podejmowano.

### Minimalne zależności

Poza powyższymi nie dodano nic: brak Room, Retrofit, Ktor, Hilt, Koin, Firebase,
bibliotek wykresów i bibliotek płatności.

Jedyny wyjątek to silnik 3D dodany w STAGE-012 i **wyłącznie jako
`debugImplementation`** — patrz „Spike renderera 3D (STAGE-012)”. Wariant release
nie kompiluje go, nie linkuje i nie pakuje.

## Warstwa domenowa (STAGE-002, STAGE-002A)

Kanoniczna warstwa domenowa **istnieje** i mieszka w
`app/src/main/java/com/buildplan/app/domain/`:

- `domain/money/` — `CurrencyCode`, `Money`;
- `domain/units/` — `MeasurementDimension`, `UnitOfMeasure`, `Quantity`;
- `domain/model/` — identyfikatory, encje, kontrakt kosztów oraz czyste
  zapytania wybierające elementy budynku (`BuildingElementSelection.kt`).

Celowo **nie** utworzono warstw `data/`, `repository/` ani `usecase/`. Nie
istnieje jeszcze logika, która by ich wymagała.

### Typowane identyfikatory

Każdy trwały obiekt ma własne, jawne ID jako `@JvmInline value class`:
`ProjectId`, `BuildingId`, `FloorId`, `RoomId`, `BuildingElementId`, `StageId`,
`CostId`, `BudgetId`. Puste identyfikatory są odrzucane w konstruktorze.

Tożsamością nigdy nie jest nazwa, pozycja na liście, indeks ani `hashCode`:
nazwy się zmieniają, pozycje się przestawiają, a hash koliduje. Typowane ID
sprawia dodatkowo, że podanie `RoomId` tam, gdzie oczekiwany jest `FloorId`,
jest błędem kompilacji.

**Generowanie identyfikatorów nie zostało tu ustalone** — należy do STAGE-003,
które decyduje, kiedy encja staje się trwała.

### Money — niezmiennik jednostek podrzędnych

`Money(minorUnits: Long, currency: CurrencyCode)`. Prawdą kwoty jest **całkowita
liczba najmniejszych jednostek waluty** (100 zł to 10 000 groszy). Domena nigdy
nie używa `Float` ani `Double` do pieniędzy: nie reprezentują dokładnie 0,10,
a błędy kumulują się przez cały budżet budowy.

Dwie reguły są egzekwowane, a nie tylko opisane:

- działania na różnych walutach są **odrzucane**, nigdy cicho konwertowane;
- przepełnienie **rzuca wyjątek** (`Math.addExact` i pokrewne), zamiast zawinąć
  się w błędną kwotę.

`Money` nie zawiera formatowania — zamiana kwoty na tekst to sprawa UI.

### Jednostki

`UnitOfMeasure` jest enumem (mm, cm, m, m², m³, l, szt., kg, t), a nie dowolnym
stringiem. Każda jednostka deklaruje `MeasurementDimension`, dzięki czemu model
odrzuca np. powierzchnię pokoju wyrażoną w kilogramach. `Quantity` odrzuca
wartości nieskończone i `NaN` przy konstrukcji. Konwersji między jednostkami
celowo nie zaimplementowano — celem jest stabilna semantyka, nie biblioteka
jednostek.

### Hierarchia budynku

`Project → Building → Floor → Room`. Projekt ma dokładnie jeden budynek (MVP).
Własność jest wyrażona jeden raz — przez zagnieżdżenie — więc `Room` nie nosi
`floorId`, który mógłby zaprzeczyć strukturze. Kondygnacje są trzymane w jednym
kanonicznym porządku (ściśle rosnące `order`), co odrzuca zarówno duplikaty
pozycji, jak i listę sprzeczną z zapisaną kolejnością.

### Własność elementów budynku (STAGE-002A)

`BuildingElement` **nie należy do kondygnacji przez zagnieżdżenie**. Wszystkie
elementy mieszkają na `Building.elements`, a każdy z nich deklaruje dokładnie
jeden logiczny zakres:

- `BuildingElementScope.WholeBuilding` — dach, fundament, komin przechodzący
  przez kondygnacje;
- `BuildingElementScope.OnFloor(floorId)` — ściany, stropy i otwory jednej
  kondygnacji.

Powód: trzymanie elementów w `Floor` nie ma miejsca na dach i fundament — nie
należą do żadnej kondygnacji — i wymuszałoby **fikcyjną kondygnację**, którą
trzeba by potem ukrywać w każdej liście, każdym ukryciu i każdej sumie. Fikcyjne
kondygnacje i pokoje nie istnieją.

Przynależność do pomieszczeń jest **osobną relacją 0..N**: `roomIds: Set<RoomId>`.
Ściana dzieląca dwa pokoje jest **jednym** elementem połączonym z dwoma
pokojami, a nie dwiema kopiami — dzięki temu zachowuje jedną tożsamość, jeden
koszt i jedną powierzchnię. Element o zakresie `OnFloor` może wskazywać wyłącznie
pomieszczenia z tej samej kondygnacji; egzekwuje to konstruktor `Building`.
Identyfikatory pomieszczeń są unikalne w obrębie całego budynku, bo elementy
sięgają do nich po ID.

### Czyste zapytania: ukrywanie i izolacja

`BuildingElementSelection.kt` odpowiada na pytania, których będzie potrzebował
przyszły model 3D: elementy kondygnacji, elementy całego budynku, elementy dachu,
elementy pomieszczenia, zbiór widoczny po ukryciu dachu i wybranych kondygnacji
(`BuildingVisibility`) oraz kontekst wyizolowanego pomieszczenia
(`isolateRoom`). Wszystko to jest czystym Kotlinem, deterministycznym
(zachowuje kolejność `Building.elements`) i testowalnym na JVM.

`BuildingVisibility` jest **argumentem zapytania, nigdy polem encji**. Domena nie
przechowuje stanu widoczności: encja pamiętająca, co jest teraz ukryte, trzymałaby
stan renderera, a dwa widoki tego samego budynku nie mogłyby się różnić. Praca
kamery należy do renderera — domena dostarcza wyłącznie wynik selekcji.

### Brak stanu renderera w domenie

`BuildingElement` ma jawny `BuildingElementKind` (wall, slab, roof, door,
window, stairs, foundation, facade, other) i **nie zawiera geometrii,
transformacji, siatek, materiałów, flagi widoczności ani żadnego innego stanu
renderera**. Przyszły model 3D będzie czytał te dane i trzymał własną
reprezentację. Wpuszczenie stanu renderera do domeny uczyniłoby ją bezużyteczną
bez renderera i niemożliwą do testowania na JVM.

### Kwota kosztu i etap (STAGE-002A)

`Cost.amount` to **kwota brutto, faktycznie zapłacona** — jedyna kanoniczna
liczba, z której będą budowane sumy w pierwszym MVP. Pól netto/VAT celowo nie
ma: produkt nie ma jeszcze trybu firmowego, a rozbicie kwoty przed pojawieniem
się funkcji, która go wymaga, zostawiłoby trzy pola mogące się rozjechać.

`Cost.stageId` jest **klasyfikacją**, a nie drugą kwotą. Faktura na 1000 PLN
opisana etapem „Dach” wnosi do projektu dokładnie 1000 PLN, niezależnie od tego,
ile etykiet nosi. Dlatego ani `Stage`, ani `CostAllocation`, ani żaden
`CostTarget` nie trzyma własnego `Money` — relacja nie może stać się drugą
kwotą.

Sumowania i UI kosztów jeszcze nie ma; powyższe jest kontraktem semantycznym
zapisanym w modelu i sprawdzanym testem (`CostSemanticsTest`).

### Typowany cel kosztu

`CostTarget` to sealed hierarchy: `ProjectTarget`, `StageTarget`, `FloorTarget`,
`RoomTarget`, `BuildingElementTarget` — a nie jeden luźny `targetId: String`,
który nie potrafiłby powiedzieć, czy „f-1” to kondygnacja, czy formalność.

Koszt może mieć **wiele** alokacji. `CostAllocationKind` rozróżnia `DIRECT`,
`DISTRIBUTED` i `ESTIMATED`, bo niosą różny poziom pewności. Udziały
`DISTRIBUTED` są wyrażone w punktach bazowych (10 000 bp = 100%), czyli
całkowitoliczbowo — `Double` uniemożliwiałby reprezentację 1/3 i zostawiał
resztki zaokrągleń.

**Co jest już gwarantowane:** cele alokacji są unikalne w obrębie kosztu, udział
`DISTRIBUTED` mieści się w 1..10 000 bp, a suma udziałów nie przekracza 100%.
**Co pozostaje do STAGE-015:** wymóg sumowania się do dokładnie 100%, algorytm
automatycznego rozdzielania i sposób rozdysponowania reszty z zaokrągleń.

Cały ten kształt jest **prowizoryczny**. Decyzja o podziale kosztów
(DEC-COST-ALLOCATION-001) pozostaje otwarta do STAGE-015 — alokacji nie
rozbudowujemy wcześniej.

### Budżet bez zduplikowanej prawdy

`Budget` przechowuje wyłącznie kwotę planowaną. **Nie ma pól `spent`,
`remaining` ani `usedPercent`** — wynikają one z zarejestrowanych kosztów,
a zapisana kopia rozjechałaby się z nimi przy pierwszej edycji kosztu,
zostawiając dwie odpowiedzi na to samo pytanie. Zostaną wyliczone później.
Budżety per etap i per kategoria nie są modelowane, bo nie ma funkcji, która
by je ustawiała.

### Walidacja

Niezmienniki są sprawdzane w konstruktorze i sygnalizowane przez
`IllegalArgumentException`. Nie wprowadzono frameworka `Result`/`Error`:
naruszenie niezmiennika jest błędem programisty, a nie odzyskiwalnym wynikiem.

### Czystość domeny

Domena nie zależy od Compose, Android Context, Room, SQLite, Retrofit ani
żadnego serializatora. Jest to **testowane**, nie tylko deklarowane
(`DomainPurityTest`, DOM002-16): test skanuje importy w źródłach domeny oraz
sprawdza refleksją typy pól i adnotacje encji.

Jedno zastrzeżenie: domena mieszka w tym samym module Gradle co UI, więc plugin
kompilatora Compose przechodzi także po jej klasach i dodaje syntetyczne
statyczne pole `$stable`. To artefakt builda, nie zależność w kodzie źródłowym.
Wydzielenie domeny do osobnego modułu czysto-kotlinowego usunęłoby i to — do
rozważenia przy STAGE-003.

### Brak persystencji

W domenie nie ma DAO, encji bazodanowych, adnotacji persystencji, DTO ani
serializacji. **STAGE-003 jest właścicielem wyboru persystencji** i zaprojektuje
ją na podstawie zaakceptowanego modelu.

### `applicationId` / namespace

Roboczo `com.buildplan.app`. **To decyzja robocza** i wymaga potwierdzenia
przez OWNER-a przed publikacją w Google Play. Migracji nazwy pakietu nie wolno
wykonywać samodzielnie.

## Kontrakt geometrii budynku (STAGE-011)

Geometria **istnieje** i mieszka w osobnym pakiecie
`app/src/main/java/com/buildplan/app/geometry/`. Jest to warstwa modelu, a nie
renderer: nie wybrano jeszcze żadnej technologii 3D i nic w tym pakiecie jej nie
przesądza.

### Układ współrzędnych i jednostki

- **Metry.** Każda współrzędna, rzędna, wysokość i grubość jest długością
  w metrach. Pikseli, `dp` ani współrzędnych ekranu w geometrii nie ma —
  zależą od wyświetlacza, a geometria zależna od wyświetlacza nie da się
  przetestować ani użyć ponownie przez drugi renderer.
- **Osie:** układ prawoskrętny z **Y w górę** (`X × Y = Z`). `X` i `Z` to osie
  poziome, więc **rzut poziomy leży w płaszczyźnie XZ**.
- **Kierunek obiegu wielokąta nie ma znaczenia semantycznego.** Walidacja
  używa wartości bezwzględnej pola, żeby żaden prymityw nie zależał po cichu od
  tego, czy obrys zapisano zgodnie z ruchem wskazówek zegara, czy przeciwnie.
- **Współrzędne są lokalne dla jednego modelu budynku.** Nie ma działki,
  terenu, georeferencji ani kamery.
- `NaN` i nieskończoności są odrzucane w konstruktorze wszędzie. Próg
  tożsamości punktów i degeneracji wielokąta jest jawny
  (`GeometryTolerance`), a nie ukryty w porównaniach.

### Granica: semantyka domeny vs geometria

`Building` nic nie wie o geometrii; zależność idzie wyłącznie w jedną stronę
(pilnuje tego `DomainPurityTest`). Dzięki temu budynek może istnieć, być
kosztorysowany i testowany, zanim w ogóle dostanie kształt — dokładnie tak jak
`MarcowkiReferenceProject`.

Prymityw geometryczny niesie z domeny **wyłącznie `BuildingElementId`**. Nie
powtarza `BuildingElementKind`, `BuildingElementScope`, `roomIds` ani stanu
widoczności: te mają już jedną kanoniczną odpowiedź na `BuildingElement`,
a druga kopia mogłaby wyłącznie się z nią rozjechać. Sprawdza to
`GeometryPurityTest` (GEO011-07, GEO011-18).

### Prymitywy

Prymitywy są **wysokopoziomowe, nie są zapieczoną siatką trójkątów** — pieczenie
zależy od renderera, którego nie ma.

- `PlanPoint(x, z)` — punkt rzutu; `ModelPoint(x, y, z)` — punkt w przestrzeni
  lokalnej. Nie są to typy `android.graphics`.
- `WallGeometry` — **oś ściany** (`start`, `end`), rzędna dolna, dodatnia
  wysokość, dodatnia grubość oraz `openings` (STAGE-013B). Oś, a nie obrys:
  obrys trzeba by przy każdej edycji uzgadniać z własną grubością.
- `WallOpening` (STAGE-013B) — prostokątna dziura w ścianie: odległość wzdłuż
  osi, szerokość, rzędna parapetu, wysokość, oraz `BuildingElementId`
  **wypełniającego ją okna lub drzwi**. Dziura należy do ściany, skrzydło do
  okna. Rodzaju otworu tu nie ma — różnicę okno/drzwi opisuje w geometrii
  wyłącznie parapet.
- `OpeningPanelGeometry` (STAGE-013B) — płaskie wypełnienie otworu: szyba,
  skrzydło, okno połaciowe. Walidowane na płaskość, nie na pionowość: okno
  połaciowe leży w płaszczyźnie dachu.
- `SlabGeometry` — obrys w rzucie, rzędna **spodu** i dodatnia grubość. To,
  której płaszczyzny dotyczy rzędna, jest nazwane wprost, bo „strop na 2,80” jest
  niejednoznaczne dokładnie o grubość stropu.
- `RoofFacetGeometry` — jedna płaska połać dachu jako uporządkowane wierzchołki
  3D. Pole liczone metodą Newella, czyli na płaszczyźnie połaci, a nie w rzucie.
- `GablePanelGeometry` (STAGE-013) — pionowy wielokąt płaski zamykający lukę
  między ścianą a połacią: szczyt, albo skośne zakończenie ścianki poddasza.
  Walidowany na pionowość. Należy do tej samej ściany co jej `WallGeometry`.

Pełnego solvera samoprzecięć **celowo nie ma**: obrys typu „muszka” to realny
błąd, ale nic w produkcie nie generuje jeszcze obrysów.

### Jeden element, wiele prymitywów

Relacja jest jeden-do-wielu z założenia. Dach dwuspadowy to **jeden** semantyczny
element `ROOF` (jedna nazwa, jeden koszt, jedno ukrycie) złożony z **dwóch**
połaci, bo jedna płaszczyzna nie jest dachem dwuspadowym. Rozbijanie dachu na
dwa elementy semantyczne wprowadziłoby szczegół renderowania do modelu kosztów.

### Kolekcja, złączenie po ID i bounds

`BuildingGeometry` trzyma uporządkowaną listę prymitywów; każde zapytanie
zachowuje kolejność deklaracji, więc to samo pytanie zawsze daje tę samą
odpowiedź w tej samej kolejności. Potrafi zwrócić prymitywy jednego elementu,
przefiltrować się zbiorem `BuildingElementId` oraz **sprawdzić się względem
kanonicznego `Building`** (`requireElementsIn`): geometria wskazująca nieistniejący
element jest odrzucana, bo taki kształt byłby dalej rysowany, a nikt nie mógłby
go nazwać, ukryć ani wycenić. Sprawdzenie jest na żądanie, a nie w konstruktorze,
bo geometria powstaje **obok** budynku, nie po nim.

`LocalBounds` to lokalny prostopadłościan osiowy do późniejszego kadrowania.
Dla pustej geometrii jest `null`, a nie zerowe pudełko w początku układu —
pudełko wokół niczego to kłamstwo, do którego kamera chętnie by poleciała.
**To nie jest kamera:** ani kamery, ani projekcji, ani pickingu w tym pakiecie
nie ma.

### Most: selekcja semantyczna → geometria

Reguły ukrywania i izolacji zostały już rozstrzygnięte raz — w domenie,
w `BuildingElementSelection.kt`. Geometria ich **nie powtarza**. Cały most to
jedna funkcja `BuildingGeometry.primitivesOf(elements)`, więc kompozycja zawsze
wygląda tak:

```kotlin
val visible = building.visibleElements(BuildingVisibility(roofHidden = true))
val toDraw = geometry.primitivesOf(visible)
```

Przetestowane na syntetycznym domu: ukrycie dachu usuwa obie połacie, ukrycie
poddasza zostawia geometrię parteru, ukrycie obu zostawia ściany i strop parteru,
a izolacja pomieszczenia zwraca ścianę dzieloną dokładnie raz.

### Syntetyczny dom demonstracyjny

`app/src/debug/java/com/buildplan/app/geometry/demo/SyntheticDemoHouse.kt` to
`Building` **wraz z** geometrią: dwie kondygnacje, trzy pomieszczenia, ściana
działowa połączona z dwoma pokojami, płyta fundamentowa, strop nad parterem
i dach dwuspadowy o dwóch połaciach.

**Wszystkie liczby są wymyślone.** Nic w nim nie zostało zmierzone, odrysowane,
wyprowadzone ani zgadnięte z żadnego realnego domu, projektu, rysunku, zrzutu
ekranu ani podanej powierzchni. Powstał wyłącznie po to, żeby pierwszy renderer
miał co wyświetlić, zanim istnieje parser rzutów.

**To nie jest rekonstrukcja domu z ARCHON+.** Zbiór `MarcowkiReferenceProject`
pozostaje bez geometrii (`elements` jest puste), bo jego źródło nie podaje rzutu;
doczepienie tych współrzędnych do jego identyfikatorów zamieniłoby uczciwe „nie
znamy rzutu” w zmyślony rzut. Identyfikatory obu zbiorów są rozłączne, żaden nie
odwołuje się do drugiego, a test tego pilnuje (GEO011-16, GEO011-17).

Fixture żyje w `src/debug`, a jego testy w `src/testDebug` — wariant release ani
go nie kompiluje, ani nie wysyła. Sprawdzone: w wyjściu `compileReleaseKotlin`
jest pakiet `geometry`, ale nie ma klas `geometry.demo` ani `reference`,
a `testReleaseUnitTest` przechodzi bez nich.

### Czego w geometrii nie ma

Drzwi, okien, schodów, elewacji, obrysów pomieszczeń jako prawdy produktu, ścian
krzywoliniowych, dowolnych siatek, CAD/BIM i terenu. Pierwszy renderer potrzebuje
tylko tyle, żeby udowodnić bryłę 3D i UX warstw/izolacji.

## Referencyjny zbiór danych (STAGE-010A)

`app/src/debug/java/com/buildplan/app/reference/` zawiera jeden deterministyczny
projekt referencyjny odwzorowujący publiczną strukturę pomieszczeń gotowego
projektu domu (ARCHON+ „Dom w marcówkach (GE)”): dwie kondygnacje, osiemnaście
pomieszczeń, podane powierzchnie.

- **To nie są dane użytkownika ani prawda produktu.** Zbiór żyje wyłącznie
  w źródłach `debug`, więc kompilacja release ani go nie kompiluje, ani nie
  wysyła. Jego testy leżą w `src/testDebug/`, dzięki czemu wariant release nie
  zależy od treści osób trzecich. Sprawdzone: `compileReleaseKotlin`
  i `testReleaseUnitTest` przechodzą, a w wyjściu release nie ma klas pakietu
  `reference`.
- **Proweniencja mieszka poza domeną.** Tytuł, adres URL i publiczne fakty
  skalarne (powierzchnia domu, dach, wysokość) trzyma `ReferenceProjectSource`
  w pakiecie `reference`. Domeny nie rozszerzono, żeby je pomieścić — `Project`
  nie dostał pola na adres strony.
- **Renderer będzie czytał kanoniczny `Building`, nie HTML ani rysunki źródła.**
  W repozytorium nie ma obrazów, rzutów ani opisów marketingowych pobranych ze
  strony projektu. Nie ma też parsera ani kodu sieciowego — zbiór jest literałem
  w Kotlinie.
- **Ten zbiór nadal nie ma geometrii i mieć jej nie będzie, dopóki nie pojawi
  się źródło rzutu.** Źródło podaje nazwy i powierzchnie pomieszczeń; nie podaje
  współrzędnych ścian, obrysów pomieszczeń, sąsiedztwa, otworów, geometrii
  schodów ani rzędnych kondygnacji. Dlatego `Building.elements` referencyjnego
  budynku jest **puste**, a jego `Floor` nie ma `elevation` ani `height`.
  STAGE-011 dodał kontrakt geometrii, ale **nie** podpiął go tutaj: zmyślony rzut
  wyglądający na autorytatywny byłby gorszy niż brak rzutu. Kształt do prac nad
  rendererem daje osobny, jawnie syntetyczny `SyntheticDemoHouse`.
- **Jedna powierzchnia na pomieszczenie.** Źródło pokazuje przy części
  pomieszczeń i sum wartość alternatywną w nawiasie. Zakodowano wyłącznie
  wartość podstawową: drugie pojęcie powierzchni nie jest częścią przyjętego
  kontraktu domeny.

Mała syntetyczna atrapa `ReferenceBuilding` w `src/test/` zostaje bez zmian. Ma
inny cel — testuje kontrakt własności elementów — i celowo nie zależy od faktów
osób trzecich.

## Spike renderera 3D (STAGE-012)

Spike, nie produkcja. Zadaniem było rozstrzygnąć **jedno** pytanie: czy przyjęte
warstwy — semantyczna domena i kontrakt geometrii — dają się interaktywnie
narysować na Androidzie bez naginania któregokolwiek z ich niezmienników.
Odpowiedź brzmi tak, a kod dowodzący tego żyje w całości w `src/debug`.

### Zależność

| Artefakt | Wersja | Licencja | Konfiguracja |
| --- | --- | --- | --- |
| `com.google.android.filament:filament-android` | 1.75.1 | Apache-2.0 | `debugImplementation` |
| `com.google.android.filament:filamat-android` | 1.75.1 | Apache-2.0 | `debugImplementation` |

1.75.1 to najwyższe **stabilne** wydanie faktycznie rozwiązywalne z Maven Central
w chwili realizacji etapu (`maven-metadata.xml`: `<release>1.75.1</release>`).
Żadnej wersji 1.76.x tam nie ma — pinowanie jej byłoby pinowaniem wersji, której
nie da się pobrać. Obie biblioteki deklarują `minSdkVersion 21`, więc `minSdk 24`
projektu zostaje bez zmian. Nie dodano SceneView; nie podnoszono żadnej innej
zależności.

### Dlaczego bezpośrednio Filament

Filament to biblioteka renderująca z publicznym API Java/JNI, a nie framework
sceny. Nie narzuca modelu danych, formatu zasobów ani nawigacji, więc geometria
i semantyka mogą zostać dokładnie tam, gdzie są, a renderer pozostaje warstwą,
która je **czyta**. Warstwa pośrednia w rodzaju SceneView dokładałaby własne
pojęcie węzła i cyklu życia, a spike miał sprawdzić granicę, nie ukryć ją.

### Granica geometria → GPU

Kierunek zależności jest jednostronny i niezmieniony: renderer czyta geometrię,
geometria nie wie o rendererze. `GeometryPurityTest` i `DomainPurityTest` badają
`src/main`, a cały spike leży w `src/debug`, więc żaden typ Filamenta nie może
wejść do domeny ani do geometrii.

Pieczenie trójkątów to osobny, czysty adapter —
`app/src/debug/java/com/buildplan/app/render/filament/BuildingRenderMesh.kt`:

- `WallGeometry` i `SlabGeometry` → graniastosłup z obrysu, zamknięty czapkami;
- `RoofFacetGeometry` → jedna płaska połać;
- ściany nie są zgrzewane, więc każda ścianka ma własną, płaską normalną;
- nawinięcie trójkąta jest **korygowane** do normalnej, którą jest cieniowany
  (materiał jest dwustronny, a dwustronny materiał wnioskuje kierunek normalnej
  z orientacji trójkąta);
- adapter nie importuje niczego z Filamenta, więc testuje się na czystym JVM.

Złączenie z semantyką to nadal **wyłącznie `BuildingElementId`**. Relacja
jeden-do-wielu przechodzi przez adapter bez zmian: dach dwuspadowy to jeden
`BuildingElementId` i dwie siatki, a więc dwie encje Filamenta odpowiadające tym
samym id. Dotknięcie jednej połaci podświetla obie — bo to jeden element.

### Widoczność: jedna ścieżka decyzyjna

Renderer **nie ma** własnej reguły ukrywania. Ścieżka jest zawsze ta sama:

```
val visible    = building.visibleElements(visibility)   // domena decyduje
val primitives = geometry.primitivesOf(visible)         // jedyny most
renderer.setVisibleElements(primitives.map { it.elementId }.toSet())
```

Przełączenie widoczności **nie przebudowuje geometrii**: siatki są wgrywane raz,
a zmiana widoku to dodanie i usunięcie encji ze sceny. Widoczne skutki uboczne są
dokładnie takie, jakich wymaga model: ukrycie poddasza zostawia dach w powietrzu,
bo dach ma zakres `WholeBuilding`, a nie `OnFloor(poddasze)`.

### Cykl życia

Jedna instancja renderera na jeden `SurfaceView`, tworzona przy wejściu do
kompozycji i niszczona przy wyjściu. Brak singletona i brak statycznego silnika:
po `recreate()` Activity poprzedni silnik jest już zniszczony, a nowy powstaje
osobno. Potwierdzone na urządzeniu — dwa kolejne odtworzenia zalogowały
`FEngine created` **pod tym samym adresem**, co znaczy, że poprzedni został
w całości zwolniony.

Zapamiętywany jest wyłącznie stan kamery (`rememberSaveable`), bo użytkownik
ustawił ją ręcznie. Widoczność i zaznaczenie celowo **nie** są utrwalane.

### Materiał

Jeden materiał: neutralny, dwustronny, bez tekstur, z kolorem jako parametrem
instancji. Tło jest ciemne, bryła jasna — wygląd techniczny, nie fotorealizm.
Nie ma mapy środowiskowej ani zasobu IBL: człon ambient to jeden współczynnik
sferyczny podany liczbą.

Materiał jest kompilowany **na urządzeniu** przez `filamat-android`, żeby nie
wciągać do builda narzędzia `matc` ani nie commitować binarnego `.filamat` dla
materiału, który może nie przetrwać wyboru renderera. Koszt jest realny
i ograniczony: `filamat-android` to druga biblioteka natywna, wyłącznie w debug.
**Produkcjonizacja (STAGE-013) powinna zastąpić to skompilowanym `.filamat`.**

### Natywne biblioteki i zgodność z 16 KB

`filament-android` i `filamat-android` wnoszą `libfilament-jni.so`
i `libfilamat-jni.so` w czterech ABI. (`libandroidx.graphics.path.so` było
w APK już wcześniej — pochodzi z `androidx.graphics:graphics-path`, tranzytywnej
zależności Compose UI.)

Sprawdzone na **zbudowanym APK**, nie na dokumentacji:

- każdy segment `LOAD` każdej z 12 spakowanych bibliotek ma `align = 0x4000`
  (2^14), więc warunek 16 KB jest spełniony także w 32-bitowych ABI, których on
  nie dotyczy;
- `zipalign -c -P 16 -v 4` → `Verification successful`, każde `.so` na granicy
  16 KB;
- `GNU_RELRO` obecne we wszystkich 12 bibliotekach;
- **runtime zweryfikowany**: ten sam APK uruchomiony na lokalnie zainstalowanym
  obrazie API 36 o stronie 16 KB (`getconf PAGE_SIZE` = 16384) ładuje obie
  biblioteki, renderuje, pickuje i przełącza widoczność bez awarii.

Te kontrole trzeba powtarzać po każdej zmianie wersji renderera.

### Granica spike / produkcja

Spike dowodzi: wgrania geometrii, mapowania `BuildingElementId`, orbitowania,
skali i przesuwania, ukrywania dachu i kondygnacji, pickingu i stabilności cyklu
życia. Spike **nie** rozstrzyga: docelowej architektury renderera, hosta
produkcyjnego, izolacji pomieszczenia w UI, systemu materiałów, cieni, poziomów
szczegółowości ani wydajności na realnym sprzęcie. To zakres STAGE-013.

## Model wizualny „Dom w marcówkach (GE)" V1 (STAGE-013)

Pierwszy model, którego kształt pochodzi ze **źródła**, a nie z wyobraźni:
`reference/visual/MarcowkiVisualModelV1` w źródłach `debug`. Powstał po to, żeby
OWNER mógł postawić render obok strony produktowej ARCHON i powiedzieć, czy to
ten sam dom. Odpowiada na pytanie o bryłę, kierunek dachu i układ ścian
działowych — i na tym się kończy. **Nie jest dokumentacją budowlaną**, nie ma
otworów i nic w nim nie nadaje się do wymiarowania.

### Klasyfikacja wiarygodności — obowiązkowa

Każda nietrywialna liczba w modelu ma jedną z trzech etykiet (`SourceFidelity`),
a ledger wszystkich z nich to `MarcowkiSourceEvidence`:

- **`SOURCE_EXACT`** — wydrukowana w aktualnym źródle: parametr ze strony albo
  zwymiarowana wartość na rzucie lub przekroju. Np. 40°, 1,30 m ścianki
  kolankowej, kotwy 1205 / 1260 / 790 / 510, poziomy ±0,00, +3,06, +7,95, −0,32.
- **`SOURCE_TRACED`** — odmierzona z aktualnego obrazu źródłowego po kalibracji
  do kotew `SOURCE_EXACT`. Np. położenie ścianki działowej, granica strefy
  pomieszczenia, grubość ściany.
- **`DISPLAY_ASSUMPTION`** — źródło jej nie podaje wcale; istnieje tylko po to,
  żeby dało się narysować powierzchnię. Jest ich cztery i każda jest wyliczona
  z uzasadnieniem.

Rozróżnienie nie jest kosmetyczne. Odmierzona pozycja ścianki i podany kąt dachu
wyglądają identycznie, gdy oba są już `Double` w tym samym pliku — a wtedy model
zaczyna przedstawiać pomiar z rastra 853 px jako deklarację projektanta. To
właśnie ta etykieta pilnuje, żeby model walidacyjny nie stał się fałszywą
dokumentacją.

Strefy pomieszczeń, których źródło **nie rysuje** (kuchnia i hol są jedną
przestrzenią otwartą), są oznaczone `TRACE_UNCERTAIN`. Niepewność ma być
widoczna, a nie ukryta w wiarygodnie wyglądającej liczbie.

### Jedna prawda: siatka odrysu

`MarcowkiPlanGrid` trzyma wszystkie odmierzone współrzędne w jednym miejscu.
Z niej — i wyłącznie z niej — powstają zarówno elementy ścian
(`MarcowkiVisualModelV1`), jak i strefy pomieszczeń (`MarcowkiRoomTrace`). Dwa
ręcznie utrzymywane opisy tego samego rzutu rozjeżdżają się przy pierwszej
korekcie; tutaj ścianka przesuwa się raz, a pomieszczenia po obu jej stronach
przesuwają się razem z nią.

Kalibracja: oba rastry rzutów mają 853 × 853 i wychodzą na 37,77 px/m — 455 px
na wydrukowanej kotwie 1205 i 476 px na kotwie 1260. Rzut piętra wychodzi na tę
samą skalę, więc obie kondygnacje są odrysowane w jednym układzie i ich ściany
pokrywają się z konstrukcji, a nie z dopasowania.

### Kierunek kalenicy ustalony rachunkiem, nie renderem marketingowym

Kalenica biegnie z północy na południe, nad środkiem szerokości 7,89 m. Nie
odczytano tego z wizualizacji: tylko takie ułożenie odtwarza podaną powierzchnię
dachu 150,57 m² (40°, odrysowany wysięg szczytowy 1,00 m, okap bez wysięgu →
150,4 m²), a przekrój — którego szerokość skaluje się do 7,89 m względem jego
własnych wymiarów pionowych, a nie do 12,60 m głębokości — tnie w poprzek
szczytu. Podana wysokość 8,27 m zgadza się niezależnie: +7,95 kalenicy nad −0,32
terenu.

### Materiał źródłowy jest przejściowy

Rzuty i przekrój ARCHON są pobierane do katalogu **poza worktree**, odmierzane
i tam zostawiane. W repozytorium wolno trzymać wyłącznie fakty tekstowe: adres
strony, adresy rastrów, moment odczytu i liczby. Żadnego obrazu, żadnego HTML,
żadnego skopiowanego tekstu marketingowego, nic z tego w APK. Pilnuje tego test
`M013-13`.

### `GablePanelGeometry` — nowy prymityw

Ściana szczytowa to pionowy wielokąt płaski, którego górna krawędź biegnie po
połaci, a nie po poziomie. `WallGeometry` jest wyciągnięciem między dwoma
**stałymi** poziomami, a `RoofFacetGeometry` to powierzchnia oglądana z góry —
żaden z nich nie utrzymuje tego kształtu uczciwie. Panel należy do tej samej
ściany co jej `WallGeometry` (jeden element, wiele prymitywów) i jest walidowany
na pionowość: składowa Y normalnej musi być zerowa.

Tego samego prymitywu używają ścianki działowe poddasza. Poddasze jest wnętrzem
dachu, a nie pomieszczeniem pod nim: przy okapie spód połaci jest 1,76 m nad
podłogą, więc ścianka o pełnej wysokości 2,66 m wychodziłaby przez dach. Ścianka
jest więc budowana do niższej z tych wartości, a lukę nad nią zamyka panel
biegnący po połaci. Testy pilnują, że **żaden** wierzchołek poddasza nie
przebija spodu dachu.

### Renderer bez zmian

Nadal bezpośredni Filament 1.75.1, nadal `debugImplementation`, nadal materiał
kompilowany na urządzeniu. Wymieniona została **treść** hosta debugowego, nie
jego architektura: doszedł przełącznik modelu `Marcówki / Syntetyczny`
(Marcówki domyślnie) oraz pięć deterministycznych presetów widoku
(`ModelViewPreset`), z których każdy jest czystą funkcją `LocalBounds` — bo
zrzut ekranu jest dowodem tylko wtedy, gdy da się go powtórzyć. Syntetyczny dom
zostaje jako fikstura regresyjna renderera: jego liczby są wymyślone, więc nie
zmieniają się przy korekcie odrysu.

## Korekta modelu wizualnego (STAGE-013B)

OWNER obejrzał V1 i zgłosił cztery rzeczy: ukrycie warstwy dawało bryłę, o
której nie było widać, że to ten sam dom; brakowało okien; schody były w złym
miejscu; całość wyglądała jak klocek low-poly, a nie jak techniczny model
studialny. Poniżej decyzje, które z tego wynikły. Wszystkie mieszczą się
w `src/debug`, poza kontraktem otworów, który należy do `geometry/`.

### Ciągłość modelu przy ukrywaniu warstw — reguła

**Ukrycie warstwy nie może zmienić bryły, tylko to, ile z niej widać.**
Egzekwują to trzy rzeczy naraz:

- **Jeden kanoniczny model.** Nie ma uproszczonych wariantów geometrii dla
  poszczególnych trybów. Każdy stan widoczności jest **podzbiorem** tego samego
  `MarcowkiVisualModelV1.geometry`, a suma wszystkich stanów to całość modelu —
  jest to sprawdzane testem, nie obietnicą.
- **Warstwa zdjęta zostaje narysowana jako siatka.** Renderer dostaje dwa
  zbiory: widoczny i usunięty. Widoczny rysuje jako bryłę z konturem, usunięty
  wyłącznie jako kontur — jaśniejszy dla obecnego, przygaszony i bez testu
  głębi dla zdjętego. Dach zdjęty z domu nadal wisi nad nim jako szkielet, więc
  pytanie „czy to jeszcze ten sam budynek” nie ma jak powstać.
- **Zbiór usunięty to dopełnienie, nie druga reguła.** Liczy się go jako
  `wszystkie elementy geometrii − wynik zapytania domeny`. Renderer nadal nie
  ma własnego zdania o tym, co jest dachem ani co jest kondygnacją.

### Otwory: dziura należy do ściany, wypełnienie do okna

`WallGeometry` ma teraz `openings: List<WallOpening>`. `WallOpening` to
prostokątna dziura opisana **odległością wzdłuż osi ściany**, wysokością
parapetu i wysokością otworu — plus `BuildingElementId` **innego** elementu:
okna albo drzwi, które ją wypełniają.

Podział jest celowy i przebiega tam, gdzie przebiega w rzeczywistości:

- dziura jest własnością ściany, bo to ściana jest przewiercona;
- skrzydło jest osobnym elementem (`WINDOW` / `DOOR`) z własną nazwą,
  własnym pickingiem i własnym przyszłym kosztem;
- ukrycie okna zostawia dziurę — bo dziura tym właśnie jest.

`WallOpening` **nie** przenosi `BuildingElementKind`. Różnica między oknem
a drzwiami to w geometrii wyłącznie poziom parapetu, a drugi egzemplarz
odpowiedzi, którą domena już zna, mógłby się z nią tylko rozjechać.

Otwór jest pozycjonowany **współrzędną planu**, nie odległością od początku
ściany, i przeliczany na odległość dopiero przy budowie prymitywu. Ten etap
przedłużył cztery ściany o metr w każdą stronę (podcienie); zapisana odległość
przesunęłaby wtedy po cichu każde okno na tych ścianach.

`OpeningPanelGeometry` to płaskie wypełnienie otworu: szyba, skrzydło, okno
połaciowe. Osobny typ od `GablePanelGeometry`, bo nie jest pionowy (okno
połaciowe leży w płaszczyźnie dachu), jest oglądany z obu stron i należy do
własnego elementu, a nie do ściany. Walidowana jest płaskość.

Pieczenie dziury polega na **niepieczeniu** kawałka ściany: mur dzieli się na
odcinki pełnej wysokości obok otworu oraz podokiennik i nadproże nad nim. Węgarki
wychodzą wtedy jako czoła sąsiednich odcinków — zawsze dokładnie w licu otworu,
bo nic ich nie rysuje osobno.

### Reprezentacja schodów

Schody są elementem `STAIRS` na parterze, bo tam zaczyna się bieg. Nie wskazują
żadnego pomieszczenia: źródło nazywa „Schody” dopiero na poddaszu, a wymyślanie
pomieszczenia parteru po to, żeby schody miały gdzie mieszkać, jest dokładnie
tym ruchem, którego model nie robi.

Kształt to trzy biegi obracające się dwukrotnie wokół prostokątnego trzonu —
tak rysują je oba rzuty. Kierunek nie jest zgadnięty: strzałka na rzucie
poddasza wskazuje na zachód, w korytarz, co ustala całą sekwencję wstecz od
miejsca dojścia. Każdy stopień to osobny stopień o własnej grubości, a nie
bryła od podłogi — bryła wypełnia klatkę i z góry czyta się jak cokół, a widok
z góry jest tym jedynym, w którym OWNER musi rozpoznać schody.

Strop nad parterem jest w związku z tym cięty na kawałki wokół klatki. Schody
przechodzące przez lity strop to pierwsza rzecz, w którą nikt nie uwierzy.

### Wierność bryły: podcienie szczytowe

Najważniejsza korekta zewnętrza. Na obu rzutach i na obu kondygnacjach hatch
ścian zachodniej i wschodniej biegnie metr **za** ścianę szczytową. Wysunięcie
dachu o 1,00 m nie jest więc samym okapem — idą z nim ściany, a każdy szczyt
jest podcieniem z dwoma policzkami, z balkonem na poddaszu i przeszkleniem
cofniętym w głąb. To jest cecha, po której ten dom się rozpoznaje, i V1 nie
miał jej wcale.

Konsekwencje w modelu: naroża należą teraz do ścian północ–południe (to one
przechodzą przez oba szczyty), strop nad parterem wychodzi w oba podcienie jako
płyta balkonowa, a płyta fundamentowa podchodzi pod policzki, żeby nie kończyły
się w powietrzu.

### Przeszklenia szczytów mają spadzisty nadproże

Rzut poddasza podaje 2 × 234/303 i 270/320. Wysokość jest tam **maksimum**, nie
stałą: otwór 303 stojący tam, gdzie zaczyna się zachodni, potrzebowałby 3,03 m
połaci, a jest jej 2,38 m. Nadproża idą więc po dachu i sięgają podanej
wysokości tam, gdzie dach na to pozwala. Dlatego trójkąt szczytu nie jest już
jednym panelem, tylko pasmami: pełnymi od okapu i przeszklonymi od nadproża
w górę. Jeden panel z dziurą wymagałby wielokąta niespójnego, którego nic w tym
modelu nie upiecze.

### Styl prezentacji: kontur zamiast bryły

Do każdej siatki pieczony jest **drugi bufor indeksów** — lista linii ze
wszystkich krawędzi ścian, każda raz (deduplikacja po pozycji). Rysuje je
oddzielny materiał `UNLIT`, a bryła dostaje `setPolygonOffset`, żeby kontur
wygrywał test głębi zamiast z nim remisować.

To jest cała różnica między klockiem a rysunkiem technicznym: lite cieniowanie
mówi, gdzie jest ściana, ale nie mówi, gdzie się kończy. Do tego szyby są
ciemniejsze i chłodniejsze od muru — kolor wybierany po **typie prymitywu**,
nie po `BuildingElementKind`, bo „to jest cienkie płaskie wypełnienie otworu”
jest faktem o geometrii i renderer ma prawo go znać.

Presetów jest siedem: doszły `FACADE_OPENINGS` (nisko, prawie z poziomu terenu
— z 22° węgarek jest kreską) i `STAIRS_VIEW`, jedyny kadrujący **część** modelu.
Framing bierze więc dwa pudełka: model, do którego skalują się limity kamery,
i to, na co patrzy.

### Czego świadomie nie zrobiono

Komina — widać go na obu wizualizacjach, ale żaden rzut nie rysuje szybu, który
dałoby się odróżnić od szaf kreskowanych tak samo. Komin postawiony z renderu,
a nie z rzutu, byłby wymyśloną współrzędną w typie nieodróżnialnym od zmierzonej.
Okna połaciowe są odrysowane co do pozycji, ale rysowane jako panele **na**
połaci, bo ten etap nie wycina dziur w połaci. Jedno i drugie jest zapisane
w `MarcowkiSourceEvidence.notModelled` i `displayAssumptions`.

## Cechy rozpoznawcze modelu wizualnego (STAGE-013C)

Etap odpowiada na drugą ocenę OWNER-a: model był poprawny wymiarowo i nadal nie
był *tym* domem. Diagnoza z elewacji: brakowało pasów, po których ta bryła się
rozpoznaje, a garaż kończył się 0,34 m niżej niż balkon obok niego, więc nic nie
wiązało obu brył w jedną kompozycję.

### Rozpoznawalność jest kryterium akceptacji

Model referencyjny Marcówek ma jeden cel: OWNER stawia go obok strony ARCHON
i mówi, czy to ten sam dom. Zgodność liczb jest warunkiem koniecznym, nie
wystarczającym — dwa domy o tej samej powierzchni zabudowy, tym samym kącie
dachu i tej samej liczbie okien mogą wyglądać zupełnie inaczej, a różnicę robią
cechy rozpoznawcze: pasy, ramy, uskoki i to, co jest cofnięte względem czego.
Dlatego od tego etapu **cecha rozpoznawcza jest osobnym elementem budynku**,
z własną nazwą i własnym identyfikatorem, a nie skutkiem ubocznym innej bryły.

### Rama podcienia szczytowego

Każdy szczyt jest ramą: policzki podcienia i dwa pasy biegnące po połaci,
wszystko w jednej płaszczyźnie czoła podcienia, z elewacją cofniętą o metr za
nią. Szerokość pasa to grubość policzka — rama **jest** policzkiem wyprowadzonym
ponad okap, a nie opaską naklejoną na niego. Elewacje mierzą widoczny pas na
0,66 m przy 25,38 px/m; model niesie odrysowane z rzutu 0,44 m, bo pas szerszy
od policzka, który kontynuuje, wprowadziłby uskok w linii rysowanej w źródle
prosto. Różnica jest zapisana w `MarcowkiSourceEvidence`.

Dwa czworokąty na szczyt, nie jeden szewron: szewron nie jest wypukły, a renderer
trianguluje panel wachlarzem z pierwszego wierzchołka — wachlarz przez wcięcie
zamalowałby otwór, który rama ma obramować. Cięte na kalenicy obie połówki są
wypukłe i dzielą krótką krawędź, więc rama zamyka się dokładnie.

Rama ma zasięg `WholeBuilding`. Przechodzi przez obie kondygnacje i zostaje
w każdym stanie widoczności — to ona sprawia, że po zdjęciu dachu nadal widać
ten sam dom, a nie niższy budynek o tym samym rzucie.

### Opaska międzykondygnacyjna

Jedna pozioma linia na +3,06: czoło balkonu w obu podcieniach i góra garażu.
To ona wiąże wysoką bryłę ze szczytem z płaskim garażem — bez niej są to dwa
budynki, które się stykają. Góra pasa to poziom stropu poddasza podany
w przekroju, a jego wysokość 0,54 m to różnica między zwymiarowaną wysokością
garażu (252) a tym poziomem, więc **oba końce pasa są poziomami ze źródła**, nie
pikselami. Elewacje mierzą tę wysokość na 0,47–0,72 m zależnie od tego, którą
się mierzy.

Na domu opaska należy do kondygnacji, której krawędzią stropu jest, i znika
razem z poddaszem — jako wygaszona siatka, jak każda zdjęta warstwa. Na garażu
jest attyką samego stropodachu i nie potrzebuje osobnego elementu: stropodach
garażu sięga teraz +3,06 zamiast 2,72.

**Granica modelowania pasa.** Pas jest bryłą, a nie kolorem. `MeshStyle` nadal
wynika z **typu prymitywu**, nie z `BuildingElementKind` — renderer nie dostał
trzeciego stylu „rama", bo „to jest element charakterystyczny elewacji" jest
faktem o domenie, a nie o geometrii. Pas czyta się z wysunięcia, z konturu
i z cieniowania powierzchni poziomych, i to jest właściwy sposób.

Płyta balkonowa jest cofnięta dokładnie o grubość pasa. Fasada przed licem
płyty byłaby dwiema powierzchniami w jednej płaszczyźnie, a te migoczą przy
ruchu kamery.

### Siatka odniesienia — tylko prezentacja

`render/filament/PresentationGrid` rysuje rastrową płaszczyznę pod modelem: linie
co metr, co piąta mocniejsza, wyśrodkowane na rzucie i przyciągnięte do pełnych
metrów od początku układu. Dom zawieszony na ciemnym tle czyta się jak przedmiot;
ten sam dom na rastrze czyta się jak budynek o określonej wielkości.

Reguła jest twarda: **siatka nie jest geometrią modelu.** Nie ma
`BuildingElementId`, więc nie da się jej wybrać ani ukryć, żaden stan widoczności
nic o niej nie mówi, a `geometry/` i `domain/` o niej nie wiedzą — pilnuje tego
`C013C-06`. To nie jest teren, działka ani otoczenie i nie wolno tego w to
rozwijać.

### Kamera, presety i czytelność sterowania

Presetów jest dziesięć: doszły `SIGNATURE_FACADE` (szczyt północny z bliska,
prawie z poziomu — z góry pas 0,54 m na pionowym licu jest kreską),
`GARAGE_RELATION` (naroże południowo-wschodnie, jedyny widok, z którego linia
opaski biegnie przez cały ekran) i `SITE_CONTEXT` (z daleka, o siatce).

Dolne ograniczenie pochylenia kamery podniesiono z −70° do −12°. Pod modelem
kamera patrzy na spód płyty fundamentowej przez całą siatkę odniesienia i nic na
ekranie nie mówi już, gdzie jest góra — stan łatwy do osiągnięcia jednym
ruchem palca i nieoczywisty do cofnięcia. Kilka stopni poniżej poziomu wystarczy,
żeby zajrzeć pod podcień, i to jedyny powód, żeby tam być.

Etykiety chipów są jednowierszowe (`maxLines = 1`, bez zawijania), a rzędy chipów
mają margines na obu końcach. Chip stojący dokładnie przy krawędzi rzędu ma
pierwszy znak etykiety na granicy przycięcia — to jest to, co na zrzutach OWNER-a
wyglądało jak ucięty tekst.


## Wierność elewacji modelu wizualnego (STAGE-013D)

Etap odpowiada na trzecią ocenę OWNER-a: model miał już cechy rozpoznawcze
i nadal nie czytał się mocno jako *ten* projekt. Diagnoza z zestawienia
z wizualizacjami i elewacjami (w tym z zaznaczonymi na czerwono ramą i opaską):
rama była płaskim arkuszem bez głębokości, balkon południowy biegł na całą
szerokość, której źródło mu nie daje, balustrady i przeszklenia były lite,
a dach nie miał krawędzi. Etap przebiegł jako ograniczona pętla samoaudytu —
AUDIT-0 → CORRECTION-1 → AUDIT-1 — i zatrzymał się po jednej rundzie korekt,
bo warunek stopu (brak zer w B–H i K, suma ≥ 18/22) został spełniony. Pętla jest
techniką walidacyjną tego etapu, nie zachowaniem runtime: w kodzie nie ma
żadnego „audytora".

### Granica: rozpoznawalność kontra precyzja

Model pozostaje **stylizowanym, monochromatycznym modelem technicznym**, który
ma się rozkładać na warstwy. Kryterium jest zdanie osoby znającej projekt
z rzutów i wizualizacji: „to jest mój dom". Nie jest nim fotorealizm ani
dokumentacja wykonawcza. Konsekwencje:

- kolor jest jeden, a jedyny wyjątek to **przezroczystość szkła**, bo balustrada,
  przez którą nie widać, jest attyką;
- cechy rozpoznawcze mają bryłę i głębokość, nie kolor ani teksturę: rama jest
  belką metrowej głębokości ze skosem w narożu, a nie konturem;
- rozbieżności wymiarowe rzędu jednego piksela odrysu (4 cm na elewacji) nie są
  celem korekt, dopóki nie zmieniają czytelności kompozycji.

### Rama podcienia: grubość, skos, głębokość

Policzki podcieni są odrysowane z rzutu poddasza na **0,64 m** (24–25 px przy
37,77 px/m), a elewacje potwierdzają to na 0,59–0,63 m; rzut parteru kreskuje
je węższe (0,48 m) i ta rozbieżność jest zapisana w `MarcowkiSourceEvidence`,
a nie narysowana. Ściana okapowa jest więc elementem z trzema pryzmami: policzek
północny, ściana 0,44 m, policzek południowy — `portalWall` w
`MarcowkiVisualModelV1`. Rama ma tę samą szerokość, bo **jest** policzkiem
wyprowadzonym ponad okap, i spotyka się z nim w skosie: narożnik wewnętrzny
leży 0,30 m poniżej okapu na wewnętrznym licu policzka
(`GABLE_FRAME_INNER_CORNER_Y`), tak jak rysują to obie elewacje.

Każdy pas ramy to trzy ściany: lico (czworokąt wypukły ze skosem,
`GablePanelGeometry`, 5 mm przed licem podcienia, żeby nie dzielić płaszczyzny
z policzkiem), podniebienie i wierzch (płaskie, nachylone — `RoofFacetGeometry`
należące do **elementu ramy**, nie do dachu; wierzch leży 1 cm pod połacią).
Tył ramy to panele szczytu, boki to policzek i drugi pas. Dwie połacie dachu
pozostają dokładnie tym, czym były.

### Balkon południowy: zasięg ze źródła

Rzut poddasza kropkuje podłogę podcienia południowego dopiero od linii
x = 3,35 m (na ściance garderoba–pokój), elewacja frontowa zaczyna opaskę
3,2 m od lica zachodniego, a rzut parteru w otwartej części rysuje rabatę.
Płyta balkonu, opaska i balustrada zaczynają się więc na `X_SOUTH_BALCONY_WEST`
= 3,39 m, a zachodnia część podcienia jest otwarta przez dwie kondygnacje.
STAGE-013C zalało całą szerokość, żeby nie zostawić szczeliny w policzku; teraz
szczelinę zamykają **kawałki stropu wewnątrz policzków** (trzy dodatkowe
prostokąty elementu `strop-nad-parterem`, sięgające lica podcienia), więc płyta
mogła stanąć tam, gdzie stawia ją źródło. Opaska południowa kończy się na
zewnętrznym licu ściany działowej, gdzie tę samą linię na tym samym poziomie
przejmuje stropodach garażu. Balustrada południowa skręca na wolnym końcu
balkonu, bo rzut rysuje tę krawędź linią.

### Szkło techniczne i rola prezentacji poza domeną

Renderer dostał drugi materiał: `TechnicalMaterial.buildGlass` — `LIT`,
`TRANSPARENT`, bez zapisu głębi, dwustronny, z alfą premnożoną w shaderze.
Wartości (`GlassPresentation.ALPHA` = 0,38, barwa neutralna, lekko chłodna) są
czystymi liczbami testowanymi na JVM. Szyby otworów są szkłem z **typu
prymitywu** (`OpeningPanelGeometry` → `MeshStyle.GLAZING`) — ta reguła zostaje.
Balustrada jest jednak `WallGeometry` grubości 2 cm i żaden fakt geometryczny
nie odróżnia jej od cienkiej ścianki; dlatego istnieje
`reference/visual/VisualSurfaceRole` (`OPAQUE_STUDY`, `GLASS_STUDY`)
i `MarcowkiVisualPresentation.surfaceRoles` — mapa **po `BuildingElementId`**,
trzymana obok modelu referencyjnego w `src/debug`. Renderer składa obie
odpowiedzi w jednej czystej funkcji `surfaceRoleOf(mesh, roles)`. Domena
i `geometry/` nie wiedzą o rolach (pilnuje `C013D-06`); `Building`
i `BuildingGeometry` są bajt w bajt tym, czym byłyby bez mapy. Roli
„cecha rozpoznawcza" celowo nie ma — trzeci kolor byłby złamaniem reguły
z `CLAUDE.md`.

Zaznaczenie zachowuje przezroczystość (`SELECTED_ALPHA` = 0,55). **Picking
przechodzi przez szkło**: pass pickingu Filamenta renderuje tylko powierzchnie
kryjące, co sprawdzono na urządzeniu z zapisem głębi wyłączonym i włączonym —
dotknięcie balustrady odpowiada elementem za nią. To celowa lektura „szkła
technicznego": patrzy się przez nie i dotyka przez nie. Osobny wybór szyb
i balustrad wymaga innej ścieżki pickingu i należy do produkcjonizacji
renderera.

### Pas okapowy: osobna geometria, dach bez zmian

Obie elewacje boczne rysują 0,24 m pas między dachówką a tynkiem na całej
długości 14,6 m — krawędź dachu bez okapu. Reguła: **grubości nie dostaje
połać**, bo dwie połacie są jedyną geometrią uzgodnioną z liczbą ze strony
(150,4 m² wobec 150,57 m²). Krawędź rysuje osobny element `pas-okapowy`: dwa
pryzmy `WallGeometry` 4 cm przed licem ścian okapowych i policzków, z górą na
linii okapu. Ma zasięg `WholeBuilding` i rodzaj `OTHER`, jak rama — nie `ROOF`
— bo niezmiennik ciągłości wymaga identycznego zasięgu rzutu w każdym stanie
widoczności, a listwa wystająca 4 cm poza ściany i znikająca z dachem by go
przesunęła. Po zdjęciu dachu czyta się jako zwieńczenie ściany, na której stoi.
`C013D-08` sprawdza, że dach ma nadal dwie połacie o niezmienionej powierzchni
i wierzchołkach.

### Kominy ponad dachem

Dwa słupy 0,60 × 0,60 m, oba na x = 5,45–6,05, na z = 4,40–5,00 (nad kominkiem
salonu) i 8,90–9,50 (nad kotłownią), z górą 0,24 m nad kalenicą. Trzy widoki
się zgadzają: elewacja od garażu pokazuje oba, front i ogród po jednym (dwa
słupy na jednym x nakładają się), przekrój ten sam słup 1,5–2,1 m na wschód od
kalenicy. STAGE-013 nie rysowało komina, bo rzut nie daje szybu; słupy są
umieszczone z elewacji, które je rysują, a szyb pod dachem nadal nie istnieje.
Rodzaj `ROOF`: znikają z dachem, z którego wyrastają, bo słup nad zdjętym
dachem wisiałby w powietrzu. Wysokość budynku 8,27 m liczy się nadal do
kalenicy, nie do komina.

### Presety dowodowe

Doszły `FRONT_SIGNATURE` (front z południowego zachodu, prawie z poziomu: lewa
noga ramy do ziemi, podcień otwarty na dwie kondygnacje, opaska od połowy
szczytu przez garaż), `FULL_REAR_AXON`, `GLASS_RAILING_CLOSEUP` (kadrowany na
balustradzie północnej) i `ROOF_FASCIA_CLOSEUP` (kadrowany na ramie północnej,
z zachodu: skos, podniebienie i pas okapowy w jednym ujęciu). `PresetFocus`
wskazuje element przez `DebugModel.focusElementId`; fikstura syntetyczna
odpowiada `null` i kadruje całość.

### Czego świadomie nie zrobiono

Trzonów kominowych pod dachem, grubości połaci, pochwytu balustrady, ram okien
i słupków, materiałów elewacji. Wszystko w `MarcowkiSourceEvidence.notModelled`.
Pas okapowy przy jednym kolorze czyta się z reliefu, nie z ciemnej barwy, więc
jest subtelniejszy niż na elewacji — to świadoma granica monochromu.

## Dekompozycja, krawędzie i prezentacja studyjna (STAGE-013E)

Etap odpowiada na czwartą ocenę OWNER-a (`RETEST_PENDING` po 013D): schody nie
odtwarzały rzutu, pełna bryła była pocięta liniami, „Bez dachu" nadal pokazywało
dach, a żaden pokój nie miał drzwi. Przebiegł jako trzy ograniczone fazy
audyt→poprawka (A: techniczna, B: rubryka I1–I12 z eksperymentem
renderowania, C: ruch i czytelność) i zatrzymał się po fazie C. Samoaudyt nie
jest akceptacją OWNER-a.

### Dekompozycja a własność semantyczna

Własność (`BuildingElementScope`) i przynależność wizualna nie zawsze się
pokrywają: rama szczytowa i pas okapowy są elementami całego budynku, a przy
zdjętym dachu rysują jego obrys w powietrzu. Rozstrzyga to
`presentation/DecompositionProfile` — źródłowo neutralna mapa
`BuildingElementId → DecompositionGroup` (dziś jedna grupa: `ROOF_ENVELOPE`)
trzymana obok modelu referencyjnego w `MarcowkiVisualPresentation`, tak jak role
szkła. Kontrakt: profil nakłada się **po** `BuildingElementSelection.kt`
i może tylko zawężać odpowiedź domeny; stan „wszystko" jest odpowiedzią domeny
co do joty; własność, pokoje i picking pozostają nietknięte. Renderer dostaje
gotowy zbiór identyfikatorów przez `DebugModel.visibleElementIds` i nie zna
żadnej reguły. Syntetyczny dom deklaruje `DecompositionProfile.NONE`.

### Warstwa zdjęta znika

Widmowa siatka warstwy usuniętej (013B) została wycofana: OWNER widział w niej
dach, który miał zniknąć. Został jeden materiał liniowy z testem głębi;
`setVisibleElements` przyjmuje jeden zbiór i dla elementu spoza niego nie rysuje
nic. Ciągłość modelu pilnują testy podzbioru (C013E-01), nie obraz.

### Krawędzie cech, nie szwy

Kontur jest liczony w `BuildingRenderMesh` z upieczonych wielokątów: każda
krawędź jest dzielona w wierzchołkach leżących na niej (pełnowysoki narożnik
filara i krótki narożnik nadproża to ta sama linia ściany o różnych końcach),
a kawałek, którego dwie sąsiednie ściany mają wspólną normalną, jest szwem
jednej powierzchni i nie jest rysowany. Zostają załamania, brzegi otwarte
i ościeża otworów; przekątne triangulacji nigdy nie były krawędziami.
Deterministyczne, w obrębie jednej siatki, bez Filamenta — testowalne na JVM
(C013E-04).

### Schody i drzwi wewnętrzne

Schody: trzy proste biegi na pełną szerokość pasma klatki (4 + 5 + 4 stopnie)
i dwa zabiegi w każdym narożu (kwadrat cięty po przekątnej od naroża rdzenia),
zamiast równych kroków wzdłuż linii środkowej. Pierwszy bieg wychodzi z holu
przez przerwę w ściance wschodniej holu (rdzeń → kotłownia), ostatni wchodzi do
korytarza przez przerwę w jego ściance wschodniej (garderoba → rdzeń); obie
przerwy są odrysowane, obie ścianki to jeden element w dwóch pryzmach.
Drzwi wewnętrzne: dwanaście, odczytane z łuków otwierania na obu rzutach —
pozycje `SOURCE_TRACED`, wymiary (0,80/0,90 × 2,00) `DISPLAY_ASSUMPTION`.
Każde to `WallOpening` w ściance plus element `DOOR` z dwoma pokojami tej
samej kondygnacji i własną taflą (tak jak drzwi zewnętrzne). Ścianka
korytarz–pokój 3 (z = 3,70, x 3,93–5,20), której 013B nie miał, została
dorysowana. Ścianki poddasza w poprzek połaci są cięte tam, gdzie dach mija
wysokość 2,66: pośrodku stoją do stropu, przy okapach do połaci — dlatego drzwi
mieszczą się na pełną wysokość.

### Prezentacja studyjna

`RenderStyle` ma dwóch kandydatów na tej samej geometrii: **CLAY** (jasne
tło, matowe ciepło-neutralne powierzchnie, słońce z cieniami, SSAO, ciemne
krawędzie cech, delikatna siatka) i **LINE_STUDY** (dotychczasowe ciemne tło,
te same krawędzie, bez cieni). Po porównaniu na urządzeniu domyślny jest CLAY;
LINE_STUDY zostaje jako wariant zapasowy. Styl zmienia kolory instancji
materiałów, skybox i przebiegi widoku — nigdy siatki, bufory ani zbiór encji
(C013E-09). Szkło jest neutralnie szare (bez błękitu), półprzezroczyste, bez
refrakcji; na jasnym tle czyta się jako ciemniejsza tafla. MSAA 4× i FXAA
wygładzają linie. Kamera nie schodzi pod płaszczyznę siatki (minimalny pitch
+2°), żeby siatka nie czytała się jako unosząca się płachta.

### Czego świadomie nie zrobiono

Kierunku otwierania skrzydeł, poręczy i balustrady wewnętrznej przy pustce
klatki, ściany rdzenia od strony holu (rzut nie rozstrzyga), wycinania otworów
w połaci, materiałów. Analizatora nadal nie ma.

## Modułowe pokrycie dachu — dachówka studyjna (STAGE-013F)

Etap zlecony przez OWNER-a przed zamknięciem `GATE-3D-SHAPE-01-R5`: dach ma
czytać się jako powtarzalne, zachodzące na siebie dachówki (łuski), a nie jako
płaska płaszczyzna — nadal monochromatycznie, lekko i rozkładalnie. Przebieg:
implementacja → samoaudyt A1–A12 (23/24) → korekta uznana za niepotrzebną →
`NOT_NEEDED`. Samoaudyt nie jest akceptacją OWNER-a.

### Pokrycie jest wyłącznie prezentacją

Kanoniczny dach nie zmienił się o bajt: jeden element `ROOF`, dwie
`RoofFacetGeometry`, te same wierzchołki i te same 150,4 m², które uzgadniają
się z podaną powierzchnią. Nie ma elementu „dachówka", nie ma
`BuildingElementId` na kafelek, nie ma rodzaju elementu, kosztu ani schematu
persystencji dla pokrycia. Dotknięcie dachówki to dotknięcie dachu
(`marcowki-v1-dach`), potwierdzone na urządzeniu.

Warstwa mieszka w dwóch plikach `src/debug`:

- `presentation/RoofCover.kt` — słownik prezentacji: `RoofCoverStyle` (dziś
  tylko `CURVED_TILE`; drugi styl musi zostać najpierw wyłożony w generatorze,
  bo `when` jest wyczerpujące), `RoofCoverSpec` (moduł: szerokość, długość,
  krycie, zaokrąglenie ogona, wybrzuszenie, wyniesienie, stopień — wszystko
  w metrach wzdłuż normalnej połaci, z walidacją: krycie < długość, ogon <
  zakład), `RoofCoverBlocker` (wypukły wielokąt na rzucie, którego żadna
  dachówka nie przekracza) i `RoofCoverProfile` — mapa
  `BuildingElementId → RoofCoverSpec` plus lista blokerów, trzymana obok modelu
  referencyjnego tak samo jak role szkła i `DecompositionProfile`. Syntetyczny
  dom deklaruje `RoofCoverProfile.NONE`.
- `render/filament/RoofCoverMesh.kt` — czysty adapter na JVM bez importów
  Filamenta: `RoofCoverGenerator.cover(facet, ordinal, spec, blockers)`
  i `BuildingGeometry.roofCoverMeshes(profile)`.

### Generator jest ogólny względem połaci

Wejściem jest jedna płaska `RoofFacetGeometry`. Generator wyznacza własną bazę
połaci — normalną (odwróconą w górę), kierunek najstromszego wzniesienia
i kierunek w poprzek — bez wiedzy o kalenicy, dwuspadowości, `MarcowkiPlanGrid`
ani liczbie połaci (TILE013F-03: dach syntetyczny z kalenicą wzdłuż X, trójkąt
kopertowy, dach pulpitowy obrócony o 30° i połać płaska). Rzędy biegną
w poprzek połaci od jej najniższej krawędzi do najwyższej na **rozstawie
dzielącym spadek dokładnie** (nie większym niż krycie), więc górny rząd kończy
się na kalenicy bez skrawka. Każda dachówka jest przycinana do obrysu na swoim
rzędzie: wierzchołki ogona (o nieco różnym `t`) dzielą część wspólną przedziałów
obrysu na obu końcach tego zakresu — dla obrysu wypukłego przedział ten leży
w połaci na całej długości — a wierzchołki głowy przedział na swoim `t`.
Dachówka węższa niż 25 % modułu jest pomijana. Powierzchnie zakrzywione są
poza kontraktem, bo `RoofFacetGeometry` jest z definicji płaska.

### Dachówka: dziesięć wierzchołków, osiem trójkątów

Pięć wierzchołków na ogonie i pięć na głowie: beczkowe wybrzuszenie
w poprzek (parabola, `camber`), zaokrąglony ogon (narożniki cofnięte
o `tailRound` po łuku), przechył wzdłuż długości — głowa `lift` nad płaszczyzną,
ogon `lift + step`, bo ogon leży na rzędzie poniżej. Bez spodu, bez bryły:
płaszczyznę pod dachówkami rysuje sama połać. Normalne liczone analitycznie
z pochodnych powierzchni (`n − f_t·u − f_s·a`), więc beczka łapie światło bez
tekstury. Rzędy są wyrównane (bez przesunięcia o pół modułu), żeby krawędź
dachówki nigdy nie musiała minąć grzbietu tej pod nią; prześwit ogona nad
głową rzędu niżej wynosi `step · rozstaw / długość` ≈ 14 mm. Trójkąt o polu
poniżej 1 mm² (mocno przycięty segment) nie jest emitowany.

Moduł Marcówek (`MarcowkiVisualPresentation.roofCoverSpec`,
`DISPLAY_ASSUMPTION` „Moduł pokrycia dachu"): 0,25 × 0,40 m, krycie 0,30 m,
ogon 0,05 m, wybrzuszenie 0,025 m, wyniesienie 0,01 m, stopień 0,02 m
(maksymalne wyniesienie 0,055 m — poniżej 0,08 m okien połaciowych).

### Jedna partia na połać, nigdy encja na dachówkę

`FilamentModelRenderer.uploadRoofCover` wgrywa całą partię jako **jedną**
encję z trzema buforami wierzchołków (pozycja, ramka styczna, `UV0`) i jednym
buforem indeksów, rejestruje ją pod identyfikatorem dachu w tych samych mapach
co połacie (`entitiesByElementId`, `elementIdByEntity`, `instanceByEntity`)
i w zbiorze `roofCoverEntities`, który rozstrzyga tylko kolor bazowy. Żadnego
konturu: dachówki czytają się z reliefu, cienia i SSAO, a dwa tysiące
obrysowanych czworokątów byłoby powrotem siatki, którą 013E usunęło.

Marcówki: 2 partie (po jednej na połać), 2016 dachówek (2124 przed wycięciami),
20 160 wierzchołków, 15 984 trójkątów — w budżecie ≤ 4 encji i ≤ 25 000
trójkątów (TILE013F-08).

### Wycięcia: blokery z geometrii, nie z drugiej listy współrzędnych

Dwa kominy i trzy okna połaciowe są blokerami zbudowanymi
z `LocalBounds` prymitywów, które model już rysuje pod tymi identyfikatorami
(`RoofCoverBlocker.aroundPlan(bounds, 0.03 m)`); w `MarcowkiVisualPresentation`
nie ma żadnej współrzędnej powtórzonej z siatki (TILE013F-07 sprawdza to
tekstowo). Dachówka, której rzut nachodzi na bloker (test osi rozdzielającej),
nie jest kładziona; luka jest co najwyżej jednomodułowym pierścieniem i czyta
się jak obróbka blacharska. Wypukłość blokera jest założeniem: wklęsły byłby
badany po otoczce.

### Dekompozycja: to samo id, ta sama ścieżka

Partia odpowiada identyfikatorem dachu, więc `setVisibleElements` nie ma o niej
ani słowa: „Wszystko" i „Bez poddasza" pokazują dach z pokryciem, „Bez dachu"
i „Bez dachu i poddasza" nie zostawiają ani dachówki, ani widma, ani linii
(TILE013F-09, dowody na urządzeniu). `DecompositionProfile` nie wie
o pokryciu; nie ma drugiej prawdy o widoczności.

### Szew pod przyszłą animację

Nie ma animacji. Jest deterministyczna tożsamość prezentacyjna każdej
dachówki (`RoofCoverTile`: połać, rząd, kolumna, porządek, środek na
płaszczyźnie, faza w `[0,1)` z mieszania całkowitoliczbowego — ta sama na
każdej JVM i każdym telefonie) oraz sygnał na wierzchołek w `UV0`: faza
i położenie wzdłuż dachówki (0 ogon, 1 głowa). Żaden materiał tego dziś nie
czyta; przyszły ruch łuskowy to przemieszczenie w vertex shaderze na tym
sygnale, bez zmiany liczby encji, map identyfikatorów ani domeny.

### Styl

`RenderStyle.roofCover` to ta sama neutralna szarość o stopień ciemniejsza od
powierzchni (CLAY 0,62 wobec 0,74; LINE_STUDY 0,46 wobec 0,55), bez odcienia,
bez tekstury, bez mapy normalnych, tym samym materiałem `TechnicalMaterial`.
Preset `ROOF_COVER_CLOSEUP` („Pokrycie dachu") kadruje wschodnią połać od
strony słońca.

### Czego świadomie nie zrobiono

Gąsiora na kalenicy (kalenica to szereg grzbietów obu połaci — czysta, ale
niezwieńczona), przesunięcia rzędów, drugiego stylu pokrycia, katalogu
pokryć w UI, animacji, koloru, refleksów i profilu producenta. Blokery
wklęsłe i połacie wklęsłe są obsługiwane po otoczce, nie dokładnie.

## Okna, schody i powłoka produktu (STAGE-013G)

Etap zlecony przez OWNER-a po przeglądzie STAGE-013F: model „w miarę w
porządku", ale przeszklenia czytają się jak puste dziury, a schody wchodzą
w ścianę; do tego powłoka aplikacji nie jest produktem, tylko prototypem
z kart. Dwie równorzędne części: korekta modelu i przebudowa powłoki.
Przebieg: implementacja → audyt modelu → audyt UI (z Mobbin) → jedna
poprawka kalibracji kamery i okładki pierwszej klatki → audyt łączny.
Samoaudyt nie jest akceptacją OWNER-a.

### Schody: ściana pod biegiem, nie przez bieg

Rzut parteru rysuje spiżarnię (5) dokładnie pod górnym biegiem, który rzut
poddasza prowadzi na zachód do korytarza; na rzucie parteru nie ma tam
stopni, bo bieg jest ponad płaszczyzną cięcia. Ściany spiżarni istnieją
w źródle (wraz z drzwiami z holu) i **nie zostały usunięte** — zostały
zatrzymane pod biegiem. `MarcowkiVisualModelV1.groundPartitionRun` tnie
każdą ściankę parteru w miejscach, gdzie krawędź stopnia ją przecina,
buduje każdy kawałek do wysokości kondygnacji albo do spodu najniższego
stopnia nad nim minus `STAIR_SOFFIT_CLEARANCE` (0,02 m,
`DISPLAY_ASSUMPTION`), i scala sąsiednie kawałki o tej samej górze.
Odcinek krótszy niż grubość ścianki jest wchłaniany przez sąsiada na
niższej z dwóch gór — żaden pełnowysoki słupek nie zostaje obok biegu.
Stopnie są układane raz (`stairTreads()`) i czytane dwa razy: przez element
schodów i przez ścianki, więc ścianka może uniknąć tylko stopnia, który jest
rysowany.

Trzy krawędzie klatki — wschodnia krawędź szybu oraz wschodnia i północna
ściana rdzenia — były odrysowane 1–2 cm od lic ścian, na których leżą
(lico wewnętrzne ściany wschodniej, lica ścianki wschodniej i południowej
spiżarni). Są teraz **wyprowadzone z tych lic** (`STAIR_EAST_X`,
`STAIR_CORE_EAST_X`, `STAIR_CORE_NORTH_Z` jako `val` z `exteriorFace` /
`partitionFace`), a licznik odrysowanych linii jest o trzy mniejszy. Efekt:
jedyną ścianą pod biegiem jest wschodnia ścianka spiżarni (dwa pryzmaty,
2,32 m pod pierwszym stopniem biegu północnego), 52 pryzmaty ścian, 140
prymitywów; `G013-01` sprawdza, że żaden pryzmat ściany nie przecina
żadnego stopnia w trzech wymiarach.

### Ramy okien: prezentacja obok modelu, jak pokrycie dachu

Naga półprzezroczysta tafla w ościeżu czyta się z odległości telefonu jak
dziura. To, co robi z dziury okno, to rama i podział na skrzydła — jedyna
linia, którą każda elewacja rysuje wokół każdej szyby. Kanoniczna dziura,
tafla i ściana nie zmieniły się o bajt; ramy żyją w dwóch plikach
`src/debug`, na wzór `RoofCover`:

- `presentation/OpeningFrame.kt` — `OpeningFrameSpec` (szerokość listwy,
  głębokość przez płaszczyznę tafli, maksymalna szerokość skrzydła albo
  `null` dla bramy i drzwi) i `OpeningFrameProfile` — mapa
  `BuildingElementId → OpeningFrameSpec` obok modelu referencyjnego.
  Marcówki: 11 otworów elewacyjnych (`MarcowkiPlanGrid.facadeOpenings`,
  czyli wykaz bez drzwi garaż–kotłownia) plus trzy okna połaciowe pod
  identyfikatorem dachu; brama garażowa bez skrzydeł; drzwi wewnętrzne
  **bez ram** — dziura z płatem, tak jak bez klamki. Syntetyczny dom:
  `OpeningFrameProfile.NONE`.
- `render/filament/OpeningFrameMesh.kt` — generator ogólny względem tafli:
  pracuje w płaszczyźnie wielokąta (prostokąt okna, pięciokąt przeszklenia
  szczytu z nadprożem po połaci, okno połaciowe na spadku), listwa wzdłuż
  każdej krawędzi jako pas w odległości ≤ szerokości od tej krawędzi
  i bliżej niej niż sąsiednich (skos w narożach bez nakładania), słupki
  tam, gdzie skrzydło przekroczyłoby maksimum (470 → cztery, 234 → dwa,
  drzwi → jedno). Wynik to zwykłe `BuildingRenderMesh` (`SOLID`, kontur
  krawędzi cech, id tafli) wytłoczone przez nowy `bakeExtrudedPolygons`
  w `BuildingRenderMesh.kt`, więc rama jest oświetlana, kreskowana,
  ukrywana, podświetlana i pickowana jak ściana — renderer nie ma o ramach
  ani słowa (`G013-05`).

Liczby ramy (0,07 × 0,08 m, skrzydło ≤ 1,20 m) są `DISPLAY_ASSUMPTION`
„Ramy i słupki okien"; wpis `notModelled` o ramach został zastąpiony
pochwytem balustrady, którego nadal nie ma.

### Powłoka: dom jest obiektem głównym

Ekran startowy (`DashboardScreen`) przestał być siatką pustych kart:
kompaktowy nagłówek projektu, **bohater** — model w wariancie
`ViewportMode.HERO` (bez sterowania, obrót jednym palcem, dotknięcie
otwiera ekran modelu; w wariancie release ten sam slot wypełnia
zarezerwowana rycina) — na pozostałej wysokości ekranu, pod nim pasek osi
czasu (`TimelineStrip`: pusty tor z pustymi znacznikami jako kształt, nie
dane) i jeden pasek pieniędzy (`SummaryStrip`: trzy figury, jedno zdanie,
jedna akcja). Szuflada (`AppDrawerSheet`) jest launcherem: grupy *Projekt /
Finanse / Zasoby* z `AppSectionGroup`, ustawienia osobno na dole; „Dashboard"
nazywa się „Start". Nawigacja do sekcji ma jedno wejście
(`navigateToSection`) dla szuflady i dla linków ze startu. `MetricCard`
i cztery puste KPI zniknęły.

Ekran modelu (`ModelScreen`) jest pełnoekranowy: viewport na całej
wysokości i dok sterowania pod nim — segmentowany wybór stanu widoczności
(Całość / Bez dachu / Bez poddasza / Parter), przewijany rząd ujęć,
segmentowana para stylów, przycisk kamery i przełącznik szczegółów
(źródło modelu, gest, panel wiarygodności — zwinięte domyślnie). Wybrany
element pokazuje pigułka nad viewportem, nie stały wiersz tekstu.

Dwie konsekwencje techniczne pełnoekranowego viewportu:

- **Pole widzenia rozpina krótszy bok viewportu**
  (`FilamentModelRenderer.applyCamera`: `Fov.HORIZONTAL` przy aspekcie < 1).
  Presety kadrowały względem pionowego kąta w prawie kwadratowym viewporcie;
  w wysokim dom zajmował trzecią część szerokości i był ucinany po bokach.
  Marginesy presetów zostały przekalibrowane, przesuw dwoma palcami liczy
  metry na piksel po krótszym boku.
- **Okładka do pierwszej sensownej klatki.** Filament kompiluje programy
  materiałów leniwie na wątku sterownika i nie rysuje niczego programem,
  który nie jest gotowy, więc „pierwsza klatka" była czarna. Renderer prosi
  trzy materiały o kompilację wariantów sceny (`DIRECTIONAL_LIGHTING |
  SHADOW_RECEIVER`) z góry i zgłasza `onReady` po ostatniej kompilacji *i*
  wyrenderowanej klatce; viewport do tego czasu zakrywa powierzchnię tłem
  stylu i zdejmuje okładkę z zanikiem. Zabezpieczenie: okładka zdejmuje się
  sama po 15 s, gdyby sterownik nigdy nie odpowiedział.

Dwa viewporty (start i model) były w tym etapie dwoma silnikami żyjącymi w dwóch `SurfaceView`
(STAGE-013H scalił oba ekrany w jedną przestrzeń z jednym silnikiem),
każdy dokładnie tak długo, jak jego kompozycja — bez singletona, zgodnie
z regułą STAGE-012; kosztem jest ponowna kompilacja programów przy zmianie
ekranu.

### Audyt z Mobbin

Sześć zapytań (ekran główny z obiektem-bohaterem, viewer 3D z dolnym
paskiem, szuflada z grupami, przegląd projektu z pustym stanem, oś postępu
pod bohaterem) i wzorce, z których wzięto zasady, nie wygląd: obiekt
wyśrodkowany na spokojnym tle z jedną akcją pod nim (Crate & Barrel
„Object", Tesla, My BMW), status i „co dalej" w jednym kompaktowym pasku
zamiast siatki (Waking Up, Calm), szuflada z nagłówkiem i grupami oraz
ustawieniami na dole (X, Spotify, Oura), pusty stan zachowujący kształt
przyszłej treści zamiast martwych kart (Klarna Insights, Vipps), tor postępu
pod bohaterem (Instacart, Grab). Nic nie jest kopią.

### Czego świadomie nie zrobiono

Pochwytu balustrady, profilu ramy (ramy są prostokątnymi listwami jednego
koloru), koloru stolarki, bramy garażowej jako litej płyty (nadal tafla
z ramą bez skrzydeł), wspólnego silnika między ekranami, ikon w szufladzie
(zestaw `material-icons-core` nie ma pasujących ikon, a rozszerzony to
zależność bez potrzeby), danych na osi czasu i w pasku pieniędzy
(pozostają puste stany, bo danych nie ma).

## Immersyjna przestrzeń robocza (STAGE-013H)

Etap zlecony przez OWNER-a po odrzuceniu powłoki ze STAGE-013G: model nadal
mieszkał w zaokrąglonej karcie, a ekran był tablicą prostokątnych bloków
informacji ułożonych wokół modelu. Ten etap nie poprawia kart — usuwa je.
Dom jest ekranem; wszystko, co nie jest domem, jest chromem przy krawędziach,
które otwiera się na żądanie i zamyka gestem wstecz. Przebieg: pierwsza
implementacja → AUDIT-1 (Impeccable, niezależny agent) → poprawki → AUDIT-2
(Emil Kowalski, motion, niezależny agent) → poprawki → dowody na
`emulator-5570`. Samoaudyt nie jest akceptacją OWNER-a.

### Jedna przestrzeń zamiast startu, karty i ekranu modelu

Sekcje `Dashboard` i `Model` zostały scalone w jedną: `AppSection.Home`
(trasa `home`, etykieta „Dom") jest ekranem startowym **i** ekranem modelu.
Nie ma już drogi „start → karta → Model 3D": `WorkspaceScreen`
(`ui/screens`) składa cztery warstwy w jednym `Box`:

1. **Kanwa** — wariantowa `WorkspaceModel` (`internal`, osobno w `src/debug`
   i `src/release`): w debugu `FilamentCanvas` na całym ekranie, bez kształtu,
   obramowania i marginesów, pod paskami systemowymi włącznie; w release
   zarezerwowana rycina na tle motywu. Nad kanwą oba warianty rysują ten sam
   `WorkspaceScrims` — jeden górny gradient, który osadza jasne ikony paska
   statusu na jasnym tle studyjnym.
2. **Górna krawędź** — `WorkspaceTopChrome`: jedna szklana pigułka
   z przyciskiem szuflady i nazwą projektu. Bez `TopAppBar`, bez statusu
   (stan budowy mówi raz oś czasu, która go posiada).
3. **Prawa krawędź** (tylko debug) — `ToolDock`: pionowa szklana szyna
   z pięcioma glifami (Warstwy, Ujęcia, Styl, Wyśrodkuj kamerę, Źródło
   modelu). Panel narzędzia rozwija się **obok** szyny, z jej górnego-lewego
   narożnika, a nie jako arkusz nad domem; dom pozostaje widoczny po lewej.
4. **Dolna krawędź** — `TimelineRail`: szkło od krawędzi do krawędzi, zwinięte
   do jednego wiersza (nazwa, stan „Brak zaplanowanych etapów", tor, szewron),
   rozwijane dotknięciem albo przeciągnięciem w górę do dwóch zdań z akcjami
   (Planuj etapy → sekcja Oś czasu, Dodaj koszt → sekcja Koszty).

Do tego kontekstowy **inspektor** (`ContextPanel`, lewy dolny róg, tylko
debug): nazwa wybranego elementu, rodzaj i kondygnacja z domeny, pomieszczenia
z `roomIds`. Pojawia się tylko, gdy coś jest wybrane, i znika z wyborem.
Pozostałe sekcje mają własny `SectionTopBar`; `BuildPlanApp` nie ma już
paska aplikacji nad wszystkim, bo pasek nad domem znów robiłby z domu treść
w ramie.

Konsekwencja techniczna: **jeden silnik Filamenta na całą przestrzeń**
zamiast dwóch (start i model) ze STAGE-013G — kompilacja programów przy
przejściu między ekranami zniknęła, bo zniknął drugi ekran. Silnik nadal
żyje dokładnie tak długo, jak kompozycja kanwy (bez singletona); po zmianie
konfiguracji jest odtwarzany pod okładką, a kamera wraca z `rememberSaveable`.

### Jeden otwarty element chromu i jeden przebieg gestu wstecz

`ui/workspace/WorkspaceChromeState` trzyma jedną wartość
`WorkspaceSurface` (`None` / `Timeline` / `Tool(key)`) z `Saver`-em na
odtworzenie. Otwarcie osi czasu zamyka panel narzędzia i odwrotnie — dwie
rzeczy otwarte naraz zakryłyby większość modelu i walczyłyby o ten sam kciuk.
Gest wstecz w `WorkspaceScreen` najpierw zamyka otwarty element; gdy nic nie
jest otwarte, `WorkspaceModel` zwalnia wybór elementu; dopiero potem wstecz
opuszcza ekran. Otwarta szuflada ma własny `BackHandler` w `AppShell`
(skomponowany w treści szuflady, więc nad handlerami sekcji): Material 3
`ModalNavigationDrawer` w tej wersji nie zamykał się gestem wstecz i aplikacja
wychodziła z otwartą szufladą — sprawdzone na urządzeniu w obu trybach ruchu.
Reguły stanu chromu są w `WorkspaceChromeStateTest`.

### Szkło: tint i obwódka, świadomie bez rozmycia

`ui/components/Glass.kt` — `GlassSurface` to jedyny materiał chromu:
przyciemniony tint `#0F1113` o alfie 0,84 i włoskowa obwódka jaśniejsza
u góry (`GlassRimHigh` → `GlassRimLow`). **Nie rozmywa tła.** Filament
rysuje na własnym `SurfaceView`, którego okno nie może próbkować, więc
rozmycie byłoby albo udawane, albo kosztowałoby drugi render sceny;
czysty tint nad ruchomym modelem jest uczciwszy i tańszy. Alfa jest ustawiona
pod tekst, nie pod efekt: nad jasnym tłem studyjnym szkło składa się do
ok. `#2E2F31`, na którym przygaszony atrament trzyma ponad 4,5:1 (cieńsze
szkło z pierwszej implementacji, 0,68, wyglądało bardziej jak szkło i gasiło
każdą niewybraną etykietę — AUDIT-1). Tafla jest też **lita dla palca**:
Compose przepuszcza dotyk przez każdy obszar bez własnej obsługi wskaźnika,
a pod każdą taflą jest kanwa, która obróciłaby kamerę albo wybrała ścianę
spod tytułu panelu; `GlassSurface` bierze więc udział w hit-teście na całej
powierzchni, niczego nie konsumując, więc jej przyciski, listy i uchwyt
przeciągania działają, a model za nią zostaje w spokoju. Żadnej zależności
na „liquid glass" nie dodano.

Glify szyny (`WorkspaceGlyphs`) są rysowane ręcznie jako `ImageVector`
(kreska 1,6, zaokrąglone złączenia) — zestaw `material-icons-extended` to
kilka megabajtów dla czterech symboli, a glif warstw zbudowany z tej samej
aksonometrii, w której widać dom, należy do tego produktu.

### Ruch: jedna polityka, jeden zegar

`ui/workspace/MotionPolicy.kt` jest jedynym miejscem, które nazywa czasy
i krzywe: wejście 220 ms (M3 emphasized-decelerate), wyjście 150 ms
(emphasized-accelerate), osadzenie stanu 180 ms, podróż kamery 300 ms.
Każde przejście powłoki pyta politykę o spec zamiast podawać własną liczbę.
`rememberSystemMotionPolicy()` czyta `ANIMATOR_DURATION_SCALE` i obserwuje
jego zmiany: Compose animuje własnym zegarem i **ignoruje** systemową skalę
animacji, więc dostępnościowe „Usuń animacje" trzeba honorować samemu —
każdy spec staje się wtedy `snap()`, a każde `EnterTransition`/`ExitTransition`
staje się `None`. Skala 10× (developerska) nadal animuje, bo prosi
o zobaczenie ruchu, nie o jego utratę. Reguły są w `MotionPolicyTest`.

Ruchy, które istnieją, i po co:

- **Panel z szyny** — `AnimatedContent` z `scaleIn/Out` 0,92→1 i fade,
  `TransformOrigin(1f, 0f)` (prawy-górny narożnik przy szynie), więc panel
  rośnie z przycisku, który go otworzył; zmiana narzędzia podmienia treść
  w miejscu z `SizeTransform`.
- **Oś czasu** — tor zostaje na miejscu, szczegół rozwija się pod nim
  (`expandVertically` z góry); szewron obraca się o 180°.
- **Inspektor** — rośnie z lewego-dolnego narożnika, skąd jest zakotwiczony.
- **Podróż kamery** — `ModelScene.applyPreset` nie przeskakuje już do
  ujęcia: `OrbitCameraState.between(from, to, t)` interpoluje framing
  (yaw krótszą drogą, reszta liniowo) przez `Animatable` w 300 ms; stan
  końcowy to dokładnie framing presetu, więc ujęcia zostają odtwarzalne;
  palec na kanwie przerywa podróż (`interruptCamera`) i przejmuje kamerę.
- **Okładka pierwszej klatki** — po 600 ms bez klatki mówi „Wczytywanie
  modelu" z paskiem postępu; zanika przez `motion.exit()`.

Czego nie ma: pętli dekoracyjnych, sprężyn z odbiciem na chromie
funkcjonalnym, przejścia współdzielonego przez granicę `AndroidView`
(model stoi, chrom się porusza).

### Ujęcia: grupy zamiast ściany opcji

`ModelViewPreset` dostał `group: PresetGroup` (Bryła / Wnętrze / Elewacje /
Detale) — fakt o liście, nie o kamerze. Panel Ujęć ma cztery krótkie grupy
z nagłówkami i liniami, przewija się w granicy 60 % wolnej wysokości,
a ostatnie wiersze pod fałdą są przyciemniane (`fadeBelowFold`), żeby lista
kończąca się równo z krawędzią nigdy nie wyglądała na kompletną. `preset`
jest teraz `null`-owalny: zmiana warstwy z panelu Warstw czyści znacznik
ujęcia, bo ujęcie to kamera **i** widoczność, a znacznik na nazwie, której
obraz już nie odpowiada, kłamałby. Preset „Bez dachu" nazywa się „Odkryte
poddasze", żeby jedno słowo nie znaczyło dwóch rzeczy w dwóch panelach.

### Audyty i co z nich zostało

**AUDIT-1 (Impeccable 4.1.1, `audit android` + krytyka heurystyczna,
niezależny agent).** Przed: 13/20 (Acceptable), heurystyki 18/32,
5 × P1: dotyk przechodzący przez szkło do kanwy; cele 40 dp (wiersze opcji)
i 44 dp (przycisk menu); przygaszony tekst na szkle ~3,5:1; pięć pustych
znaczników na osi czytających się jak etapy; 15 ujęć bez grup, sześć pod
fałdą bez sygnału, „Bez dachu" w dwóch znaczeniach ze stałym znacznikiem.
Wszystkie pięć poprawione (wyżej) oraz P2: wstecz zwalnia wybór, podpowiedzi
na długim dotknięciu glifów (`TooltipBox`), `Ellipsis` na każdym
jednowierszowym tekście, `selectableGroup` i grupa przechodzenia dla
TalkBack, zapis stanu inspektora poza kompozycją (`SideEffect`), jasne ikony
pasków systemowych wymuszone dla aplikacji tylko-ciemnej
(`SystemBarStyle.dark`), dolny scrim usunięty, trzy figury „— / — / 0"
zastąpione jednym zdaniem z akcją, kropka środkowa zastąpiona przecinkiem
w zasobie, komunikat wczytywania, interpolacja kamery. Świadomie nie
zrobiono: przeniesienia szyny na środek prawej krawędzi (u góry panel
zakrywa puste niebo, nie dom) ani przesunięcia kadru presetów pod chrom
(kadr jest funkcją modelu i pozostaje odtwarzalny).

**AUDIT-2 (Emil Kowalski — `emil-design-eng`, `review-animations`,
`improve-animations`; niezależny agent).** 14 pozycji w inwentarzu ruchu,
0 × P0, 5 × P1: dwie tafle nakładające się przy zmianie narzędzia, szewron
i szczegół osi na dwóch zegarach, inspektor rosnący z narożnika ekranu,
ease-in na wyjściach, 700 ms crossfade `NavHost` poza polityką — wszystkie
poprawione (patrz „Ruch" wyżej), plus P2: dystans kamery geometrycznie,
ease-in-out i czas 150–300 ms wg wielkości ruchu, halo od 0,7, szuflada
`snapTo` i statyczny pasek postępu pod „Usuń animacje".

**AUDIT-3 (weryfikacja obu soczewek, niezależny agent).** Wszystkie dziesięć
P1 potwierdzone; 0 × P0/P1; Impeccable 14/20, heurystyki 26/32. Z sześciu P2
cztery poprawione w tej samej pętli (pivot panelu liczony w `graphicsLayer`
z rozmiaru bieżącej klatki zamiast ze stanu opóźnionego o klatkę, granice
inspektora pamiętane przez cykl osi, „Otwory elewacji", znacznik ujęcia
czyszczony po ręcznym ruchu kamery); dwa zostają jako dług (gesty szuflady
pod „Usuń animacje", 4 dp między przyciskami szyny). Pełny zapis:
`PROJECT_STATUS.md`, wpis STAGE-013H.

### Wzorce z Mobbin (zasady, nie kopie)

Cztery zapytania: viewer 3D z narzędziami na krawędziach, pionowa szyna
przezroczystych przycisków nad kanwą, dolny arkusz z torem postępu nad mapą,
pływający inspektor wybranego obiektu. Wzięto: pionową szynę ikon przy
prawej krawędzi nad kanwą (Obsidian), panel opcji otwierany przy pasku
narzędzi, z którego pochodzi (Freeform, Craft), dolny uchwyt utrzymujący
mapę widoczną z osią trasy w środku (AllTrails, Polarsteps), kartę
informacji w lewym dolnym rogu sceny 3D (Google Arts & Culture), obiekt
wyśrodkowany z okrągłymi przyciskami w narożnikach (Apple Store, Best Buy).

### Narzędzia projektowe zainstalowane w tym etapie

- `frontend-design@claude-plugins-official` (Anthropic, Apache-2.0) —
  zainstalowany w zakresie projektu przez `claude plugin install
  frontend-design@claude-plugins-official --scope project`; jedyny ślad
  w repozytorium to `.claude/settings.json` z `enabledPlugins`. Użyty jako
  soczewka planu (tokeny, układ, anty-domyślne).
- `impeccable` 4.1.1 (pbakaus/impeccable) — już obecny w `~/.claude/skills`,
  użyty przez `audit android` i krytykę heurystyczną; jego deterministyczny
  detektor dotyczy HTML/JSX i nie został użyty na Kotlinie.
- `emil-design-eng`, `review-animations`, `improve-animations`,
  `find-animation-opportunities` (emilkowalski) — już obecne
  w `~/.claude/skills`, użyte jako druga soczewka.
- Mobbin MCP — połączony, cztery zapytania.
Żadnej zależności runtime aplikacji nie dodano.

### Czego świadomie nie zrobiono

Prawdziwego rozmycia tła (patrz „Szkło"), przejścia współdzielonego przez
granicę renderera, sprężyn, kadru presetów zależnego od chromu, wspólnego
silnika między sekcjami (sekcje poza domem nie mają modelu), danych na osi
czasu i w księdze kosztów (puste stany mówią to jednym zdaniem), ikon
w szufladzie, wersji tabletowej i poziomej (brak klas rozmiaru okna —
dług), pochwytu przeciągania osi czasu śledzącego palec (przeciągnięcie
rozstrzyga się na końcu gestu).

## Kontrakt przyszłego analizatora projektów (dokumentacja, STAGE-013C)

Analizatora **nie ma** i ten etap go nie zaczyna. Ta sekcja zapisuje poprzeczkę,
którą ustawił model Marcówek, żeby późniejszy etap miał do czego celować, a nie
zaczynał od pytania „co to znaczy dobrze".

### Wejścia wymagane

- **Rzuty wszystkich kondygnacji** z co najmniej jednym łańcuchem wymiarowym na
  osi X i jednym na osi Z. Bez wymiaru drukowanego na rzucie nie ma kalibracji,
  a bez kalibracji każda liczba jest pikselem.
- **Przekrój** z rzędnymi: poziom terenu, poziom parteru, poziom kondygnacji
  wyższej, kalenica. Wysokości nie wolno wyprowadzać z rzutu.
- **Wszystkie elewacje.** Rzut mówi, gdzie stoi ściana; elewacja mówi, co na
  niej jest. Pasów, ram i uskoków nie widać na rzucie — cały ten etap jest tego
  dowodem.
- **Wizualizacje**, o ile są. Wolno z nich czytać *jak coś jest zestawione*,
  nigdy *ile ma centymetrów*.
- **Parametry tekstowe** ze strony: powierzchnie, kąt dachu, wysokość, ścianka
  kolankowa. To one weryfikują odrys — rachunek zgodny z podaną powierzchnią
  dachu jest tym, co ustaliło kierunek kalenicy w Marcówkach.

### Cechy rozpoznawcze, które analizator musi odzyskać

Model uznaje się za rozpoznawalny dopiero wtedy, gdy ma wszystkie z poniższych,
o ile źródło je pokazuje:

1. bryła i rzut zewnętrzny z podanymi kotwicami wymiarowymi;
2. kierunek kalenicy, kąt i zasięg wysunięć dachu;
3. podcienia, wnęki i wszystko, co jest cofnięte względem lica;
4. **pasy, ramy i opaski elewacji** wraz z poziomem, na którym biegną;
5. relacja bryły głównej do garażu lub innej przybudówki: wspólny poziom, wspólna
   linia, wspólna płaszczyzna;
6. otwory z wykazu, z parapetami i nadprożami;
7. schody: liczba biegów, kierunek i klatka;
8. strefy zewnętrzne osłonięte: taras, balkon, podcień.

### Kiedy analizator musi zapytać

Brak danych ma kończyć się pytaniem, nigdy wartością domyślną wyglądającą jak
zmierzona. Wyzwalacze pytania do użytkownika:

- brak łańcucha wymiarowego na jednej z osi rzutu, więc rysunku nie da się
  wykalibrować;
- rzędne nieczytelne albo sprzeczne między przekrojem a stroną;
- kształt dachu nieoczywisty: brak kalenicy na rzucie, wysunięcia niemierzalne,
  powierzchnia dachu niezgodna z rachunkiem o więcej niż kilka procent;
- niejasna relacja garażu: nieznana wysokość, nieznany poziom góry, nieznane
  połączenie z domem;
- widoczna na elewacji cecha rozpoznawcza, której nie da się umiejscowić na
  rzucie — pas bez poziomu, rama bez grubości;
- brak elewacji z którejś strony, więc jedna ściana pozostaje niepotwierdzona;
- wykaz stolarki niepełny albo niezgodny z przerwami w hatchu ścian;
- niejednoznaczny podział przestrzeni otwartej, której źródło nie przegradza.

Każda odpowiedź użytkownika na takie pytanie wchodzi do modelu jako
`DISPLAY_ASSUMPTION` z podaną przyczyną — dokładnie tak, jak wchodzą dziś
założenia odrysu ręcznego. Nierozstrzygnięta niepewność zostaje
`TRACE_UNCERTAIN` i ma być widoczna, a nie zasypana wartością.

### Lekcja STAGE-013D dla analizatora

Rozpoznawalność nie wynika z samej geometrii rzutu. Trzy korekty tego etapu —
głębokość i skos ramy, zasięg balkonu, krawędź dachu — pochodzą z elewacji
i wizualizacji, a rzut żadnej z nich nie pokazuje. Elewacje i wizualizacje są
więc pełnoprawnym źródłem **kompozycji elewacji** (co jest przed czym, gdzie
kończy się pas, jak gruby jest policzek), nigdy wymiarów. Jeżeli cechy
rozpoznawczej — ramy, opaski, zasięgu balkonu, pasa okapowego — nie da się
ustalić z dostępnych widoków, analizator ma **zapytać użytkownika**, a nie
podstawić generyczny domyślny kształt: dom bez tej cechy jest innym domem.
Prezentacja (co jest szkłem, co jest przygaszone) pozostaje metadaną obok
modelu, po identyfikatorze, i nigdy nie wchodzi do domeny.

## Analizator projektów — prototyp (STAGE-023A, utwardzony w STAGE-023B i 023C)

Pierwsza implementacja kontraktu spisanego wyżej: z adresu publicznej strony
projektu (dziś: ARCHON) do kandydata bryły, przedmiaru i listy pytań — bez
zdalnej AI, bez OCR, deterministycznie. To prototyp badawczy w osobnym module,
nie funkcja produktu.

### Moduł i granice

- `analyzer/` — czysto-JVM moduł Gradle (`kotlin-jvm`, Java 17), pakiet
  `com.buildplan.app.analyzer`. Jedyna zależność produkcyjna: jsoup 1.23.2
  (MIT) do parsowania HTML. Nie zna Androida, Compose, `domain/`,
  `geometry/` ani renderera — pilnuje tego `AnalyzerPurityTest`, który
  odrzuca także każdy token konkretnego projektu (nazwy, klucze, wartości
  oczekiwane) w `analyzer/src/main`.
- **Od STAGE-024 `:app` zależy od `:analyzer` produkcyjnie**
  (`implementation`), bo import projektu jest funkcją produktu. Do release
  wchodzą: moduł analizatora, jsoup, `kotlinx-coroutines-core` i uprawnienie
  `INTERNET` (w `app/src/main/AndroidManifest.xml`). **Nie wchodzą**: Analyzer
  Lab, jego wpis launchera i zasoby, fikstury ewaluacyjne, wartości
  benchmarkowe ani spike renderera. Pilnują tego `ReleaseBoundaryTest`
  i `AnalyzerApiSurfaceTest` w `app/src/test/`, a potwierdza kontrola dexa na
  zbudowanym APK (§ „Granica release" niżej).
- Wynik analizy (`ProjectAnalysisCandidate`, `ProjectAnalysisSnapshot`) nigdy
  nie trafia do `domain/`. Podgląd 3D w Lab buduje ulotny `Building`
  i `BuildingGeometry` z identyfikatorami `cand-` przez nowy szew
  `render/filament/SceneModel` (interfejs, który `DebugModel` dotąd realizował
  implicite); `MarcowkiReferenceProject` i `MarcowkiVisualModelV1` nie są
  dotykane.

### Potok

```
ProjectInput ─► UrlSafety ─► SourceResolver ─► SiteAdapter (ARCHON) ─► SourcePackage
   ─► AssetFetcher ─► PlanAnalyzer (rzut po rzucie) ─► RoofSolver ─► VerticalAnalyzer
   ─► FloorCandidateBuilder ─► QuantityTakeoffEngine ─► CrossSourceValidator ─► GapAnalyzer
   ─► ProjectAnalysisSnapshot (JSON, deterministyczny)
```

1. **Bezpieczeństwo adresu.** Tylko `https`, allowlista hostów
   (`www.archon.pl`, `archon.pl`, `assets.archon.pl`), normalizacja IDN, bez
   poświadczeń i portów, odrzucenie adresów rozwiązujących się do zakresów
   prywatnych, ręczne śledzenie przekierowań z ponowną kontrolą każdego skoku,
   limit 8 MB na odpowiedź. Żadnego crawlowania: strona projektu, jej rysunki
   i podstrona kosztów.
2. **Rozpoznanie strony.** Klucz ARCHON to `m[0-9a-f]{13}`. Adres ze
   zdezaktualizowanym sufiksem (Projekt B) jest rozwiązywany po rdzeniu sluga
   i przyjmowany tylko wtedy, gdy strona docelowa potwierdza klucz linkiem
   kanonicznym; każdy krok trafia do `SourceResolution.steps`.
3. **Adapter witryny** czyta fakty tekstowe do `Measured` z `SOURCE_EXACT`
   i proweniencją (adres, lokator CSS, metoda): powierzchnie, wysokość,
   kąt i rodzaj dachu, ścianka kolankowa, tabele pomieszczeń po kondygnacji,
   role rysunków (rzuty, przekrój, elewacje, wizualizacje), benchmarki
   z podstrony kosztów. Polskie słownictwo pomieszczeń żyje wyłącznie
   w `site/archon/` i `validate/`.
4. **Rzut** (`plan/`): klasyfikacja pikseli (tusz = ciemny i nienasycony,
   więc ściany pod kolorowym znakiem wodnym zostają, a niebieski pas dachu
   odpada), otwarcie morfologiczne do struktury, osiowe kawałki ścian
   z przerwami na ich końcach, zalanie „zewnętrza" od krawędzi obrazu i od
   pasa dachu (przez jego własną kreskę obrysu), obrys jako największy
   składnik nie-zewnętrza, kawałki mające zewnętrze po obu stronach
   odrzucone (krawędź dachu, obrzeże tarasu), przesegmentowanie po liniach
   ścian, regiony przez 4-spójność i śledzenie konturu. Cały przebieg idzie
   dwa razy: z prowizoryczną skalą z rozpiętości struktury (tylko do
   wymiarowania jąder), potem ze skalą skalibrowaną. Progi są w metrach,
   nigdy w pikselach dobranych pod jeden rysunek.
5. **Kalibracja** (`PlanCalibrator`): kotwica to opublikowana powierzchnia
   zabudowy (`ppm = √(px/m²)`); suma powierzchni pomieszczeń jest wyłącznie
   sprawdzeniem (`residual`), rzuty wyższe dziedziczą skalę parteru
   (`SHARED_PLAN_SCALE`). Residuum ≤ 3 % → `SOURCE_DERIVED`, > 8 % →
   `CONFLICTING`. **Po dopasowaniu pomieszczeń residuum jest przeliczane
   ponownie** (`PlanCalibrator.reconcile`): anchor `MATCHED_ROOM_AREAS`
   porównuje piksele *dopasowanych* regionów z powierzchniami *tych*
   pomieszczeń i zastępuje surowy `PUBLISHED_ROOM_AREAS`, który ważył
   wszystkie zamknięte regiony (także taras, szacht i drzazgi) przeciw
   tabeli pomieszczeń. Skala nigdy się przez to nie przesuwa — powierzchnie
   pomieszczeń sprawdzają kalibrację, nie ustawiają jej.
6. **Dopasowanie pomieszczeń** (`RoomMatcher`): powierzchnia jest sygnałem
   wiodącym, ale nie jedynym, a matcher mówi wprost, czego nią nie
   rozstrzygnie: jednoznaczne pary najpierw, potem grupy regionów spójne
   przez uszczelnione przerwy, na końcu unie otwartych planów dzielone
   proporcjonalnie (`DISPLAY_ASSUMPTION` + pytanie). Cechy strukturalne —
   brama ≥ 2,2 m, bieg schodów, `CIRCULATION_HUB` (region, na który otwiera
   się najwięcej innych) — są preferencją, nie wetem, i żadna nie przebije
   powierzchni, która nie pasuje. Dwie równe powierzchnie to jawna
   niejednoznaczność; rozstrzyga ją dopiero druga cecha, a każde dopasowanie
   niesie `alternatives` (co jeszcze pasowało) i `signals` (co zdecydowało).
7. **Dach** (`roof/`): prostoliniowy szkielet z prędkościami krawędzi
   (1 = okap, 0 = szczyt) — zdarzenia zapadania i podziału, łuki → połacie —
   wspólny dla dachu dwuspadowego i kopertowego; płytkie wnęki obrysu
   (≤ 1,5 m) wypełniane, orientacja szczytów wybierana po opublikowanej
   powierzchni dachu, remis → dłuższa oś jako `DISPLAY_ASSUMPTION`
   z pytaniem. Bryły poza obrysem dachu (garaż) dostają dach płaski jako
   „masę wtórną". `RoofHeightField` daje wysokość połaci w punkcie.
8. **Pion** (`VerticalAnalyzer`): kalenica = wysokość budynku + teren
   (założone −0,30), okap = kalenica − wzniesienie szkieletu·tg, strop
   poddasza = okap + okap·tg − kolankowa − t·tg, parter w świetle = strop
   − 0,30 płyty. Łańcuch, który daje parter poniżej 2,30 m albo powyżej
   4,20 m, jest odrzucany jako niewiarygodny (szkielet o jednym spadku
   szczytuje na złączu skrzydeł) i zastępowany jawnym założeniem
   z pytaniem, a nie liczbą wyglądającą na zmierzoną.
9. **Przedmiar** (`QuantityTakeoffEngine`): na pomieszczenie podłoga, lica
   ścian po odcinkach obwodu (całka wysokości wzdłuż odcinka), otwory
   odejmowane z założonymi wysokościami (drzwi 2,05, okno 1,50, brama 2,20
   → `DISPLAY_ASSUMPTION`), sufit płaski i skosy na siatce 5 cm, kubatura,
   powierzchnia użytkowa wg reguły wysokości (100 % > 2,2 m, 50 % 1,4–2,2 m).
   Osobno ściany konstrukcyjne raz na ścianę wg klasy (`EXTERIOR`,
   `INTERNAL_LOAD_BEARING` ≥ 0,20 m, `PARTITION`; `LIGHT_EXTERIOR` — cienka
   linia na obrysie: balustrada, obrzeże — i `PIER` — słupek, komin, szacht
   krótszy niż 2× grubość — nie sumują się jako ściany), połacie, kalenice,
   naroża, okapy, stolarka, elewacja brutto/netto.
10. **Porównanie ze źródłem** (`CrossSourceValidator`): ≤ 5 % `MATCH_STRONG`,
    ≤ 15 % `MATCH_ACCEPTABLE`, dalej `MISMATCH`; powierzchnia zabudowy jest
    `NOT_COMPARABLE` (to kotwica), wielkości oparte na założeniach
    `INSUFFICIENT_SOURCE`.
11. **Braki i pytania** (`GapAnalyzer`): wymagania z wagami, stan
    (`SATISFIED`/`PARTIAL`/`ASSUMED`/`MISSING`), wskaźnik kompletności
    i lista `ClarificationQuestion` z tym, co analizator tymczasem założył.
12. **Migawka**: własny zapis i odczyt JSON (`snapshot/Json.kt`),
    `schemaVersion 1`, `analyzerVersion 0.1.0-stage023a`, `Locale.ROOT`
    w każdym formatowaniu, obieg zapis→odczyt→zapis identyczny co do bajtu.

### Niezmiennik obrysu: pierścień prosty albo nic (STAGE-023B)

Obrys pomieszczenia jest albo **poprawnym pierścieniem prostym**, albo nie ma
go wcale (`RoomGeometryState.UNRESOLVED_REGION`, `polygon == null`). Trzeciej
możliwości nie ma, bo pierścień, który sam siebie przecina, ma i tak
powierzchnię z shoelace'a i i tak odpowiada na `contains` — a obie odpowiedzi
są złe w sposób, którego nic dalej nie zauważa.

- `candidate/RingValidity.kt` sprawdza: skończoność, ≥ 3 wierzchołki,
  brak krawędzi zerowej długości, brak kolców, **brak przecięcia
  nieprzyległych krawędzi** (samo dotknięcie już dyskwalifikuje), niezerowe
  pole. Naprawa tylko **usuwa** (kolec, zdublowany wierzchołek, mniejsze pętle
  zaciśniętego pierścienia) i raportuje `areaRetained`; nigdy nie łączy
  regionów.
- `plan/RoomGeometry.kt` składa to w trzy kroki: **mostkowanie** pasków, które
  segmentacja uszczelniła w miejscu linii ściany, jakiej źródło nie narysowało
  (tylko gdy po obu stronach jest ten sam pokój); **śledzenie i dowód** —
  kolejne warianty wygładzenia uskoków, pierwszy prosty pierścień wygrywa,
  więc wygładzanie nie może wprowadzić przecięcia; **uzgodnienie** — pole
  pierścienia musi zgadzać się z polem pikseli, z których był śledzony
  (pasmo 90–115 %).
- Powierzchnia podłogi (`plannedArea`) jest liczona z pikseli **regionów przed
  mostkowaniem** — do lica ścian, jak mierzy je strona — a pierścień obejmuje
  dodatkowo progi drzwiowe łączące części pokoju. To dwie różne liczby, każda
  do swojego celu.
- Przedmiar pilnuje granicy: pokój bez pierścienia dostaje `MISSING` na
  suficie, kubaturze, licach ścian i powierzchni użytkowej — nigdy małej
  liczby wyglądającej na zmierzoną.

Błąd, który to zamyka: pokój złożony z dwóch regionów po obu stronach
drzwi dostawał pierścień **pierwszego** z nich, więc podłoga (z pikseli)
i sufit (z pierścienia) różniły się trzykrotnie.

### Szew odczytu tekstu z rysunku (STAGE-023B)

`text/` to kontrakt, nie silnik OCR: `DrawingTextExtractor` →
`TextObservation(text, bounds, confidence, sourceAsset, method, legibility)`.
Rdzeń nie zna czcionki ani biblioteki; implementacja może mieszkać w adapterze.

- `GlyphRunLocator` znajduje **gdzie** rysunek drukuje tekst (składowe
  wielkości glifu na wspólnej linii bazowej) i **mierzy wysokość glifu**.
- `DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX = 10` jest bramką
  wysokości: poniżej niej rozpoznawanie nie jest w ogóle podejmowane. Obie
  strony benchmarku wymiarują otwory i rzędne przy **4–6 px na glif**, więc
  wszystkie przebiegi wracają `TOO_SMALL_TO_READ`, a wysokości otworów
  zostają `MISSING` z pytaniem. To wynik o *źródle*, mocniejszy niż milczenie:
  liczby tam są, a opublikowany raster ich nie unosi.
- `DimensionLabelParser` jest bramką jakości: `SOURCE_EXACT` dopiero przy
  `legibility == READ`, `confidence ≥ 0,90`, pełnym dopasowaniu wzorca
  i fizycznie możliwej wartości. `OpeningLabelMatcher` wiąże etykiety
  z otworami i odrzuca etykietę, której szerokość kłóci się z odrysowaną.

### Rozpoznawanie cyfr: szablony, a nie model (STAGE-023C)

`GlyphRecogniser` to interfejs; `TemplateDigitRecogniser` jest jedyną
implementacją i jest w całości deterministyczny — bez sieci, bez wag, bez
biblioteki. Glif normalizuje się do siatki 8 × 12 po **prostowaniu pochylenia**,
a decyduje suma dopasowania kresek i cech **oczek** (liczba, położenie
w pionie, udział powierzchni). Dwie bramki, obie konieczne: `MIN_SCORE = 0,80`
i `MIN_MARGIN = 0,045` — przewaga nad drugim kandydatem. Bez marginesu
rozstrzygnięcie 9 vs 4 byłoby rzutem monetą podanym jako wynik.

Trzy rzeczy wymusił raster, każda zmierzona, nie założona:

- **Pochylenie liczy się na przebiegu, nie na glifie.** Prostowanie mierzone
  osobno dla każdej cyfry zamienia przekątną siódemki w pionową kreskę i „7"
  czyta się jako „1". Pochylenie jest cechą kroju, więc `estimateShear` bierze
  cały przebieg.
- **Szerokość mierzy się po wyprostowaniu.** Pochylenie dokłada kolumny, które
  należą do kroju, a nie do cyfry, i wąska „1" wygląda wtedy jak „7".
- **Domykanie tylko w pionie** (`close(0, 1)`). Kreski mają jeden piksel i gubią
  wiersze, więc cyfra przychodzi w kawałkach; domykanie symetryczne skleiłoby
  sąsiednie cyfry w jedną plamę.

### Łańcuch wymiarowy jako własny dowód (STAGE-023C)

Rozpoznana cyfra **nigdy nie jest faktem sama z siebie**. `DimensionChain`
rozstrzyga, czy odczyt może stać się `SOURCE_EXACT`, i robi to geometrią, nie
pewnością rozpoznawania — bo przy jedenastu pikselach żadna pewność nie
wyklucza pomylenia 5 z 6:

- `SELF_CONSISTENT` — **trzy albo więcej** etykiet, których rozstaw zgadza się
  z drukowanymi wartościami. Trzy to próg celowy: para zostawia jedną przerwę,
  a jedna przerwa zawsze da się wyjaśnić jedną skalą, więc para jest
  samozgodna z definicji i werdykt nic by nie znaczył.
- `AGREES_WITH_PLAN_SCALE` — skala z rozstawu etykiet zgadza się z kalibracją
  rzutu wyprowadzoną zupełnie inną drogą (powierzchnia zabudowy przez
  odrysowany obrys).
- `AGREES_WITH_TRACED_SPAN` — suma łańcucha równa się rozpiętości odrysowanej
  z rzutu. **Dodatkowo** etykieta musi być wydrukowana *poza* obrysem, tak jak
  drukuje się wymiar całkowity: to jedyne sprawdzenie, jakie dostaje samotna
  liczba, a arkusz jest pełen liczb, które wymiarami nie są (powierzchnie
  pomieszczeń, numer rysunku). Zgodność co do kilku procent bez właściwego
  miejsca to zbieg okoliczności, nie wymiar.
- `UNCORROBORATED` / `INCONSISTENT` — odczytane, **niezaufane**; trafiają do
  dziennika i nigdzie indziej.

Osobna bramka pilnuje **separatora dziesiętnego**: przecinek ma przy tym
stopniu pisma dwa–trzy piksele, więc nigdy nie jest glifem i nigdy nie wchodzi
do przebiegu. Gdyby przerwę uznać za pustą, „12,05" przeczytałoby się jako
„1205" — błąd stukrotny, przychodzący jako całkowicie zwyczajny wymiar rzutu,
którego żadne dalsze sprawdzenie by nie zauważyło. Dlatego pas przy linii
bazowej między glifami jest **oglądany**, a atrament tam oznacza odmowę
odczytu całego przebiegu.

### Rejestracja kondygnacji (STAGE-023C)

`FloorRegistration` szuka przesunięcia, przy którym obrys ścian piętra najlepiej
pokrywa się z parterem — zgrubnie co 4 px, potem dokładnie. Zgrubny przebieg
porównuje z **rozmytym** celem (`dilate` o połowę kroku), bo obrys ma jeden
piksel i krok co cztery przechodzi nad maksimum, trafiając kilka metrów obok.
Wynik (`FloorAlignment` z `confidence`) jest tym, co pozwala uznać strefę
schodów nad strefą schodów za ten sam bieg — `CROSS_FLOOR_ALIGNMENT`.

### Schody: dwa niezależne świadectwa (STAGE-023B)

`StairCandidate` niesie `evidence: Set<StairEvidence>` —
`PUBLISHED_STAIR_ROOM` (tabela pomieszczeń nazywa pokój schodami i region go
dopasował), `TREAD_LINES` (bieg równo rozstawionych linii stopni),
`CROSS_FLOOR_ALIGNMENT` (strefa nad strefą) — oraz `fromFloorId`/`toFloorId`,
`flights`, `roomId` i `unresolved`. Rozstaw stopnia to fizyczny przedział
0,16–0,42 m, a o fałszywce decyduje **regularność** rozstawu (najszersza
przerwa ≤ 1,4 × najwęższa): to ona odróżnia bieg od kreskowania mebla.
Liczba stopni i kierunek wejścia zostają nieodczytane — są tekstem i strzałką.

### Dwie semantyki ścian i dwie semantyki powierzchni

Lico pomieszczenia (tynk, malowanie; osobno dla każdej strony ściany
dzielonej) i ściana konstrukcyjna (raz, po osi, na wysokość kondygnacji albo
do spodu połaci) to różne wielkości i różne encje: jeden `WallCandidate`,
dwie `MeasuredSurfaceCandidate`. Podobnie sufit płaski, skosy i powierzchnia
użytkowa wg reguły wysokości są trzema liczbami obok powierzchni podłogi.
Porównania ze stroną używają tylko wielkości tej samej semantyki.

### Ewaluacja poza repozytorium

Testy w `analyzer/src/test/.../evaluation/` uruchamiają się tylko, gdy
`BUILDPLAN_ANALYZER_EVIDENCE_DIR` wskazuje katalog poza worktree; pierwszy
przebieg pobiera źródła na żywo i buforuje je pod `cache/`, kolejne odtwarzają
bufor (`BUILDPLAN_ANALYZER_LIVE=1` wymusza sieć). Do katalogu trafiają maski
pośrednie, raporty i migawki obu projektów. Cudze rysunki nie wchodzą do
repozytorium — w kodzie są wyłącznie adresy, klucze i liczby oczekiwane,
i to tylko w testach.

### Semantyka elewacji: dwa odczyty obwiedni

Ta sama ściana ma dwie liczby i strona kosztów nie mówi, którą drukuje:

- **mur między otworami** (`facadeWallMaterial`) — odrysowane kawałki ścian
  zewnętrznych. Otwory są z niej nieobecne z definicji, bo otwór to przerwa
  *między* kawałkami, więc **nic nie może ich odjąć drugi raz**; brakuje w niej
  też obwiedni, której raster nie rozłożył na prostokąty. To dolny widełek.
- **obwiednia** (`exteriorEnvelopeGross`) — obwód obrysu kondygnacji × jej
  wysokość, mierzona **nad** otworami, plus szczyty; `…Net` to ona minus
  stolarka.

`CrossSourceValidator.bracketed` porównuje z widełkami: gdy liczba ze strony
wypada **między** odczytem brutto a netto, rozbieżność jest definicją, a nie
geometrią, i wynik to `NOT_COMPARABLE` z wypisanymi obiema wartościami. Poza
widełkami porównanie idzie do bliższego odczytu i pozostaje `MISMATCH`.
Pozycję „ściany zewnętrzne" ze strony kosztów porównujemy z **murem** (to
wielkość do wybudowania), a nie z obwiednią.

### Zakres elewacji: sześć odczytów jednej ściany (STAGE-023C)

Widełki brutto/netto okazały się za wąskie. Jedna liczba pod „elewacją" może
znaczyć sześć różnych pomiarów tej samej ściany, a rozpiętość między nimi to
połowa jej wartości. `FacadeScope` wylicza je **wszystkie** i każdy nazywa: nad
otworami / netto, z garażem / bez, z bryłą poboczną pod własnym dachem / bez.
Odejmowane są wyłącznie części, które analizator **zmierzył** — nieogrzewany
garaż i bryła poboczna — więc odjęcie jest zakresem do nazwania, a nie
poprawką domykającą różnicę. Cokół poniżej poziomu parteru zostaje
**niezmierzony**, dopóki teren jest założeniem, i tak jest raportowany.

Porównanie robi dwa **osobne** twierdzenia i tylko jedno z nich jest o geometrii:

- że obwiednia zgadza się z liczbą ze strony — to jest zmierzone i zachowuje
  swoją siłę (`MATCH_STRONG` ≤ 5 %);
- **który** zakres strona wyceniła — to jest atrybucja. Zakresy leżą blisko
  siebie, więc tolerancja dość szeroka, by unieść błąd odrysu, bywa dość
  szeroka, by objąć dwa sąsiednie zakresy naraz. Wtedy `FacadeScope.attribute`
  zwraca oba i atrybucja jest raportowana jako **nierozstrzygnięta** —
  nazwanie bliższego byłoby definicją, której źródło nigdy nie podało.

Nazwany zakres, który pasuje, wygrywa z samą przynależnością do widełek:
„to jest obwiednia netto bez garażu (1,5 %)" jest odpowiedzią, a „liczba mieści
się gdzieś w przedziale" nie jest.

### Granica produkcyjna: usługa analizatora (STAGE-024)

STAGE-023A/B/C dowiodły, że deterministyczne podejście wystarcza; STAGE-024
zamienia je w **usługę o stabilnym kontrakcie**, z której korzysta aplikacja,
nie zmiękczając żadnego z zabezpieczeń. Rdzeń badawczy się nie zmienił —
zmieniło się to, przez co się do niego mówi.

#### Publiczne API: `analyzer/.../service/`

```
AnalyzeProjectRequest(url, cachePolicy)
        │
        ▼
ProjectAnalyzerService.analyze(request): Flow<AnalysisEvent>
        │                                  ├─ Progress(AnalysisPhase, detail)
        │                                  └─ Completed(AnalysisOutcome)  ← dokładnie jeden
        ▼
AnalysisOutcome = Success | Partial | UnsupportedSource | UnsafeUrl
                | FetchFailed | SourceChanged | AssetFailure
                | AnalysisFailed | Cancelled
```

- **`ProjectAnalyzerService`** — jedyne wejście. `analyze` jest zimne: nic się
  nie dzieje bez kolektora, anulowanie kolekcji anuluje bieg, a praca nigdy nie
  idzie na wątku kolektora.
- **`AnalyzerPlatform`** — dwa szwy, których analizator nie ma sam: dekodowanie
  bajtów na piksele i klient sieci (plus wstrzykiwalne rozwiązywanie nazw, żeby
  regułę adresów prywatnych dało się sprawdzić bez DNS). Na urządzeniu
  `AndroidAnalyzerPlatform` (`BitmapFactory` + `HttpResourceFetcher`),
  w testach ImageIO i atrapa. Dzięki temu **cała** usługa — anulowanie, cache
  i bezpieczeństwo adresu — jest testowana na czystym JVM.
- **`AnalyzerServices.create(platform, cacheRoot, …)`** — aplikacja nie nazywa
  adaptera witryny; katalog cache musi być nazwany wersjami tego, co decyduje
  o odpowiedzi, a jedną z nich jest wersja adaptera, więc jedno i drugie
  powstaje razem.

**Czego aplikacja nie widzi.** `plan/`, `roof/`, `raster/`, `text/`,
`vertical/`, `site/archon/` i fikstury ewaluacyjne to wnętrze prototypu i będą
przepisywane. `AnalyzerApiSurfaceTest` sprawdza, że `app/src/main` importuje
wyłącznie `service/`, `cache/` i typy wartości, z których zrobiony jest raport
(`Measured`, `FactFidelity`, `Provenance`, model kandydata i przedmiaru).
Granica, której nikt nie musi używać, jest ozdobą.

#### Rodziny wyniku: co znaczy „częściowo"

Reguła jest jedna i mieszka w `DefaultProjectAnalyzerService.classify`:

> Bieg jest **niepełny, gdy czegoś, co miał odczytać, nie odczytał** — a nie
> dlatego, że odpowiedź jest niepewna.

Niejednoznaczność i brak wysokości otworów to normalny, uczciwy stan tych
źródeł. Gdyby czyniły bieg niepełnym, każdy bieg na prawdziwym domu byłby
niepełny i słowo przestałoby cokolwiek znaczyć.

| Sytuacja | Rodzina |
|---|---|
| host spoza allowlisty, strona nie jest stroną projektu | `UnsupportedSource` |
| nie-`https`, poświadczenia, port, adres prywatny | `UnsafeUrl` |
| strona nie dała się pobrać | `FetchFailed` |
| brak **wymaganego** sygnału adaptera (tabele, parametry, rzuty) | `SourceChanged` |
| rzuty nazwane przez stronę, żaden nie dał się zdekodować | `AssetFailure` |
| sygnał zdegradowany, część rzutów, brak dachu, `BLOCKING` | `Partial` |
| wszystko odczytane (z pytaniami i niejednoznacznościami) | `Success` |
| kolektor anulował | `Cancelled` (bez raportu) |

`SourceChanged` i `AssetFailure` **niosą raport**, bo strona, która straciła
tabele pomieszczeń, mogła nadal opublikować parametry, a to warto pokazać obok
ostrzeżenia. Czego być nie może, to prawie pustego kandydata zwróconego jako
`Success`: dom bez pomieszczeń wygląda jak wynik, a jest ciszą.

#### Zdrowie adaptera: `AdapterHealth`

Sześć sygnałów (`TITLE`, `SCALARS`, `ROOM_TABLES`, `PLAN_ASSETS`, `COST_PAGE`,
`SITE_TAGS`), każdy `PRESENT` / `DEGRADED` / `ABSENT` z dowodem w słowach.
Trzy są wymagane; brak któregokolwiek to `BROKEN`. To odpowiedź na tryb awarii,
który scraper ma, a człowiek nie: selektor, który nic nie trafia, zwraca nic,
a warstwy wyżej składają z tej ciszy schludny, pusty dom. `AdapterDriftTest`
przechodzi przez przemianowany selektor wartości, przemianowany wrapper tabeli,
znikające rzuty, brakującą podstronę kosztów, podstronę kosztów z błędem HTTP
i zepsuty `dataLayer`.

Adapter deklaruje `adapterId` i `adapterVersion`; obie trafiają do migawki
(`schemaVersion` podniesiony do **2**, `analyzerVersion` do
**`0.2.0-stage024`**) i do klucza cache.

#### Kontrakt kandydata: `ProjectAnalysisReport`

Trwałą częścią jest **`snapshot`** — ta sama wersjonowana migawka, którą
`SnapshotCodec` pisze i czyta, i jedyna rzecz, którą cache przechowuje.
Wszystko inne na raporcie jest z niej **wyprowadzone czystą funkcją**, i to
właśnie sprawia, że bieg z cache i bieg świeży są dla wywołującego nie do
odróżnienia — oraz że ta granica nie stała się drugą kopią modelu kandydata,
utrzymywaną obok pierwszej.

- `identity` — `schemaVersion`, `analyzerVersion`, `adapterId`,
  `adapterVersion`, witryna, klucz projektu, adres kanoniczny i adres **wpisany
  przez użytkownika** obok niego.
- `generation` — czas, czasy etapów, kroki rozwiązywania i to, czy bajty
  przyszły z cache. Trzymane **osobno** od danych o domu: znacznik czasu
  wewnątrz kandydata sprawiłby, że żadne dwa biegi nie dałyby się porównać.
  `deterministicJson()` usuwa czasy, dziennik i ścieżki plików — dwa biegi na
  tych samych bajtach dają identyczne bajty, i to jest forma, którą można
  wkleić do zgłoszenia błędu, bo nie wychodzi przez nią żadna ścieżka
  z urządzenia.
- `quantityVerification` — **płaska** lista wszystkiego, co dałoby się wycenić:
  po pokojach (podłoga, obwód, lica ścian brutto/otwory/netto, sufit płaski,
  skos, razem, kubatura, powierzchnia wg reguły wysokości), po kondygnacjach
  (mur zewnętrzny, nośny, działowy, obwiednia brutto/netto), po projekcie
  (dach, kalenice, okapy, stolarka, elewacja, rzędne) oraz **po otworach**
  (szerokość, wysokość, parapet). Płaska celowo: ekran kosztów pyta „co wolno
  wycenić", a odpowiadanie na to chodzeniem po drzewie kondygnacji, pokojów,
  ścian i połaci to sposób, w jaki nieweryfikowane założenie trafia do budżetu,
  bo ktoś przeoczył gałąź.
- `ambiguities`, `adapterHealth`, `questions`, `validations`, `gaps`, `issues`.

Każda `ValidationFinding` ma teraz stabilny, maszynowy `key`
(`project:roofArea`, `floor:f0:roomFloorAreaSum`, `room:f1-r3:floorArea`) obok
polskiego `subject`. Prozę ktoś kiedyś poprawi; konsument, który chce położyć
porównanie obok wielkości, którą ono ocenia, potrzebuje czegoś, co się nie
ruszy.

#### Stan weryfikacji ilości

`FactFidelity` odpowiada „skąd ta liczba"; `VerificationState` odpowiada „czy
wolno na niej oprzeć pieniądze". To bliskie, ale nie te same pytania, a ekran
budżetu zadaje drugie.

| `FactFidelity` | `VerificationState` |
|---|---|
| `SOURCE_EXACT` | `SOURCE_VERIFIED` |
| `SOURCE_TRACED`, `SOURCE_DERIVED` | `DERIVED_VERIFIED` |
| `DISPLAY_ASSUMPTION` | `ASSUMED` |
| `TRACE_UNCERTAIN`, `MISSING`, `CONFLICTING` | `UNRESOLVED` |
| `USER_CONFIRMED` | `USER_CONFIRMED` |

**Analizator nigdy nie emituje `USER_CONFIRMED`.** To stan, w który człowiek
wprowadza wielkość w STAGE-025; bieg, który mógłby go wybić sam, uczyniłby
weryfikację ozdobną. `ReportContractTest` sprawdza to na całym raporcie i na
całej migawce, a `safeForCosting` odmawia każdej wielkości opartej na
założeniu. Do żadnego kosztu ani budżetu nic z tego jeszcze nie wchodzi —
`app/src/main/.../analyzer/` nie może nawet nazwać typu z `domain/`
(`ReleaseBoundaryTest`).

#### Niejednoznaczność: nazwać, nie rozstrzygnąć

`CandidateAmbiguity` niesie **każdy** odczyt, na który źródło pozwala:
`ROOM_IDENTITY` (dwa wiersze tabeli pasujące do jednego regionu),
`ROOM_UNCLAIMED`, `ROOM_GEOMETRY` (brak pierścienia prostego),
`STAIR_INTERPRETATION` (bieg czy półki), `FACADE_SCOPE` (dwa zakresy
w tolerancji). Kandydat zachowuje **jedno** przypisanie — musi, inaczej nie ma
geometrii — i mówi obok, że jest ono wyborem, oraz jakie były pozostałe.

Dlatego `RoomCandidate.matchAlternatives` przestało być listą zdań i jest listą
`RoomMatchAlternative(sourceRowIndex, roomName, publishedAreaM2, relativeError,
why)`. Proza czyta się dobrze i nie da się jej podać jako wyboru, a to jedyny
użytek, jaki ma. W `CandidateAmbiguities` nie ma **żadnego progu**: każda
liczba tutaj byłaby drugą opinią o tym, co jest niepewne, a potok ma już
jedyną.

#### Braki, których nie wolno wypełnić

Wysokości otworów nie ma w żadnym publicznym rastrze ARCHON (etykiety przy
4–6 px na glif, poniżej bramki 10 px). W raporcie widać obok siebie
`opening:<id>:width` odrysowaną i `opening:<id>:height` jako `MISSING`
z powodem, `UNRESOLVED` i pytaniem. Wielkości, które muszą coś odjąć, mówią
wprost, że odliczenie stoi na założeniu.

#### Cache: `analyzer/.../cache/AnalysisCache`

Cztery własności, każda odpowiadająca na inny sposób, w jaki cache produkuje
pewną złą odpowiedź zamiast chybienia:

1. **Wersjonowany wszystkim, co zmienia odpowiedź.** Katalog najwyższego
   poziomu nazywa się `s<schema>-a<analyzer>-<adapter><wersja>`. Build, który
   zmienił którąkolwiek, nie widzi starego katalogu w ogóle i kasuje go po
   drodze. Odtworzenie wyniku starego parsera jako bieżącego to jedyny błąd
   cache, który daje pewne złe liczby zamiast błędu.
2. **Nic nie jest zapisywane tam, skąd będzie czytane, zanim będzie
   kompletne.** Każdy plik idzie przez `.part` i `rename`; każdy bieg pisze do
   prywatnego katalogu sesji, który trafia na miejsce dopiero po zakończeniu.
   Bieg anulowany albo zabity zostawia katalog sesji, który następne otwarcie
   zamiata.
3. **Kluczowany projektem, osiągany adresem.** Projekt leży pod kluczem
   witryny; adresy, które się do niego rozwiązały, są zapisane obok, bo link
   użytkownika i link kanoniczny rzadko są tym samym napisem.
4. **Ograniczony.** Domyślnie 96 MB / 8 projektów, kasowane **całymi
   projektami** wg najdawniejszego użycia — projekt bez połowy rysunków
   odtworzyłby się jako projekt, któremu rysunki nie doszły, a to gorsze niż
   chybienie.

`CachePolicy`: `PREFER_CACHE` (domyślna), `REFRESH` (pomija cache i odświeża),
`CACHE_ONLY` (nie otwiera gniazda; `network = null` czyni to wymuszalnym,
a nie tylko zamierzonym).

**Nie zapisuje się bieg, który nie odczytał tego, po co szedł.** `AssetFailure`
i `SourceChanged` nie trafiają do cache: rzeczą, którą człowiek robi po
„rysunki nie doszły", jest ponowna próba, a odpowiedzenie na nią natychmiast tą
samą porażką z dysku jest gorsze niż powrót do sieci.

Ścieżki oddawane przez `AnalysisStorage` w produkcji są **względne**
(`assets/…`), więc ścieżka z urządzenia nie podróżuje w raporcie; Lab zostaje
przy bezwzględnych, bo jego zadaniem jest zostawić dowód do `adb pull`.
Katalog to `cacheDir/project-analyzer` — nigdy pamięć zewnętrzna, żadnego
uprawnienia, i system może go odzyskać pod presją miejsca, co jest właściwym
kompromisem dla rzeczy odtwarzalnej z sieci.

#### Anulowanie i współbieżność

- Rdzeń dostał **`CancellationSignal`** — kooperatywny, sprawdzany na każdej
  granicy etapu i między rysunkami. Celowo nie jest typem korutynowym: rdzeń
  zostaje zwykłym obliczeniem na bajtach, a `service/` mostkuje na to
  strukturalną współbieżność. `AnalyzerPurityTest` pilnuje, że
  `kotlinx.coroutines` nie pojawia się poza `service/`.
- Bieg anulowany **nie publikuje nic** i kasuje swoją sesję. Połowicznie
  zanalizowany kandydat nie jest kandydatem częściowym, tylko niedokończonym,
  a różnica jest istotna, bo `Partial` to wynik produktowy, na którym
  użytkownik może działać.
- **Jeden mutex na projekt** (po znormalizowanym adresie, bo klucz projektu
  jest znany dopiero po pobraniu strony — czyli już wewnątrz części, która nie
  może wykonać się dwa razy). Dwa dotknięcia tego samego linku są szeregowane:
  drugie znajduje wpis pierwszego zamiast się z nim ścigać. Różne projekty
  biegną równolegle.
- W aplikacji bieg żyje w `viewModelScope`, nie w kompozycji: obrót ekranu nie
  restartuje pobierania, a wyjście z ekranu je anuluje.

#### Model postępu

`AnalysisPhase` ma dwanaście wartości i **każda z nich naprawdę się pojawia**.
Brief etapu wymieniał kilka drobniejszych („pobieranie strony", „wykrywanie
rysunków", „dekodowanie rzutów", „odczyt wymiarów") i nie ma ich jako osobnych
pozycji, bo potok ich osobno nie sygnalizuje: pobranie strony jest częścią
rozpoznawania projektu, wykrycie rysunków częścią odczytu strony, dekodowanie
dzieje się w pobieraniu, a odczyt wymiarów w odtwarzaniu rzutów. Faza, która
nigdy nie nadchodzi, jest gorsza od zgrubnej, która nadchodzi — UI siedziałby
na niej i czekał. Wewnątrz `DOWNLOADING_ASSETS` szczegół liczy pojedyncze
rysunki, bo tam naprawdę jest czekanie.

`fraction` jest zadeklarowany jako „ile faz zaczęto z ilu istnieje" i nic nie
udaje, że śledzi czas; odtwarzanie rzutów zjada większość zegara. Etykiety są
sprawą wywołującego — enum jest stabilny, a polskie napisy są w `strings.xml`.

#### Sieć: co wolno, sprawdzane bez sieci

`HttpResourceFetcher` dostał szew `HttpTransport` (domyślnie
`UrlConnectionTransport`), bo rewalidacja przekierowań, budżet skoków i limity
ciała to zachowanie **bezpieczeństwa**, a dopóki nie dało się ich oddzielić od
gniazda, jedynym sposobem sprawdzenia było wycelowanie aplikacji w prawdziwy
host i nadzieja. `NetworkPolicyTest` przechodzi przez: przekierowanie poza
allowlistę, przekierowanie na adres prywatny (rebind na drugim skoku), zejście
na `http`, dorzucenie poświadczeń i portu, pętlę przekierowań, ciało
deklarujące ponad limit, ciało **kłamiące** o długości (nieskończony strumień
ucinany na limicie), przekierowanie bez `Location` oraz to, że jeden adres na
wejściu daje dokładnie jedno otwarcie na wyjściu — nie ma tu crawla do
ograniczania.

Usługa sprawdza adres **zanim** cokolwiek otworzy, więc niewspierany host jest
odpowiedzią typu, a nie błędem sieci z mylącym komunikatem; test dowodzi, że
atrapa fetchera nie dostała ani jednego żądania.

#### Granica release

| W release **jest** | W release **nie ma** |
|---|---|
| `:analyzer` (`analyzer/service`, `analyzer/cache`) | Analyzer Lab (`analyzer/lab/AnalyzerLabActivity`) |
| jsoup (`org/jsoup/Jsoup`) | Filament (`com/google/android/filament`) |
| `kotlinx-coroutines-core` | `MarcowkiVisualModelV1`, `MarcowkiReferenceProject` |
| `lifecycle-viewmodel-compose` | `SyntheticDemoHouse` |
| uprawnienie `INTERNET` | klucze projektów, `EvaluationProjects`, `BUILDPLAN_ANALYZER_EVIDENCE_DIR` |

Inwentarz `.so` w release **nie zmienił się**: nadal wyłącznie
`libandroidx.graphics.path.so` w czterech ABI. `:analyzer`, jsoup, korutyny
i `lifecycle-viewmodel-compose` są czysto bajtkodowe, więc badania 16 KB nie
trzeba powtarzać; `zipalign -c -P 16 -v 4` na APK release przechodzi.
Uprawnienia release to dokładnie `INTERNET` (plus generowane przez AndroidX
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) i jeden wpis launchera.

#### Szew produktowy

`AppSection.Import` („Import projektu", grupa Projekt) → `ProjectImportScreen`
+ `ProjectImportViewModel`. Pole adresu, `Analizuj` / `Przerwij` / `Pobierz
ponownie`, lista faz z detalem, a po biegu **podsumowanie tylko do odczytu**:
liczby kondygnacji, pomieszczeń, otworów i wielkości, ile wymaga potwierdzenia,
pytania, niejednoznaczności z ich odczytami i czego zabrakło. Plus zdanie
wprost: to propozycja, nic nie zostało zapisane w modelu ani w kosztach.

Wybieranie między dwoma pomieszczeniami tej samej powierzchni, potwierdzanie
wysokości otworu i przenoszenie czegokolwiek do modelu to praca weryfikacyjna
i należy do STAGE-025. Jej połowiczna wersja tutaj byłaby tym, czego ludzie by
używali, i wpuściłaby nieweryfikowane liczby do budżetu.

### Znane ograniczenia prototypu (po STAGE-023C)

- **Wysokości otworów i rzędne przekroju pozostają nieodczytane.** Etykiety
  otworów drukują się przy **4–6 px na glif**, rzędne przekroju przy 7–8 px —
  poniżej bramki 10 px. Są **lokalizowane i liczone** (Projekt A: 16 z 31
  i 12 z 19 otworów ma etykietę w zasięgu 2,5 m), ale żadna nie jest czytana,
  więc wysokości zostają `MISSING` z pytaniem. Strona linkuje **jeden** raster
  na rysunek — bez `srcset`, bez większego wariantu — więc większego źródła nie
  da się pobrać. To ograniczenie źródła, nie rozpoznawania.
- **Łańcuchy wymiarowe obrysu są odczytywane, ale rzadko potwierdzone.**
  Drukowane przy 10–13 px, więc nad bramką: Projekt A czyta 5 przebiegów
  i potwierdza 1 (12,05 m przez odrysowaną rozpiętość), Projekt B czyta 3
  i potwierdza 1 łańcuch dwuelementowy (4,91 + 9,21 m przez skalę rzutu).
  Reszta zostaje `UNCORROBORATED` i nie wchodzi do modelu.
- **Przebiegi glifów bywają znajdowane w kresce, nie w tekście.** Kreskowanie,
  końcówki linii wymiarowych i pojedyncze znaki trafiają czasem do przebiegu
  i dostają odczyt (Projekt A: „44", „15"; Projekt B: „00"). Broni przed tym
  **korespondencja**, nie rozpoznawanie: żaden z nich nie ma czym się
  potwierdzić, więc żaden nie staje się wymiarem. Filtr, który odróżniłby je
  po samym kształcie, dostrojony na dziesięciu przypadkach z dwóch arkuszy
  byłby dopasowaniem do benchmarku — świadomie go nie ma.
- Szkielet o jednym spadku przeszacowuje wzniesienie dachu kopertowego ze
  skrzydłami; łańcuch pionowy ma przez to bramkę wiarygodności.
- **Poddasze Projektu B: prawdziwa niejednoznaczność, nie błąd.** Trzy pokoje
  mają powierzchnie 15,36 / 15,60 / 15,01 m² — 4 % rozrzutu na trzy pozycje.
  Matcher zgłasza to jako niejednoznaczność z alternatywami i to kaskaduje.
  Poprawka, która „naprawia" B (przypisanie globalnie zachłanne, kolejność
  według wskazówek), była mierzona na obu domach i **odrzucona**: B zyskiwał
  jedno pomieszczenie, A tracił Kuchnię i dwie sypialnie. Suma kondygnacji jest
  o 33 % za mała i tak jest raportowana.
- **Parter Projektu A: dwa pomieszczenia o identycznej powierzchni.**
  Niedomiar 16,5 % to dokładnie `2. Hol` i `7. Pokój`, obie po **9,18 m²**.
  Powierzchnia ich nie rozróżnia, wolne regiony nie starczają na obie, a unia
  otwartej przestrzeni wybrała lepiej dopasowaną kombinację. Bez odczytu nazw
  z rzutu to jest nierozstrzygalne i zostaje pytaniem.
- Schody: po dwie strefy na projekt. Bieg `f1-s2` w Projekcie A zostaje
  `TRACE_UNCERTAIN` i **nazywa konkurencyjny odczyt** — jedenaście równo
  rozstawionych linii co 0,20 m leży wewnątrz „Garderoby", gdzie są to
  najpewniej półki, nie stopnie. Liczba stopni i kierunek wejścia nieodczytane.
- Kamera podglądu 3D w trybie „Całość" kadruje kandydata zbyt blisko przy
  wysokim dachu; pozostałe tryby kadrują poprawnie. To wada harnessu Lab,
  nie kandydata.
- Lab jest surowym harnessem debugowym (listy tekstu), a na emulatorze
  z 2 GB RAM pierwsze uruchomienie po instalacji trwa 1–2 minuty
  (weryfikacja bajtkodu), co system zgłasza jako ANR do przeczekania.

### Co znalazł audyt kontradyktoryjny (STAGE-023C)

Iteracja audytowa była pisana po to, żeby **zepsuć** czytnik, i cztery rzeczy
się udały. Każda jest naprawiona i przykryta testem:

- **Wywrotka na najczystszym wejściu.** Suma dopasowania z premiami za oczka
  przekracza 1,0 przy niemal idealnym glifie, a `TextObservation` wymaga
  `confidence ∈ 0..1` — cała analiza kończyła się wyjątkiem. Ocena dopasowania
  jest w swojej skali; pewność jest wielkością ograniczoną i tam się ją nasyca.
- **Samotna liczba bez miejsca.** Zgodność z odrysowaną rozpiętością
  wystarczała, gdziekolwiek liczba stała na arkuszu (wyżej: wymiar całkowity
  drukuje się poza obrysem).
- **Połknięty przecinek dziesiętny** — patrz wyżej; przy okazji zniknął fałszywy
  przebieg „44" w Projekcie B, bo przez jego linię bazową biegła kreska.
- **Atrybucja zakresu elewacji podawana jako pewna**, gdy pasowały dwa zakresy.

Determinizm sprawdzany przez powtórzenie: dwa przebiegi obu projektów różnią się
**wyłącznie** wierszem czasów. Zgodność JVM ↔ urządzenie sprawdzana przez
porównanie migawek: po odjęciu czasów i metadanych pobrania **zero** różniących
się wierszy na 24 475 (A) i 30 893 (B).

## Weryfikacja kandydata i odczyt obrazów źródła (STAGE-025)

STAGE-024 dało produktowe wejście do analizatora, ale wynik był tylko do
odczytu: 331 wielkości Projektu A, z czego **246 bez potwierdzenia**, i żadnej
drogi, którą człowiek mógłby je potwierdzić. STAGE-025 dokłada tę drogę oraz
drugie źródło dowodu — elewacje i wizualizacje, dotąd pobierane i nieczytane.

### Weryfikuje się korzenie, nie wiersze

246 pól wyboru nie jest interfejsem, tylko przerzuceniem pracy analizatora na
człowieka. Warstwa `analyzer/verification/` zaczyna od pytania: **ile decyzji
naprawdę jest niezależnych?** Odpowiedź dla Projektu A to **48**, przy 246
wierszach do potwierdzenia — pięciokrotnie mniej, bo jedna rzędna stropu nad
parterem rozstrzyga 69 wielkości, a jeden odczyt dachu 78.

- `DependencyGraph` mapuje **klucz korzenia** (`room:<id>:identity`,
  `room:<id>:boundary`, `opening:<id>:height`, `level:terrain|upperFloor|
  slab|atticCeiling`, `roof:ridge`, `roof:mass:<id>`, `facade:scope`,
  `stair:<id>`) na klucze wielkości, które z niego wynikają. Graf powstaje
  z kandydata i z księgi `quantityVerification`, którą raport już niesie —
  nie z drugiej, ręcznie utrzymywanej listy zależności.
- `RootQuestions.of(report)` zwraca `RootQuestion` z `affectedQuantityKeys`,
  więc „ile wielkości rozstrzyga ta odpowiedź" jest liczbą z grafu, a nie
  obietnicą.
- Otwory tego samego typu i zbliżonej szerokości na jednej kondygnacji
  łączą się w jedno pytanie (`groupMemberIds`): „5 × drzwi 0,85 m" to jedna
  wysokość, nie pięć. **Grupować wolno tylko wtedy, gdy członkowie mają jeden
  korzeń** — inaczej masowa decyzja jest cichym nadpisaniem.

### Kolejność pytań jest wyliczana, nie zastana

`priorityScore` powstaje z wagi tego, co pytanie odblokowuje, nie z kolejności,
w jakiej pytania powstały: blokada geometrii 100, tożsamość pomieszczenia 60,
widoczny skutek w 3D 8, konflikt wysoki 20 / średni 10 / niski 3, po 1 za
każdą zależną wielkość, −5 za rzecz czysto kosmetyczną. Wynik trafia do
czterech szczebli `PriorityTier` — `REQUIRED`, `HIGH_IMPACT`, `RECOMMENDED`,
`OPTIONAL`. `REQUIRED` to pytania, bez których bryła nie ma sensu; **tylko
one blokują** i tylko ich nie wolno odłożyć.

### Nakładka decyzji nad niezmienną migawką

`ProjectAnalysisReport` pozostaje dowodem tego, co odczytał analizator, i nie
jest mutowany nigdy. Decyzje leżą **obok**:

```
VerificationSession(
    originalCandidate,        // migawka, bez zmian
    decisions,                // co człowiek postanowił, w kolejności
    derivedVerifiedCandidate, // przeliczone deterministycznie
    unresolvedQuestions,
    changeSummary,
)
```

`VerificationDecision` ma siedem rodzajów: `CONFIRM_CANDIDATE_VALUE`,
`REPLACE_VALUE`, `CHOOSE_ALTERNATIVE`, `CONFIRM_ASSUMPTION`,
`REJECT_OBSERVATION`, `PROVIDE_MISSING_VALUE`, `DEFER_QUESTION`. Sesja jest
wartością niezmienną: `choose/provide/confirm/reject/defer` oddają nową sesję,
`undoLast` i `reset` zdejmują decyzje, a `replay` odtwarza wynik z samej listy
decyzji. Ten sam zbiór decyzji zawsze daje ten sam `VerifiedCandidate`.

`VerificationEngine.verify()` nie łata wielkości pojedynczo: składa decyzje
w kopię kandydata w ustalonej kolejności, po czym **przelicza od nowa** cały
przedmiar (`QuantityTakeoffEngine`) i porównania (`CrossSourceValidator`).
Potwierdzenie rzędnej terenu przenosi całą bryłę dachu, a kalenica i okap
wyprowadzają się ponownie z potwierdzonej wartości — nie z poprzedniego
założenia.

### Granica `USER_CONFIRMED`

Analizator nadal **nie umie** wyemitować `USER_CONFIRMED`; to nie jest zwykły
stan wyniku. Wprowadza go wyłącznie decyzja człowieka:

- wielkość dotknięta wprost decyzją → `USER_CONFIRMED` albo `USER_OVERRIDDEN`,
- wielkość przeliczona z takiej decyzji → `DERIVED_FROM_USER_CONFIRMED`,
- reszta zostaje tym, czym była (`SOURCE_FACT`, `SOURCE_DERIVED`,
  `ASSUMPTION`, `UNRESOLVED`, `DEFERRED`).

`VerifiedQuantity` niesie wartość przed i po, stan, `rootQuestionIds`
i `decisionIds`, więc rodowód każdej liczby daje się prześledzić do decyzji
i do miejsca w źródle. Pilnuje tego `VerificationBoundaryTest`: literał
`USER_CONFIRMED` nie występuje **nigdzie** w źródłach aplikacji — jedyną drogą
do niego jest silnik.

### Kandydat nadal nie jest prawdą

Zweryfikowany kandydat jest zweryfikowanym **kandydatem**. Nic nie zapisuje się
do `domain/`, nie powstaje żaden koszt, nie ma kanonicalizacji ani
„przeniesienia do modelu". Podsumowanie mówi to wprost: „Model zweryfikowany
do dalszej pracy", nigdy „Model jest poprawny w 100 %".

### Dowód z obrazu — elewacje i wizualizacje

Elewacje i rendery były pobierane od STAGE-023A i nieczytane.
`analyzer/visual/` czyta je deterministycznie — morfologia rastra, klasy
pikseli, autokorelacja — **bez żadnej zdalnej AI i bez modelu wizyjnego**.

- `PictureClasses` klasyfikuje piksel (niebo, zieleń, biel, szarość, ciemność,
  drewno, szkło) na `ByteArray`, nie na tablicy obiektów.
- `ElevationReader` wyprowadza sylwetkę (z usuwaniem chmur po udziale granicy
  nieba), profil kalenicy, kominy, odczyt szczyt/koperta, obszar dachu
  *zwisający* z linii dachu, linię okapu, moduł pokrycia, okna połaciowe,
  prostokąty otworów z testem otoczenia ścianą, okładzinę, ramy, pasy
  i balustrady.
- `VisualEvidenceBuilder` składa `VisualAssetEvidence` z obserwacjami,
  przypisuje elewacje do stron świata (front = drzwi wejściowe wiatrołapu albo
  brama garażu; boczne zostają nierozstrzygnięte) i wystawia konflikty.

**Hierarchia dowodu jest twarda i wpisana w typy**: wymiar podany wprost
> geometria z rzutu i przekroju > elewacja > wizualizacja > model odrysowany
ręcznie > założenie prezentacyjne. Obraz **nigdy nie nadpisuje rzutu**;
rozbieżność staje się `VisualConflict` (`OPENING_COUNT_CONFLICT`,
`ROOF_SILHOUETTE_CONFLICT`, `MASSING_CONFLICT`, `GARAGE_RELATION_CONFLICT`,
`VISUAL_EVIDENCE_UNCORROBORATED`, …) z wagą, a konflikt staje się pytaniem.

**Wygląd zostaje kandydatem prezentacji.** `AppearanceCandidate` — kolor
elewacji, materiał pokrycia, okładzina — nie wchodzi do przedmiaru, nie tworzy
kosztu i nie zmienia geometrii. To osobna warstwa, celowo oddzielona od bryły.

**Precyzja przed zasięgiem.** Fałszywy konflikt kosztuje człowieka pytanie
o rzecz, której nie ma; brakujący konflikt kosztuje tylko pytanie, którego nie
zadano. Czytnik jest z tego powodu nastrojony nisko na zasięg: A daje jeden
konflikt niski, B dwa niskie, żadnego fałszywego otworu.

### Wyrocznia porównawcza (wyłącznie debug/test)

`app/src/testDebug/.../evaluation/` porównuje kandydata analizatora z ręcznie
odrysowanym `MarcowkiVisualModelV1`. To **narzędzie oceny, nie ścieżka
produktowa**: żaden kod z `src/main` go nie widzi, a `ReleaseBoundaryTest`
sprawdza to w dexie zbudowanego APK.

Rzecz, która czyni z tego wyrocznię, a nie samozadowolenie: **każda wartość
referencyjna niesie wiarygodność, jaką daje jej własna księga odrysu**, a
`CandidateReferenceComparator` **odmawia oceniania** wartości oznaczonych
`DISPLAY_ASSUMPTION` (`NOT_SCORED`). Porównywanie kandydata z cudzym
zgadywaniem produkuje liczbę, która wygląda jak wynik.

### Korekta analizatora znaleziona przez porównanie

Pierwsze porównanie dało `plan.extentZ` 14,47 m wobec wydrukowanych 12,60 m
(+14,9 %) — największą rozbieżność wspartą liczbą ze źródła. Zrzucone maski
rzutu pokazały przyczynę: **cienkie ostrogi parapetu tarasu** wychodzące poza
bryłę, brane do obrysu.

Poprawka jest **neutralna wobec projektu** — to ogólna reguła „obrys bryły to
nie każdy piksel, który bryły dotyka":

```kotlin
private fun withoutWhiskers(enclosed: BinaryMask, radiusPx: Int): BinaryMask {
    val opened = enclosed.open(radiusPx, radiusPx)
    val body = największa składowa otwarcia
    if (body.count() < before * MIN_BODY_SHARE) return enclosed   // 0,90
    return body
}
```

Otwarcie morfologiczne promieniem połowy maksymalnej grubości ściany, z progiem
bezpieczeństwa: jeżeli operacja zjadłaby więcej niż 10 % bryły, wynik jest
odrzucany i zostaje obrys pierwotny. Zastosowane **wyłącznie do obrysu**;
`footprintMask` do segmentacji, kalibracji, rejestracji i pasa dachu zostaje
maską pierwotną, a `enclosedOutlinePx` jest jawnym zapasowym obrysem dachu.

Rezultat po korekcie: `plan.extentZ` 12,48 m wobec 12,60 m (−0,9 %,
`MATCH`), centroidy pomieszczeń znacznie bliżej (Garaż 0,87 → 0,17 m, Pralnia
0,94 → 0,04 m), wyrocznia 17 `MATCH` / 5 `CLOSE` / 5 `DIFFERS` wobec
18 / 3 / 6. **Przy okazji wyszła prawda o poprzednim wyniku**: dotychczasowe
−1,5 % na dachu Projektu A było przypadkowe — ostrogi dostarczały mniej więcej
tyle, ile wynosi okap szczytowy.

Projekt B przeszedł ten sam bieg i **nie cofnął się**: pomieszczenia,
kalibracja, dach, schody, pytania i kompletność są co do bajtu takie jak
w STAGE-024; zmienia się wyłącznie obwiednia elewacji, w stronę podanej liczby
(A 305,53 → 266,47, B 300,03 → 250,80 przy podanych 225,90 / 171,60).

### Przestrzeń robocza weryfikacji

Ekran mówi językiem STAGE-013H, nie językiem formularza. Kanwa 3D zajmuje
ekran; przy krawędzi stoi wąska szyna pytań pogrupowana szczeblami, a nad nią
panel kontekstu jednego pytania. Podświetlany jest **podmiot pytania** w 3D
(`BuildingElementId("cand-<id>")`), a nawigacja następne/poprzednie nie
przestawia układu.

- Szyna i panel dzielą **jeden budżet wysokości** liczony z `BoxWithConstraints`
  (panel najwyżej 340 dp albo 46 % wolnej wysokości, reszta dla szyny).
  Na urządzeniu wyszło, że panel na pełną szerokość zasłania szynę i przechwytuje
  jej przewijanie — dwie trzecie pytań było nieosiągalnych.
- Pole liczbowe startuje **puste**. Założenie analizatora jest osobnym
  przyciskiem „Potwierdź X"; wpisana z góry wartość jest sugestią, którą ludzie
  zatwierdzają nie patrząc.
- Wariant release ma własną kanwę (`src/release`) z zarezerwowanym studiem;
  Filament zostaje w `debug`. Kanwa dostaje `chromeInsets` i układa **swój
  tekst** w obszarze, który chrom zostawia — model dalej zajmuje cały ekran.
- **Szkło jest materiałem jednowarstwowym.** Jedna tafla nad modelem czyta się
  jak tafla; dwie ułożone jedna na drugiej nigdy nie osiągają krycia i tekst
  spod nich wciąż przez nie widać. Chrom nad chromem bierze
  `GlassDefaults.OpaqueTint`, a warstwa modalna dodatkowo `ScrimModal`, który
  **przechwytuje gest** — bez tego pod kartą podsumowania nadal działało pole
  wartości i przycisk „Zastosuj".
- **Nagłówek i sposób odpowiedzi stoją poza przewijaniem.** Wewnątrz jednego
  kontenera przewijanego wysokość panelu decydowała, czy w pytaniu wymaganym
  widać drugi wariant odpowiedzi — a nie było widać, bez suwaka i bez cienia.
  Nad ucięciem prozy jest gradient, bo na ekranie dotykowym suwak nie mówi
  nic.
- **Odpowiedź człowieka i odczyt analizatora to dwa fakty i widać oba.**
  `QuestionOption.isCurrent` mówi, co niesie analizator, i nigdy się nie
  zmienia; `VerificationSession.chosenOptionId` mówi, co wybrał człowiek.
  Ekran, który podmieniłby jedno na drugie, zatarłby historię źródła, którą
  decyzja ma zachowywać.

### Język

Wszystko, co widzi człowiek, jest po polsku, łącznie ze zdaniami, które
analizator **składa** wokół własnej liczby i których żaden `strings.xml` nie
utrzyma (`candidate/PolishText`: „1 otwór", „4 otwory", „5 otworów", z uzgodnionym
przymiotnikiem). Diagnostyczna angielszczyzna analizatora — `matchNote`,
`roof.note`, `VisualConflict.impact` — zostaje w raporcie dla zgłoszenia błędu
i **nie wchodzi do pytania**; `QuestionLanguageTest` chodzi po każdym
renderowanym napisie każdego pytania i odrzuca angielskie słowa.

## Czego jeszcze nie ustalono

Persystencja, API, autoryzacja, testy instrumentalne, docelowa architektura
renderera 3D (kandydat wybrany w STAGE-012; STAGE-013 dołożyło na nim model
odrysowany, STAGE-013B poprawiło ten model, STAGE-013C dołożyło cechy
rozpoznawcze, STAGE-013D poprawiło wierność elewacji, STAGE-013G dołożyło
ramy okien i pełnoekranowy host, STAGE-013H zrobiło z hosta immersyjną
przestrzeń roboczą — nie produkcjonizację renderera), izolacja pomieszczenia w UI, docelowy kształt analizatora (STAGE-023A dało
prototyp badawczy w `analyzer/` i debugowy Lab, STAGE-024 produktowe wejście
przez usługę, STAGE-025 weryfikację kandydata przez człowieka i odczyt obrazów
źródła; **drugi adapter witryny, odczyt wysokości otworów z rysunku, trwałość
sesji weryfikacji i przejście zweryfikowanego kandydata do domeny są otwarte**),
wycinanie otworów w połaci dachu, grubość połaci,
picking przez szkło, klasy rozmiaru okna (tablet, poziom) dla przestrzeni
roboczej, docelowy
`applicationId`, generowanie identyfikatorów, pełne reguły sumowania alokacji
kosztów.
