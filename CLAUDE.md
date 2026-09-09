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
  korekcie i OWNER widzi wtedy dwa różne domy. Warstwy zdjętej **nie rysuj
  wcale** — żadnej widmowej siatki, żadnego konturu przez lite ściany; ciągłość
  gwarantują testy podzbioru, nie duch dachu nad odsłoniętym poddaszem.
  W podstawowym trybie „Bez dachu" po obwiedni dachu nie zostaje nic: ani
  połacie, ani pas okapowy, ani ramy szczytowe.
- **Dekompozycja to nie własność.** Element może być semantycznie
  `WholeBuilding` (rama szczytowa, pas okapowy) i wizualnie należeć do obwiedni
  dachu. Rozstrzyga to `presentation/DecompositionProfile` — mapa po
  `BuildingElementId` obok modelu referencyjnego, neutralna wobec źródła, która
  może **tylko zawężać** odpowiedź domeny. Nie zmieniaj przez nią
  `BuildingElementScope` ani `roomIds` i nie dopisuj w rendererze `if`-a
  o ramie.
- **Pełna bryła nie przecieka liniami.** Kontur to krawędzie cech: załamania,
  brzegi otwarte i ościeża otworów. Szew między dwiema współpłaszczyznowymi
  ścianami jednej bryły, przekątne triangulacji i linie zasłonięte litą
  powierzchnią nie są rysowane (`BuildingRenderMesh.featureEdges`, jeden
  materiał liniowy z testem głębi). Styl (`RenderStyle`) zmienia światło,
  barwy i przebiegi — nigdy geometrię ani zbiór encji.
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
- **Pokrycie dachu to prezentacja, nie dach.** Dachówki żyją
  w `presentation/RoofCover.kt` (spec i profil po `BuildingElementId` obok
  modelu referencyjnego) i w generatorze `render/filament/RoofCoverMesh.kt`,
  który czyta jedną płaską `RoofFacetGeometry` i nie zna kalenicy, liczby
  połaci ani Marcówek. Kanoniczny dach — jego połacie, wierzchołki
  i powierzchnia — nie zmienia się o bajt; nie ma elementu ani identyfikatora
  na dachówkę, a dotknięcie dachówki oznacza dach. Jedna encja Filamenta na
  połać, nigdy na dachówkę; bez konturu na dachówkach; ta sama mapa
  identyfikatorów, więc „Bez dachu" usuwa pokrycie bez żadnej nowej reguły.
  Blokery (kominy, okna połaciowe) buduj z `LocalBounds` prymitywów, które
  model już rysuje — nie z drugiej listy współrzędnych. Moduł, wybrzuszenie
  i wyniesienie to `DISPLAY_ASSUMPTION`. Bez tekstur, koloru, animacji
  i katalogu pokryć; metadane (`RoofCoverTile`, sygnał `UV0`) są szwem pod
  przyszły ruch, nie ruchem.
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
  jednego kanonicznego modelu; zdjęta warstwa znika, a nie zostaje widmem.
  Rzut, otwory, garaż i relacje elewacyjne mają być stabilne między stanami.
- **Drzwi wewnętrzne to element obiegu, nie dekoracja.** Każde źródłowe
  drzwi w ściance działowej to dziura w `WallGeometry` plus własny element
  `DOOR` z parą pomieszczeń tej samej kondygnacji. Ścianka, która szczelnie
  zamyka pokój, jest błędem rzutu, nie uproszczeniem. Klapy, klamki i ościeżnice
  nie istnieją.
- **Schody odrysowuj z rzutu, nie z centralnej linii.** Biegi to pełne pasma
  klatki, zakręty to zabiegi cięte po przekątnej naroża, a pierwszy i ostatni
  stopień muszą wychodzić z pomieszczenia przez przerwę w ściance, nie przez
  ściankę. Liczba stopni pozostaje `DISPLAY_ASSUMPTION`.
- **Samoaudyt jest ograniczony i nie zastępuje akceptacji.** Etap korekcyjny
  ma najwyżej trzy fazy audyt→poprawka z limitem poprawek na fazę; po ostatniej
  wynik wraca do OWNER-a. Zieleń techniczna nigdy nie oznacza „to jest mój dom".

## Analizator projektów (`:analyzer`) — reguły twarde

