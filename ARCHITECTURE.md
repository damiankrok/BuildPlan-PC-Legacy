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
rendererze stoi **model wizualny „Dom w marcówkach (GE)" V1** (STAGE-013):
pierwsza geometria odrysowana z aktualnych rzutów ARCHON, z jawną klasyfikacją
wiarygodności każdej liczby, również w źródłach `debug` i przeznaczona do
wizualnej weryfikacji przez OWNER-a. Brak backendu, persystencji, autoryzacji
i parsera rzutów.

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

## Czego jeszcze nie ustalono

Persystencja, API, autoryzacja, testy instrumentalne, docelowa architektura
renderera 3D (kandydat wybrany w STAGE-012; STAGE-013 dołożyło na nim model
odrysowany, STAGE-013B poprawiło ten model, STAGE-013C dołożyło cechy
rozpoznawcze — nie produkcjonizację hosta), izolacja pomieszczenia w UI,
analizator rzutów (kontrakt spisany wyżej, implementacji nie ma), wycinanie
otworów w połaci dachu, grubość połaci i pas podrynnowy, docelowy
`applicationId`, generowanie identyfikatorów, pełne reguły sumowania alokacji
kosztów.
