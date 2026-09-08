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

## Warstwa renderera — reguły twarde

Spike renderera mieszka w `app/src/debug/java/com/buildplan/app/render/filament/`
i jest **wyłącznie debugowy** (`debugImplementation`). Wariant release ma go nie
kompilować, nie linkować i nie pakować.

- **Renderer nigdy nie jest właścicielem prawdy semantycznej.** Co jest widoczne,
  rozstrzyga `BuildingElementSelection.kt` w domenie, a kształty podaje jedyny
  most `primitivesOf(elements)`. Nie dopisuj w rendererze drugiej reguły o dachu,
  kondygnacji ani pomieszczeniu — nawet „tymczasowo”.
- **Złączenie z semantyką to nadal wyłącznie `BuildingElementId`.** Nie kopiuj do
  siatek `roomIds`, `BuildingElementScope`, `BuildingElementKind` ani stanu
  widoczności. Jeden element może mieć wiele siatek (dach dwuspadowy = dwie
  połacie) i musi wtedy odpowiadać **jednym** id — zarówno przy ukrywaniu, jak
  i przy pickingu.
- **Nie przenoś prawdy geometrycznej do renderera.** Pieczenie trójkątów jest
  adapterem nad `geometry/`; wysokopoziomowe fakty (oś ściany, grubość, obrys)
  zostają w `geometry/`. Adapter ma się dać testować na czystym JVM, więc nie
  importuj w nim typów Filamenta.
- **Przełączenie widoczności nie przebudowuje geometrii.** Siatki wgrywaj raz;
  zmiana widoku to dodanie i usunięcie encji ze sceny.
- **Ukrycie warstwy musi zachować ciągłość modelu.** Każdy stan widoczności jest
  podzbiorem jednego kanonicznego modelu; bryła się nie zmienia, zmienia się
  tylko to, ile z niej jest lite. Nie utrzymuj uproszczonych wariantów geometrii
  dla poszczególnych trybów — dwa warianty rozjeżdżają się przy pierwszej
  korekcie i OWNER widzi wtedy dwa różne domy. Warstwę zdjętą rysuj dalej jako
  przygaszoną siatkę. Zbiór usunięty licz jako dopełnienie odpowiedzi domeny,
  nigdy jako drugą regułę o dachu czy kondygnacji.
- **Jeden właściciel silnika na jeden `SurfaceView`.** Bez globalnego singletona
  renderera. Zasoby zwalniaj przy wyjściu z kompozycji; po odtworzeniu Activity
  ma istnieć dokładnie jeden żywy silnik.
- **Po każdej zmianie wersji renderera powtórz kontrole natywne** na zbudowanym
  APK, nie na dokumentacji: inwentarz `.so`, wyrównanie każdego segmentu `LOAD`
  do co najmniej 2^14, `zipalign -c -P 16 -v 4`, obecność `GNU_RELRO`.
- **Wsparcie prezentacji nie jest modelem.** Siatka odniesienia pod budynkiem
  (`PresentationGrid`) powstaje w rendererze z `LocalBounds` i nie ma
  `BuildingElementId` — nie da się jej wybrać ani ukryć, a `geometry/`
  i `domain/` o niej nie wiedzą. Nie rozwijaj tego w teren, działkę ani
  otoczenie.
- **`MeshStyle` wynika z typu prymitywu, nigdy z `BuildingElementKind`.**
  „To jest cienkie płaskie wypełnienie otworu" jest faktem o geometrii; „to jest
  element charakterystyczny elewacji" jest faktem o domenie. Cechę rozpoznawczą
  pokazuj bryłą i konturem, nie trzecim kolorem.
- **Metadane prezentacji żyją poza kanoniczną domeną.** Co jest szkłem, co jest
  przygaszone i jaką ma alfę, mówi `reference/visual/VisualSurfaceRole`
  i mapa po `BuildingElementId` obok modelu referencyjnego — nigdy pole encji,
  nigdy `BuildingElementKind`. Renderer składa typ prymitywu i tę mapę w jednej
  czystej funkcji. Domena i `geometry/` nie znają ról (`C013D-06`). Szkło jest
  jedynym wyjątkiem od jednego koloru i wyjątkiem jest wyłącznie
  przezroczystość: bez refrakcji, tekstur ani biblioteki materiałów.