Analizator mieszka w osobnym, czysto-JVM module `analyzer/` (pakiet
`com.buildplan.app.analyzer`), a jego debugowy harness w
`app/src/debug/java/com/buildplan/app/analyzer/lab/`. Prototyp ze STAGE-023A.

- **Rdzeń nie zna żadnego projektu.** Żadnych nazw, sloganów, kluczy,
  współrzędnych ani wartości oczekiwanych konkretnego domu w `analyzer/src/main`.
  Wartości referencyjne Projektu A i B żyją wyłącznie w testach ewaluacyjnych
  (`analyzer/src/test/.../evaluation/`). Pilnuje tego `AnalyzerPurityTest` —
  nie obchodź go.
- **Każda liczba ma proweniencję.** Wynik analizatora to `Measured` z
  `FactFidelity` (`SOURCE_EXACT` / `SOURCE_TRACED` / `SOURCE_DERIVED` /
  `DISPLAY_ASSUMPTION` / `TRACE_UNCERTAIN` / `MISSING` / `CONFLICTING`)
  i `Provenance` (adres, lokator, metoda). Nagie `Double` w wyniku to błąd.
- **Brak wygrywa ze zgadywaniem.** Czego źródło nie podaje, to `MISSING`
  z pytaniem do użytkownika; jeżeli coś trzeba przyjąć, żeby domknąć bryłę,
  to jest `DISPLAY_ASSUMPTION` z powodem i pytaniem — nigdy „typowa wartość"
  wyglądająca na zmierzoną. Nie kalibruj rzutu przez dopasowanie powierzchni
  pomieszczeń; powierzchnie są sprawdzeniem, nie kotwicą.
- **Obrys pomieszczenia to pierścień prosty albo nic.** `RoomCandidate.polygon`
  jest `null` dokładnie wtedy, gdy `geometryState == UNRESOLVED_REGION`;
  trzeciej możliwości nie ma. Pierścień, który sam siebie przecina, ma i tak
  pole z shoelace'a i i tak odpowiada na `contains`, więc niepoprawnego
  nie wolno wypuścić „tymczasowo". Kryteria i deterministyczna naprawa
  (tylko usuwanie, nigdy łączenie) są w `candidate/RingValidity.kt`, złożenie
  w `plan/RoomGeometry.kt`; pole pierścienia musi zgodzić się z polem pikseli,
  z których był śledzony. Pokój bez pierścienia ma `MISSING` na suficie,
  kubaturze i licach ścian — nigdy małą liczbę wyglądającą na zmierzoną.
- **Powierzchnia podłogi to regiony, nie pierścień.** `plannedArea` liczy się
  z pikseli regionów **przed** mostkowaniem progów (do lica ścian, jak mierzy
  je strona); pierścień obejmuje dodatkowo progi łączące części pokoju. To dwie
  różne liczby i nie wolno ich mieszać.
- **Otwór zewnętrzny ma pokój po dokładnie jednej stronie.** Pokój po obu
  stronach to otwór wewnętrzny; brak pokoju po obu stronach to nie otwór, tylko
  przerwa między kreskami na tarasie. „Co najmniej jedna strona" wpuszcza
  widmowe okna do obwiedni.
- **Mur to nie obwiednia.** Suma odrysowanych kawałków ścian zewnętrznych nie
  zawiera otworów z definicji (otwór to przerwa *między* kawałkami), więc nic
  nie może ich od niej odjąć drugi raz. Obwiednia (obwód obrysu × wysokość,
  nad otworami) to osobna wielkość. Liczbę ze strony porównuj z widełkami
  brutto–netto: gdy wypada między nimi, to `NOT_COMPARABLE` — różnica jest
  definicją, nie geometrią.
- **Odczytu tekstu z rysunku nie wolno wymuszać.** Kontrakt to `text/`
  (`DrawingTextExtractor`, `TextObservation`), a `MIN_LEGIBLE_GLYPH_HEIGHT_PX`
  jest bramką: poniżej niej rozpoznawanie nie jest podejmowane w ogóle. Wynik
  wchodzi do modelu jako `SOURCE_EXACT` dopiero przy `READ`, wysokiej pewności,
  pełnym dopasowaniu wzorca i fizycznie możliwej wartości
  (`DimensionLabelParser`). Rozpoznany wymiar sprzeczny z odrysowaną
  geometrią odrzucamy — etykieta nie przebija rysunku.
