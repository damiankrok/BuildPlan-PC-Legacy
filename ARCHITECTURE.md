# Architektura

Dokument opisuje **wyłącznie decyzje faktycznie podjęte**. Nie jest planem
docelowej architektury.

## Stan na dziś

Jedna natywna aplikacja Android: powłoka UI (STAGE-001) oraz kanoniczna warstwa
domenowa (STAGE-002). Brak backendu, persystencji, autoryzacji i renderera 3D.

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
bibliotek wykresów, silnika 3D i bibliotek płatności.

## Warstwa domenowa (STAGE-002)

Kanoniczna warstwa domenowa **istnieje** i mieszka w
`app/src/main/java/com/buildplan/app/domain/`:

- `domain/money/` — `CurrencyCode`, `Money`;
- `domain/units/` — `MeasurementDimension`, `UnitOfMeasure`, `Quantity`;
- `domain/model/` — identyfikatory, encje i kontrakt kosztów.

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

`Project → Building → Floor → Room` oraz `BuildingElement` należący do
kondygnacji. Projekt ma dokładnie jeden budynek (MVP). Własność jest wyrażona
jeden raz — przez zagnieżdżenie — więc `Room` nie nosi `floorId`, który mógłby
zaprzeczyć strukturze. Kondygnacje są trzymane w jednym kanonicznym porządku
(ściśle rosnące `order`), co odrzuca zarówno duplikaty pozycji, jak i listę
sprzeczną z zapisaną kolejnością.

### Brak stanu renderera w domenie

`BuildingElement` ma jawny `BuildingElementKind` (wall, slab, roof, door,
window, stairs, foundation, facade, other) i **nie zawiera geometrii,
transformacji, siatek, materiałów ani żadnego stanu renderera**. Przyszły model
3D będzie czytał te dane i trzymał własną reprezentację. Wpuszczenie stanu
renderera do domeny uczyniłoby ją bezużyteczną bez renderera i niemożliwą do
testowania na JVM.

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

## Czego jeszcze nie ustalono

Persystencja, API, autoryzacja, testy instrumentalne, technologia renderera 3D,
docelowy `applicationId`, generowanie identyfikatorów, pełne reguły sumowania
alokacji kosztów.
