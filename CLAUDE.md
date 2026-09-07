# Zasady dla agentów kodujących

## Czym jest ten projekt

**Natywna aplikacja Android** (Kotlin + Jetpack Compose + Material 3).
Nie jest to aplikacja webowa, Flutter ani React Native. Jeżeli zadanie sugeruje
inaczej — zatrzymaj się i zapytaj.

## Zanim zaczniesz

- Najpierw przeanalizuj istniejący kod. Nie zakładaj, że czegoś nie ma —
  sprawdź.
- Przeczytaj `ARCHITECTURE.md` i `PROJECT_STATUS.md`. Opisują stan faktyczny,
  nie plany.

## Warstwa domenowa — reguły twarde

Domena mieszka w `app/src/main/java/com/buildplan/app/domain/`.

- **Niezmienników domeny nie wolno obchodzić.** Walidacja w konstruktorze jest
  kontraktem, a nie sugestią. Nie dodawaj bocznych konstruktorów, kopii ani
  fabryk, które ją omijają. Jeżeli niezmiennik jest zły, zmień niezmiennik
  i test, świadomie — nie przemycaj obejścia.
- **Żadnych `Float`/`Double` dla pieniędzy.** Kwoty to zawsze `Money`
  z całkowitymi jednostkami podrzędnymi. Arytmetyka na różnych walutach ma
  odmawiać, a przepełnienie rzucać wyjątek — nie tłum tego.
- **Nie wprowadzaj adnotacji persystencji do domeny.** Żadnego `@Entity`,
  `@PrimaryKey`, `@ColumnInfo`, `@Serializable`, DAO, DTO ani typów Room,
  Retrofit czy Ktor. Persystencja ma mapować na domenę, nie odwrotnie.
- **Żadnego stanu renderera w domenie.** Bez geometrii, transformacji, siatek,
  materiałów i flag widoczności w encjach budynku. Ukrywanie i izolacja to
  czyste zapytania (`BuildingElementSelection.kt`) przyjmujące
  `BuildingVisibility` jako argument — nigdy pole encji.
- **Jeden logiczny właściciel elementu.** `BuildingElement` żyje na
  `Building.elements` i deklaruje `BuildingElementScope`: `WholeBuilding`
  (dach, fundament) albo `OnFloor(floorId)`. Nie twórz fikcyjnych kondygnacji
  ani pomieszczeń, żeby coś „miało gdzie mieszkać”.
- **Pomieszczenia to relacja 0..N** (`roomIds`), nie własność. Ściana dzielona
  jest jednym elementem połączonym z dwoma pokojami — nie duplikuj elementu,
  żeby pojawił się w obu. Element `OnFloor` może wskazywać tylko pomieszczenia
  z tej samej kondygnacji.
- Domena nie zależy od Compose ani Android Context. Pilnuje tego
  `DomainPurityTest` — jeżeli zacznie failować, napraw przyczynę, nie test.
- Jednostki to `UnitOfMeasure`, nie stringi. Cele kosztów to `CostTarget`,
  nie luźne id.
- **`Cost.amount` to kwota brutto, faktycznie zapłacona** (pierwszy MVP). Nie
  dodawaj pól netto/VAT. `Cost.stageId` jest klasyfikacją: etykieta nigdy nie
  tworzy drugiej kwoty. Zasady podziału kosztów (DEC-COST-ALLOCATION-001) są
  otwarte do STAGE-015 — `CostAllocation` jest prowizoryczne i nie rozbudowuj
  go wcześniej.

## Warstwa geometrii — reguły twarde

Geometria mieszka w `app/src/main/java/com/buildplan/app/geometry/`.

- **Zależność idzie w jedną stronę.** Geometria czyta domenę; domena nigdy nie
  importuje geometrii. Pilnują tego `DomainPurityTest` i `GeometryPurityTest`.
- **Złączenie z domeną to wyłącznie `BuildingElementId`.** Nie kopiuj do
  prymitywów `roomIds`, `BuildingElementScope`, `BuildingElementKind` ani stanu
  widoczności. Jeden element może mieć wiele prymitywów (dach dwuspadowy = dwie
  połacie) i tak ma zostać — nie rozbijaj go na dwa elementy semantyczne.
- **Metry i współrzędne lokalne.** Y w górę, rzut poziomy w płaszczyźnie XZ,
  układ prawoskrętny. Żadnych pikseli, `dp`, współrzędnych ekranu, georeferencji
  ani terenu. `NaN` i nieskończoności są odrzucane w konstruktorze.