- **Rozpoznawalności nie kupuj kosztem rozkładalności.** Podobieństwo do
  wizualizacji ma wynikać z osobnych, nazwanych elementów w jednym kanonicznym
  modelu — nigdy ze scalonej siatki, wariantu geometrii dla jednego widoku ani
  z cechy, której nie da się osobno ukryć, wybrać i nazwać. Dach, kondygnacje,
  garaż, ściany, otwory, schody, ramy, opaska, balustrady i pas okapowy mają
  zostać rozdzielne.
- **Krawędź dachu to osobna geometria, nie grubość połaci.** Połacie są jedyną
  geometrią uzgodnioną z liczbą ze źródła (powierzchnia dachu); pas okapowy
  rysuj obok nich jako własny element. Nie pogrubiaj połaci, żeby udawać
  okap.

## Model odrysowany ze źródła (debug)

`app/src/debug/java/com/buildplan/app/reference/visual/` zawiera
`MarcowkiVisualModelV1` — geometrię odrysowaną z aktualnych rzutów i przekroju
projektu ARCHON. To jedyne miejsce w repozytorium, gdzie kształt pochodzi
z zewnętrznego źródła, i obowiązują tu ostrzejsze reguły niż gdzie indziej.

- **Nie podstawiaj syntetycznego domu pod referencję OWNER-a.** Kiedy zadanie
  mówi „Marcówki", chodzi o dokładnie tę stronę i te rzuty.
  `SyntheticDemoHouse` jest fiksturą regresyjną renderera i niczym więcej — jego
  wymiary są wymyślone. Podmiana jednego na drugie zamienia weryfikację
  w wrażenie.
- **Nie mieszaj nieaktualnych wartości źródłowych.** Jeżeli stary PDF, wynik
  wyszukiwania albo cache mówią co innego niż aktualna strona — wygrywa
  aktualna strona. Wartość z dwóch różnych wydań źródła w jednym modelu to model
  domu, który nie istnieje.
- **Każde założenie wizualne ma jawną klasyfikację.** Nowa liczba w modelu
  dostaje `SOURCE_EXACT`, `SOURCE_TRACED` albo `DISPLAY_ASSUMPTION`
  w `MarcowkiSourceEvidence`, wraz z uzasadnieniem. Odmierzona pozycja ścianki
  i podany kąt dachu wyglądają identycznie, gdy oba są już `Double` — etykieta
  jest jedyną rzeczą, która je rozróżnia. Czego nie da się odrysować, oznacz
  `TRACE_UNCERTAIN`; niepewność ma być widoczna, nie ukryta.
- **Nie commituj cudzych rysunków.** Rzuty, przekroje i HTML pobieraj do
  katalogu poza worktree, odmierz i zostaw tam. W repozytorium wolno trzymać
  wyłącznie fakty tekstowe: adresy, moment odczytu i liczby. Pilnuje tego test
  `M013-13` — nie obchodź go.
- **Jedna prawda odrysu.** Wszystkie współrzędne mieszkają w `MarcowkiPlanGrid`;
  ściany, otwory, schody i strefy pomieszczeń są z niej generowane. Nie dopisuj
  drugiego, ręcznie utrzymywanego zestawu współrzędnych obok.
- **Otwór to dziura w ścianie plus osobny element, który ją wypełnia.**
  `WallOpening` należy do `WallGeometry`; okno albo drzwi to własny
  `BuildingElement` z własną szybą. Nie rysuj otworu jako ciemnego prostokąta na
  litej ścianie — z boku widać, że to naklejka. Nie kopiuj do `WallOpening`
  `BuildingElementKind`: różnicę okno/drzwi opisuje w geometrii sam parapet.
- **`MarcowkiReferenceProject` zostaje bez geometrii.** Model wizualny dekoruje
  go po identyfikatorach, ale kanoniczny zbiór nadal opisuje tylko to, co źródło
  podaje słowami: nazwy i powierzchnie.