- **Kandydat nigdy nie staje się prawdą kanoniczną bez weryfikacji.**
  `ProjectAnalysisCandidate` i `ProjectAnalysisSnapshot` nie zapisują nic do
  `domain/`. Podgląd w Lab buduje ulotny `Building` z identyfikatorami `cand-`
  i nie dotyka `MarcowkiReferenceProject` ani `MarcowkiVisualModelV1`.
- **Dwie semantyki ścian.** Powierzchnia lica pomieszczenia (tynk, malowanie —
  osobno dla każdej strony) i powierzchnia konstrukcyjna ściany (raz na ścianę)
  to różne wielkości. Ściana dzielona to jeden `WallCandidate` i dwie
  `MeasuredSurfaceCandidate`. Nie porównuj dwustronnej sumy lic z agregatem
  konstrukcyjnym ze strony i nie nazywaj tego niezgodnością.
- **Sufit to nie podłoga.** W pomieszczeniach poddasza rozróżniaj sufit
  płaski i skosy (powierzchnia po połaci); powierzchnia użytkowa wg reguły
  wysokości (100 % > 2,2 m, 50 % 1,4–2,2 m) jest osobną wielkością od
  powierzchni podłogi.
- **Regresja na drugim domu jest obowiązkowa.** Każda zmiana w segmentacji
  rzutu, solverze dachu, łańcuchu pionowym albo silniku przedmiaru ma być
  uruchomiona na obu projektach ewaluacyjnych
  (`BUILDPLAN_ANALYZER_EVIDENCE_DIR=<katalog poza repo> ./gradlew :analyzer:test`)
  i nie może naprawiać jednego domu kosztem drugiego bez zapisu w raporcie.
- **Bez zdalnej AI w analizatorze.** Żadnych klientów LLM/wizji, embeddingów
  ani „zapytaj model". Deterministyczne parsowanie HTML (jsoup), własna
  morfologia rastra, szkielet prostoliniowy. Lokalny OCR, jeśli kiedyś
  dojdzie, jest narzędziem ekstrakcji, nie autorytetem.
- **Sieć tylko do publicznych stron obsługiwanej witryny.** Allowlista hostów,
  rewalidacja każdego przekierowania, odrzucenie adresów prywatnych,
  limity rozmiaru. Nie pobieraj niczego poza stroną projektu, jej rysunkami
  i podstroną kosztów. Cudzych rysunków nie commituj — trafiają do pamięci
  aplikacji albo do katalogu dowodów poza worktree.
- **Formatowanie liczb w rdzeniu jest niezależne od locale** (`Locale.ROOT`),
  bo migawka ma być bajt w bajt taka sama na każdym urządzeniu.

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

Backendu, API, bazy danych, Room, autoryzacji, rzeczywistych danych,
**produktowego** analizatora projektów i **produkcyjnego** renderera 3D.

Analizator istnieje od STAGE-023A wyłącznie jako **prototyp badawczy**
w module `analyzer/` z debugowym Lab (reguły w sekcji „Analizator projektów"
wyżej). Nie ma wejścia w produkcie, nie zapisuje nic do domeny i nie czyta
tekstu z rysunków. Jego kontrakt — wymagane wejścia, cechy do odzyskania
i sytuacje, w których ma **dopytać użytkownika** zamiast podstawić wartość
domyślną — jest spisany w `ARCHITECTURE.md`. Nie rozszerzaj go (OCR, drugi
adapter witryny, przejście kandydata do modelu, UI produktowe) bez wyraźnego
zlecenia OWNER-a. Brak danych ma kończyć się pytaniem; odpowiedź wchodzi do
modelu jako `DISPLAY_ASSUMPTION` z podanym powodem, a nierozstrzygnięta
niepewność zostaje `TRACE_UNCERTAIN`. Model domenowy i kontrakt geometrii
istnieją, ale nic ich jeszcze nie zapisuje. Rysuje je wyłącznie debugowy spike
na Filamencie (STAGE-012) — dowód wykonalności i wybór kandydata, a nie docelowa
architektura renderera; ta należy do STAGE-013.
Jeżeli zadanie tego nie obejmuje wprost — nie dodawaj tego.