- **Żadnego stanu renderera w geometrii.** Kamera, projekcja, picking,
  materiały, siatki trójkątów i flagi widoczności nie należą do tego pakietu.
  `LocalBounds` to dane lokalne do późniejszego kadrowania, a nie kamera.
- **Reguł selekcji nie duplikuj.** Co jest widoczne, rozstrzyga
  `BuildingElementSelection.kt` w domenie. Most do geometrii to jedna funkcja
  `primitivesOf(elements)` — nie dopisuj drugiej ścieżki decyzyjnej.
- **Nigdy nie wyprowadzaj rzutu z powierzchni, opisów ani zrzutów ekranu.**
  Jeśli źródło nie podaje współrzędnych, to ich nie ma. Zmyślony rzut wyglądający
  na autorytatywny jest gorszy niż brak rzutu.
- Syntetyczny dom demonstracyjny (`geometry/demo/SyntheticDemoHouse`, źródła
  `debug`) ma wymyślone wymiary i nie jest rekonstrukcją żadnego realnego
  projektu. Nie podpinaj jego geometrii do `MarcowkiReferenceProject`.

## Jak pracować

- Trzymaj się zleconego zakresu. Nie rozszerzaj go samodzielnie.
- Małe, kontrolowane etapy. Bez szerokich refactorów bez wyraźnego polecenia.
- Nie duplikuj. Jeśli podobny komponent już istnieje w `ui/components/`,
  rozszerz go zamiast tworzyć wariant obok.
- Chroń istniejące funkcje. Nie usuwaj i nie przepisuj działającego kodu
  „przy okazji”.
- Nie twórz kodu „na przyszłość”. Warstw `data/`, `repository/`, `usecase/`
  nie dodawaj, dopóki nie ma logiki, która ich wymaga.
- Nie zmieniaj `applicationId` ani nazwy pakietu bez decyzji OWNER-a.

## Jakość

- Idiomatyczny Kotlin. Bez `!!` tam, gdzie da się to wyrazić typami.
- Nie maskuj błędów: żadnych pustych `catch`, `@Suppress` ani baseline lintu
  zakładanego po to, by ukryć problem. Napraw przyczynę.
- Nie wyłączaj lintu ani testów, żeby przepuścić zmianę.
- Nie dodawaj zależności bez realnej potrzeby.
- Etykiety w UI po polsku, w `strings.xml`. Nazwy w kodzie i trasy po angielsku.

## Dane referencyjne (debug)

`app/src/debug/java/com/buildplan/app/reference/` zawiera referencyjny projekt
odwzorowujący publiczną strukturę gotowego projektu domu. To rusztowanie
deweloperskie, nie produkt.

- **Nie traktuj go jak produkcyjnej prawdy ani danych użytkownika.** Nie kopiuj
  go do `src/main`, nie pokazuj w UI i nie buduj na nim funkcji produktowych.
- **Nie pobieraj i nie commituj obrazów, rzutów ani rysunków osób trzecich.**
  Dozwolone są wyłącznie publiczne fakty tekstowe potrzebne do modelu:
  tytuł, adres URL, nazwy pomieszczeń i podane powierzchnie.
- **Proweniencja mieszka poza domeną.** Adresu źródła ani faktów o źródle nie
  dodawaj do encji domenowych.
- **Nie dopisuj tam geometrii.** Współrzędne ścian, obrysy pomieszczeń, otwory
  i rzędne kondygnacji nie należą do zbioru referencyjnego — jego źródło ich nie
  podaje. Kształt do prac nad rendererem daje jawnie syntetyczny
  `geometry/demo/SyntheticDemoHouse`.
- Testy tego zbioru żyją w `src/testDebug/`, żeby wariant release nie zależał
  od treści osób trzecich.

## Przed zakończeniem zadania

Uruchom i doprowadź do zieleni:

```bash
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Zostaw czysty worktree.

## Czego na tym etapie NIE ma

Backendu, API, bazy danych, Room, autoryzacji, rzeczywistych danych, parsera
rzutów i renderera 3D (technologia niewybrana). Model domenowy i kontrakt
geometrii istnieją, ale nic ich jeszcze nie zapisuje i nic ich nie rysuje.
Jeżeli zadanie tego nie obejmuje wprost — nie dodawaj tego.