- **Kryterium jest rozpoznawalność, nie tylko zgodność liczb.** Model ma być
  domem, który OWNER rozpozna, stawiając go obok strony ARCHON. Dwa domy o tej
  samej powierzchni zabudowy, tym samym kącie dachu i tej samej liczbie okien
  wyglądają zupełnie inaczej — różnicę robią cechy rozpoznawcze. Dlatego
  **cecha rozpoznawcza jest osobnym elementem budynku**, z własną nazwą
  i własnym identyfikatorem, a nie skutkiem ubocznym innej bryły. Dziś są to
  rama podcienia szczytowego (z głębokością i skosem), opaska
  międzykondygnacyjna na +3,06, pas okapowy i kominy ponad dachem; nie usuwaj
  ich i nie zamieniaj na kolor. Zasięg cechy bierz ze źródła, nie z wygody
  modelowania: balkon kończy się tam, gdzie kończy go rzut, a lukę, którą to
  odsłania, zamykaj geometrią, która naprawdę tam jest, nie przywracaniem
  błędnej płyty.
- **Cech rozpoznawczych szukaj na elewacjach.** Rzut mówi, gdzie stoi ściana;
  co jest na tej ścianie, mówi elewacja. Pasy, ramy i uskoki nie mają na rzucie
  śladu i nie da się ich stamtąd wyprowadzić.
- **Ciągłości modelu nie wolno cofnąć.** Każdy stan widoczności jest podzbiorem
  jednego kanonicznego modelu, a zdjęta warstwa zostaje przygaszoną siatką.
  Rzut, otwory, garaż i relacje elewacyjne mają być stabilne między stanami.

## Urządzenia i emulatory

- Projekt używa **wyłącznie serialu `emulator-5570`**. Każde polecenie do
  urządzenia podawaj jawnie: `adb -s emulator-5570 ...`.
- Nie instaluj, nie testuj, nie restartuj i nie konfiguruj innych seriali
  (m.in. `emulator-5554`, `emulator-5560`, `emulator-5580`). Nie wywołuj
  `adb kill-server`.
- Nie uruchamiaj ogólnych testów `connected*`, które mogłyby trafić we wszystkie
  podłączone urządzenia.

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
- **Nie dopisuj geometrii do `MarcowkiReferenceProject`.** Współrzędne ścian,
  obrysy pomieszczeń, otwory i rzędne kondygnacji nie należą do kanonicznego
  zbioru referencyjnego — jego źródło podaje tylko nazwy i powierzchnie.
  Geometria odrysowana z rzutów mieszka obok, w `reference/visual/`, i podlega
  regułom z sekcji „Model odrysowany ze źródła" wyżej. Kształt do prac nad samym
  rendererem daje jawnie syntetyczny `geometry/demo/SyntheticDemoHouse`.
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
rzutów, **analizatora projektów** i **produkcyjnego** renderera 3D.

Analizator — automatyczne czytanie cudzych rzutów i budowanie z nich modelu — to
przyjęty pomysł na później, a nie zadanie tego etapu. Pozostaje osobnym etapem
także po STAGE-013D: praca nad wiernością Marcówek tylko doprecyzowuje jego
kontrakt (elewacje i wizualizacje jako źródło kompozycji; brak cechy
rozpoznawczej kończy się pytaniem do użytkownika), nie zaczyna implementacji. Dopóki OWNER nie zleci go
wprost, nie zaczynaj OCR, wizji komputerowej ani obsługi wielu domów: model
Marcówek ma najpierw ustalić poprzeczkę jakości, którą taki analizator musiałby
osiągać. Jego kontrakt — wymagane wejścia, cechy do odzyskania i sytuacje,
w których ma **dopytać użytkownika** zamiast podstawić wartość domyślną — jest
spisany w `ARCHITECTURE.md` i jest dokumentacją, nie zadaniem. Brak danych ma
kończyć się pytaniem; odpowiedź wchodzi do modelu jako `DISPLAY_ASSUMPTION`
z podanym powodem, a nierozstrzygnięta niepewność zostaje `TRACE_UNCERTAIN`. Model domenowy i kontrakt geometrii
istnieją, ale nic ich jeszcze nie zapisuje. Rysuje je wyłącznie debugowy spike
na Filamencie (STAGE-012) — dowód wykonalności i wybór kandydata, a nie docelowa
architektura renderera; ta należy do STAGE-013.
Jeżeli zadanie tego nie obejmuje wprost — nie dodawaj tego.
