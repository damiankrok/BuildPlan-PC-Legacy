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
i podstawa wyboru technologii, nie jako docelowy renderer produkcyjny. Brak
backendu, persystencji, autoryzacji i parsera rzutów.

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
  wysokość i dodatnia grubość. Oś, a nie obrys: obrys trzeba by przy każdej
  edycji uzgadniać z własną grubością. Otworów (drzwi, okna) tu nie ma.
- `SlabGeometry` — obrys w rzucie, rzędna **spodu** i dodatnia grubość. To,
  której płaszczyzny dotyczy rzędna, jest nazwane wprost, bo „strop na 2,80” jest
  niejednoznaczne dokładnie o grubość stropu.
- `RoofFacetGeometry` — jedna płaska połać dachu jako uporządkowane wierzchołki
  3D. Pole liczone metodą Newella, czyli na płaszczyźnie połaci, a nie w rzucie.

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

## Czego jeszcze nie ustalono

Persystencja, API, autoryzacja, testy instrumentalne, docelowa architektura
renderera 3D (kandydat wybrany w STAGE-012, produkcjonizacja w STAGE-013),
izolacja pomieszczenia w UI, parser rzutów, geometria otworów i schodów, docelowy
`applicationId`, generowanie identyfikatorów, pełne reguły sumowania alokacji
kosztów.
