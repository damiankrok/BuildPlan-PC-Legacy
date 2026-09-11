# STAGE-025A — automatyczny analizator, raport końcowy

Wynik: **PARTIAL**. Poprawki działają technicznie i poprawiają ciągłość widocznych ścian oraz położenie części przeszkleń. Wygenerowany dom **nadal nie jest architektonicznie zgodny z modelem referencyjnym ani zdjęciami projektu**. Pozostały istotne P1. Zielone testy nie oznaczają zgodności domu ze źródłem.

## Repozytorium i dowody (1–4)

- Baza sprawdzona przed edycjami: czyste `main`, `6f7159691d52a3eaedd7903887b206233c53da81`.
- Końcowy HEAD: ten sam SHA; zmiany pozostają w drzewie roboczym, bez nowych commitów.
- Snapshot: schema 4, analizator `0.4.1-stage025a`. Wersja unieważnia także cache po wcześniejszej, odrzuconej korekcie poziomu garażu.
- Dowody poza repozytorium: `C:/Users/damia/.codex/visualizations/2026/09/11/01a08f8e-fc5a-79c2-9fcd-c63eecb5ca32/stage025a` (dalej **E**).
- Odtwarzanie widoków: `scripts/capture-stage025a.ps1`; uruchomienie rzeczywistego ekranu/usługi z URL: `scripts/smoke-stage025a.ps1`. Oba skrypty kierują każde ADB wyłącznie na `emulator-5570`.
- Zmienione obszary: pipeline, ekstrakcja obrazu, snapshot, pamięć fetchera/dekodera, adapter geometrii, ekran importu, debugowe narzędzia dowodowe, testy oraz dokumentacja. Nie zmieniono ręcznej referencji ani zależności natywnych. Pełny wykaz plików i ich SHA256: `E/final/changed-files.json`; nowe pliki również stanowią część zmiany.

## Audyt przepływu i utraty wierności (5–7)

| Etap | Rzeczywisty przepływ | Stwierdzony problem / stan końcowy |
|---|---|---|
| URL | resolver → tożsamość ARCHON → HTML | Zachowano walidację hosta, redirectów i źródła. |
| Strona | HTML/koszty → fakty, tabele pomieszczeń, manifest | Wartości publikowane mają proweniencję; nie są gotową geometrią. |
| Obrazy | manifest → bajty → raster → obserwacje | Usunięto zatrzymywanie wszystkich RGB; pozostało dekodowanie sekwencyjne i dwa rastry analizowanych rzutów. |
| Rzuty | maski ścian → przerwy → regiony → pomieszczenia | Błędy łączenia regionów/nazw; A 15/18, B 14/20 pomieszczeń. |
| Dach | obrys poddasza → rozwiązanie połaci → bryła niższa | Różnica obrysów nie wykrywa nakładającego się pasa/zadaszenia przed domem. Mały błąd powierzchni nie dowodzi prawidłowej topologii. |
| Poziomy | wysokość, spadek i ścianka kolankowa → łańcuch pionowy | Część poziomów pozostaje założeniami; małe etykiety przekroju nie zostały automatycznie odczytane. |
| Rejestracja pięter | maski → przesunięcie | Powiązanie schodów nie jest pełnym wspólnym układem wszystkich brył. |
| Elewacje | obrys/linie/szkło → przypisanie stron → hipotezy i otwory | Obrazy wpływają teraz na wynik, ale strony nie zawsze są rozstrzygnięte; fuzja wysokości objęła tylko po jednym otworze A/B. |
| Renders | obrazy perspektywiczne → cechy i konflikty | Brak dopasowania kamery, rekonstruowania cech bryły z perspektywy i oceny render–źródło. |
| Kandydat | hipotezy → wybór → bryły/obwiednie → QA | Istnieje częściowa, deterministyczna ocena, bez pełnego grafu ograniczeń i samonaprawy. |
| Ilości | stary model ścian + końcowy dach → powierzchnie | Ilości ścian nadal liczone z fragmentów, a podgląd z ciągłych obwiedni. Nie wolno utożsamiać poprawy podglądu z poprawą kosztorysu. |
| Podgląd | kandydat → CandidateGeometry → SceneModel/Canvas | Domknięto ściany do połaci, pasy nad/pod otworami i usunięto panele poza obwiednią. Nie są emitowane balkony, portale, prawidłowe grupy szklenia ani wygląd elewacji. |

Poprzedni adapter ucinał ściany na wysokości pomieszczenia, pozostawiał otwarte szczyty i umieszczał część paneli na przedłużeniach niewłaściwych fragmentów ściany. Obecny adapter korzysta z rzeczywistego odcinka przerwy, obwiedni kondygnacji i profilu dachu. Nie wolno traktować filtrowania nieprzypisanych paneli jako rozwiązania ich semantyki: 2 zewnętrzne ślady A i 10 B nadal nie pasują do elewacji.

## Źródła (8–13)

A: [Dom w marcówkach (GE)](https://www.archon.pl/projekty-domow/projekt-dom-w-marcowkach-ge-m2fa281446a8ca).
B: [Dom pod wiązowcem (N) ver. 2](https://www.archon.pl/projekty-domow/projekt-dom-pod-wiazowcem-n-ver-2-md6a1fa1493ad8).
W wejściu testowym B jest końcówka `md6a1fa1493ad8to`; resolver dochodzi do powyższego kanonicznego projektu.

Dane A użyte przez pipeline: obrys zabudowy 131,16 m², dach 150,57 m², wysokość 8,27 m, kąt 40°, ścianka kolankowa 1,30 m, dwie tabele po 9 pomieszczeń. B: 150,83 m², dach 246,70 m², wysokość 8,49 m, kąt 38°, dwie tabele po 10 pomieszczeń.

Osobiście porównano aktualne widoki z rzutami, przekrojem, elewacjami i wizualizacją A. Pliki źródłowe znajdują się w `E/run-a/assets` (ich wcześniejsza kopia w `D:/TRAVELAPPS/_stage025_evidence/run-a/assets`):

- parter `plan_ground-abfdb2625576.gif`, poddasze `plan_upper-06af9e912273.gif`;
- przekrój `section-d05811554a21.jpg`;
- przód `elevation_front-fecf3358dc06.jpg`, tył `elevation_rear-f156ae82210a.jpg`, lewa `elevation_left-149d9116dff2.jpg`, prawa `elevation_right-a5cc1beb64b8.jpg`;
- wizualizacja 800×600 `hero_render-d32a9a415739.jpg` i miniatura 200×150 `hero_render-18fc65eabe52.jpg`.

Przekrój pokazuje +7,95, +4,67, +3,06, 0,00, −0,32 oraz wysokości 272/266/252. Te odczyty wykorzystano wyłącznie w audycie człowieka; **nie wpisano ich do algorytmu produkcyjnego**. Dostępny obraz 400×300 nie jest obecnie prawidłowo rozpoznawany przez OCR analizatora. Front renderu potwierdza asymetryczne szklenie pod skosem, balkon, portal i pas niższej bryły zachodzący przed główną bryłę.

## Samodzielnie odtworzona baza (14–19)

Baza A/B powstała przez `EndToEndEvaluationTest`: resolver URL, rzeczywisty adapter, pobrane obrazy, raster, cały pipeline, ilości oraz snapshot. Testy rozwojowe odtwarzały **cache pobranych odpowiedzi**, nie pobierały ponownie strony przy każdym przebiegu. Oddzielne testy produktu na Androidzie wykonywały rzeczywistą usługę z URL.

Bazowe snapshoty i metryki: `E/baseline/e2e-a`, `E/baseline/e2e-b`. Pierwotne 8 widoków: `E/baseline/candidate-views`. Uczciwe końcowe porównanie tej samej kamery: `E/final/before-a` — bazowy snapshot i adapter odtworzony z bazowego SHA jako debugowy `BaselineCandidatePreview`.

Ręczną referencję znaleziono w debugowym modelu `DebugModel.MARCOWKI` i jego danych `reference`. Odtworzono ją przez rzeczywisty Filament, bez zmian źródeł referencji. Bazowe widoki: `E/baseline/reference-views`; końcowe 8 widoków ze stałą kamerą: `E/final/reference-views`.

Baza nie miała `SelfVerificationResult`: jej wynik automatycznej QA to **brak**, a nie 0 lub 100%. Stary wskaźnik kompletności luk nie był oceną zgodności wizualnej.

Rzeczywisty pomiar obciążenia eksperta: A 4 REQUIRED / 48 decyzji głównych; B 6 REQUIRED / 63 decyzje. Liczba diagnostycznych pytań pipeline: 14 w obu. Te trzy liczby mierzą różne rzeczy.

## Architektura runtime (20–31)

| Element wymagany | Zaimplementowany zakres i ograniczenie |
|---|---|
| EvidenceGraph | Nie zaimplementowano pełnego grafu. Obserwacje, URL źródeł, proweniencja i konflikty pozostają w istniejących strukturach. |
| Ograniczenia | Zachowanie przedziału otworu z rzutu; zgodność strony/poziomu; dodatnie powierzchnie i wysokości; odrzucenie dachu wywnioskowanego wyłącznie z ciemnej elewacji. Brak pełnych kolizji i ograniczeń topologicznych. |
| BuildingMassCandidate | Obrys, poziomy, rodzaj dachu, sąsiedzi, źródła, confidence. Główna bryła z najwyższej kondygnacji, pozostałe z istniejących secondaryMasses. Nie jest to nowy solver segmentacji brył. |
| Elewacje | Obrys, linie dachu, ciemne obszary, szklenia, fuzja proporcji z planem. Jasne szkło grupowane mimo cienkich słupków. |
| Renders | Istnieją obserwacje cech; brak pozytywnego wpływu dopasowania perspektywy na geometrię lub scoring. |
| OpeningFusion | Dopasowanie przedziału poziomego, kalibracja wysokości/sillu, odrzucanie konfliktów i niejednoznacznych dopasowań. Zachowuje szerokość/położenie z rzutu i priorytet znanej etykiety. |
| Rodziny otworów | Konserwatywne przeniesienie wysokości dopiero z dwóch zgodnych niezależnych pomiarów podobnej szerokości na tym samym piętrze. W A/B nie powstała taka rodzina. |
| Wygląd | Nie zrekonstruowano portalu, balkonu, cofnięć, listew, okładzin, kominów i okien połaciowych. |
| Hipotezy | Do dwóch wariantów: istniejące poziomy lub poziom niższego dachu z dwóch elewacji. Nie ma kombinatorycznego poszukiwania mas/okien/wyglądu. |
| Scorer | Obecność obrysów (`planTopology`, nazwa szersza od realnego sprawdzenia), profil elewacji oraz poziom niższego dachu. Wagi 0,35 / 0,40 / 0,25 normalizowane do dostępnych ocen. |
| Pętla | Jedno ograniczone wygenerowanie, ocena i wybór, potem fuzja otworów. `iterations` oznacza liczbę hipotez, **nie** liczbę pełnych cykli renderowania i naprawy. |
| Niepewność | TRACE_UNCERTAIN dla wysokości z proporcji; niewybrane hipotezy i powody odrzucenia zapisane; braki wewnętrzne. QA nie jest skalibrowanym prawdopodobieństwem poprawności. |

Ocena elewacji to IoU próbek górnego profilu bryły w 64 kolumnach, po normalizacji i tylko dla rozstrzygniętych elewacji. Nie mierzy położenia/kształtu okien, balkonu, kolorów ani perspektywy. `sourceCoverage.renders=2` oznacza dwa przetworzone zasoby, nie dwa niezależne dowody potwierdzające geometrię (miniatura i większy render mogą pokazywać ten sam widok).

## Iteracje rozwojowe (32–44)

### 1. Obwiednie i niższa bryła

Przyczyna: fragmenty ścian były pokazywane bez pełnego zamknięcia do połaci; poziom niższej bryły był przyjmowany z kondygnacji. Dodano model brył i ciągłych obwiedni oraz dwie hipotezy poziomu niższego dachu. A otrzymało 10 obwiedni, B 26. Gable closure poprawiło widoczną bryłę, lecz bez zmiany planu masy i liczby pomieszczeń.

A: garaż 2,981 → 3,259 m, 15 pomieszczeń, 13 zewnętrznych otworów, 0 wysokości z obrazu. Wynik hipotezy 0,916844 → 0,955424, ówczesny confidence ograniczony do 0,75. **Końcowy audyt wykazał, że wzrost tego wyniku nie oznaczał poprawy: wysokość dachu pogorszyła się.** B zachowało 3,067 m, 14 pomieszczeń, 25 otworów. Dowody: `E/iteration-1/e2e-a`, `e2e-b`, `candidate-views`.

### 2. Otwory, szkło i adapter

Przyczyna: jasne szkło mieszało się z niebem; małe detekcje maskowały większą grupę; położenie otworu było zaciskane do krótkiego fragmentu ściany. Dodano grupowanie szkła, usunięcie zawartych detekcji, rzeczywisty przedział otworu, fuzję wysokości z elewacją i poprawione cięcie paneli profilem dachu. Ograniczono panele bez dopasowania do obwiedni. Nie zmieniono szerokości odczytanych z rzutu.

A: 0 → 1 wysokości z obrazu (`f1-o10`, ok. 3,12 m, sill ok. 0,03 m). B: 0 → 1 (`f0-o19`, ok. 2,07 m, sill ok. 0,21 m). Oba są TRACE_UNCERTAIN, nie pomiarem z etykiety. Liczby otworów/pomieszczeń/dachów bez zmian. Profil elewacji i wybór hipotezy nadal jak po iteracji 1; brak metryki kompozycji otworów, więc nie przypisuje się jej liczbowej poprawy. Dowody: `E/iteration-2/e2e-a`, `e2e-b`, `candidate-views`; rozpoznanie obrazu w `E/final/visual-a`, `visual-b`.

### 3. Korekta regresji wykrytej w końcowym audycie

Przyczyna: górna granica ciemnej elewacji garażu mogła oznaczać attykę/okładzinę. Dwie obserwacje tego samego typu nie dowodziły poziomu konstrukcyjnego dachu. W referencji jest 3,06 m: po zmianie błąd wyniósł +0,20 m, podczas gdy baza różniła się o −0,079 m.

Ogólna reguła wymaga teraz potwierdzającej obserwacji krawędzi dachu przed zaakceptowaniem nowego poziomu. Nie zakodowano wartości 3,06 ani identyfikatora projektu. Dodano syntetyczny test dwóch ciemnych elewacji/attyki. A wraca do 2,981 m, błędny wariant ma twarde odrzucenie. B bez zmiany. Częściowy confidence obniżono proporcjonalnie do brakujących rodzin ocen, zamiast nadawać 0,75 na podstawie kilku łatwych sygnałów.

Final: A wynik wybranej hipotezy 0,916844, confidence 0,458422; B odpowiednio 0,864175 / 0,288058. To korekta rzetelności, nie dowód pogorszenia obrazu. Osiem końcowych widoków i metryki w `E/final`. Wcześniejsze stałe ujęcia z błędną wysokością zachowano w `E/iteration-2/fixed-candidate-views`.

## Końcowy A — źródło / baza / wynik / referencja (45–54)

| Cecha | Źródło i ręczna referencja | Baza automatyczna | Wynik automatyczny |
|---|---|---|---|
| Główna bryła | Dwukondygnacyjna, szczytowa | Przybliżony obrys | Obrys bez istotnej zmiany |
| Garaż | Niska prawa bryła | Obecny prosty blok | Obecny; odrzucono błędne podniesienie |
| Pas/zadaszenie przed domem | Zachodzi przed główną bryłę | Brak | Nadal brak |
| Szczyty | Zamknięte do połaci | Otwarta górna część | Domknięte profilem dachu |
| Balkon/balustrada | Widoczny na dużym szkleniu | Brak | Brak |
| Gruby portal szczytu | Wyraźny obrys w źródle/ref | Brak | Brak |
| Szklenie przednie pod skosem | Asymetryczna duża grupa | Uproszczone prostokąty | Jedna wysokość lepsza; kompozycja i skos nadal błędne |
| Szklenie tylne | Grupy na obu połowach | Uproszczone | Nadal niewłaściwe podziały/wysokości |
| Okna i wejście parteru | Część pełnej wysokości | Brakujące wysokości | W większości nadal założenia, niezgodne z elewacją |
| Brama garażowa | 1 | 1 | 1, wysokość/proporcje niepotwierdzone |
| Okna połaciowe | Widoczne, regularne | Nieodtworzone | Nieodtworzone; artefakty przy dachu nie są oknami |
| Kominy | Widoczne | Brak | Brak |
| Okładzina drewniana i ciemny pas | Mocne cechy renderu | Brak | Brak |
| Schody | Jeden układ komunikacji | 2 strefy detekcji | 2 strefy, nie pełny prawidłowy bieg |
| Pomieszczenia | 18 | 15 | 15; błędne przypisania nie naprawione |
| Panele poza domem | Nie występują | Wyraźne w bocznych/ukośnych ujęciach | Usunięte z podglądu, nie rozwiązane semantycznie |

| Metryka A | Baza | Końcowa | Źródło / referencja |
|---|---:|---:|---:|
| Pomieszczenia | 15 | 15 | 18 |
| Obrys parteru m² | 128,40 | 128,40 | 131,16 |
| Obrys piętra m² | 98,20 | 98,20 | Inna semantyka niż powierzchnia użytkowa |
| Bryły w nowym kontrakcie | brak kontraktu | 2 | Nie obejmują nakładającego pasa frontowego |
| Niższa bryła m² | 30,50 | 30,50 | 31,20 ref |
| Dach główny m² | 148,31 | 148,31 | 150,57 strona / 150,38 ref |
| Spadek / połacie | 40° / 2 | 40° / 2 | 40° / 2 |
| Kalenica / okap m | 7,97 / 4,68 | 7,97 / 4,68 | 7,95 / ok. 4,67 przekrój |
| Piętro / niższy dach m | 2,981 / 2,981 | 2,981 / 2,981 | 3,06 / 3,06 ref |
| Otwory zewnętrzne | 13 | 13 | 11 ref; wymaga korekty semantyki |
| Znane wysokości otworów | 0 | 1 | Pozostałe nierozstrzygnięte |
| Profil elewacji | brak runtime QA | 0,966167 | Ograniczona metryka obrysu, nie zgodność elewacji |
| Render–źródło | brak | **brak** | Nie wdrożono kamery i porównania |
| Confidence częściowej QA | brak | 0,458422 | Nie prawdopodobieństwo |

`plan-levels` wybrane z 2 hipotez, jedna została odrzucona. Margin 0 przy jednym poprawnym wariancie oznacza brak porównywalnego runner-up, nie rozstrzygnięcie z dużą przewagą. Obserwacje: 2 rzuty, 4 elewacje, 2 zasoby renderu, 24 fakty.

Osiem końcowych kamer: `E/final/candidate-views/view-0.png` … `view-7.png`: przód, tył, obie strony, dwa widoki ukośne, z góry, bez dachu. Przy każdym jest plik kamery, log udanych PixelCopy i SHA snapshotu. Baza: `E/final/before-a`; referencja: `E/final/reference-views`. Arkusze `detail-contact.png` są jedynie identycznymi powiększeniami oryginalnych zrzutów. `E/final/a-detail-before-after.png` pokazuje najważniejsze zmiany.

Pytania blokujące automatyczny podgląd: **0**; podgląd powstaje bez odpowiedzi. W opcjonalnej starej weryfikacji eksperckiej nadal **4 REQUIRED / 48 decyzji**, a pipeline przechowuje 14 diagnostycznych pytań. Nie usunięto ich z danych, aby sztucznie podnieść ocenę. Automatyczna publikacja modelu domenowego/kosztorysu nie jest częścią tej zmiany.

Nierozwiązane: geometria mas i elewacji opisana wyżej, perspektywa, brak oceny kompozycji otworów, brak pełnych kolizji brył, wysokości większości otworów, 2 zewnętrzne ślady bez obwiedni. Sumy powierzchni pomieszczeń i elewacji nadal różnią się istotnie od publikacji.

## Końcowy B i generalizacja (55–59)

B zachowuje 14/20 pomieszczeń, 25/88 otworów zewnętrznych/wszystkich, 90 ścian, kalibrację 38,28 px/m, obrysy 150,44 i 121,01 m². Dach HIP: 10 połaci, jedna niższa bryła, 248,39 m² wobec 246,70 m² (+0,69%). Poziom niższej bryły 3,067 m, okap 3,60 m, kalenica 8,19 m. Nowością jest 26 obwiedni i jedna niepewna wysokość otworu. Geometria planów/dachów i liczba pomieszczeń nie uległy zmianie.

QA: `plan-levels`, jedna hipoteza, profil elewacji 0,745328, ważony wynik 0,864175, confidence 0,288058. Brak metryki render–źródło. Brakuje potwierdzenia poziomu niższego dachu, wysokości większości otworów, dopasowania 10 śladów zewnętrznych. Źródło pokazuje złożony dach z lukarną i kominami; obecna rekonstrukcja nie odwzorowuje tej kompozycji.

Dowody A/B z pipeline: `E/final/e2e-a`, `E/final/e2e-b`; B przed/po w ośmiu kamerach: `E/final/before-b`, `E/final/after-b`. Wnioski z końcowej kontroli urządzenia dopisano poniżej po oględzinach.

Pytania wymagane do automatycznego podglądu B: 0. Opcjonalna weryfikacja: 6 REQUIRED / 62 decyzje (wcześniej 6 / 63), 14 pytań pipeline. Nie wykonano trzeciego projektu, więc nie ma dowodu szerszej generalizacji.

## Bezpieczeństwo, pamięć, izolacja (60–64)

Nie dodano seedów geometrii A/B, identyfikatorów projektów, nazw referencji ani wymiarów z ręcznego modelu do produkcyjnego analizatora. Test neutralności źródłowej i granic API przeszedł. Referencja, bazowy adapter dowodowy i aktywności dowodowe istnieją tylko w debug. Ścieżki plików zdjęć i kopie zdjęć nie weszły do kodu produkcyjnego.

Release używa wspólnego `CandidateGeometry` i lokalnego `AutomaticHouseCanvas`; nie importuje debugowej referencji ani Filament. Tworzone obiekty mają tymczasowe identyfikatory `cand-*`; adapter nie zapisuje do repozytorium danych użytkownika, kosztów ani budżetu. Zaktualizowano dokładne historyczne allowlisty testów, zachowując pozostałe zakazy i dodając kontrolę braku persystencji w nowym adapterze.

Nie dodano zdalnego AI/vision API, kluczy, płatnego modelu ani zależności chmurowej. Nie zaimplementowano również przyszłego interfejsu interpretatora obrazów; to jawny brak względem pełnego promptu.

Fetcher przechowuje maks. 32 MiB skompresowanych obrazów, nie wszystkie rastry RGB. Limit pojedynczego zasobu pozostaje 6 MiB. Android sprawdza wymiary przed alokacją, odrzuca >20 mln pikseli i próbkuje do maks. 4 mln pikseli. Obserwacje są zapisane w wersjonowanym, prywatnym cache/snapshocie; nie ma oddzielnego cache całej optymalizacji renderów. Zmiana wersji analizatora zapobiega odtwarzaniu wyniku z odrzuconym podniesieniem dachu. Syntetyczny test sprawdza retencję RGB, dekodowanie na żądanie i limit sumy bajtów.

## Weryfikacja (65–71)

Pełne polecenie: `gradlew :analyzer:test testDebugUnitTest lintDebug assembleDebug assembleRelease`, JBR Android Studio, lokalny cache Gradle, `BUILDPLAN_ANALYZER_EVIDENCE_DIR=E`. Wynik **BUILD SUCCESSFUL**, 5 min 11 s. Po wykryciu i poprawie zasłaniania w Canvas powtórzono pełne `testDebugUnitTest lintDebug assembleDebug assembleRelease`: **BUILD SUCCESSFUL**, 47 s. Kod analizatora nie zmienił się po jego pełnych testach.

- Analizator: 237 testów, 0 failures, 0 errors, 2 pominięte opcjonalne sondy (`dimension run probe`, `crop probe`); 235 wykonanych. A/B E2E i byte-for-byte round trip snapshotu wykonane.
- Aplikacja: 220 testów, 0 failures/errors/skips. Wykonane testy granicy release/API i porównanie debugowej referencji; dwa dodatkowe testy pokrywają lokalne zasłanianie nachylonych wielokątów i wklęsły obrys.
- Lint debug: 0 błędów, 31 ostrzeżeń (30 wcześniejszych i nowa sugestia użycia KTX `createBitmap` zamiast równoważnego API Bitmap).
- Debug APK oraz release APK zbudowane. Nie zmieniono zależności natywnych; nie powtarzano pełnego audytu 16 KB.
- Dodatkowe nowe testy: dach szczytowy + niższa płaska bryła; domknięcie do połaci; przerwa za fragmentem ściany; ślad tarasu poza konstrukcją; konflikt plan–elewacja i priorytet etykiety; jasne szkło ze słupkami; dwie ciemne elewacje nie mogą podnieść dachu; audyt neutralności; retencja obrazów.
- Nie ma pełnego pokrycia wszystkich 9 syntetycznych scenariuszy promptu: brak solvera wąskiej szyi aneksu, pełnego konfliktu render–plan i klasyfikacji niejednoznacznej bryły z wielu typów źródeł. Test dwóch okien nie jest testem kompletnej rodziny otworów.
- `E/iter/oracle/geometry-diff.txt`: końcowo 17 MATCH, 5 CLOSE, 5 DIFFERS. Sama tolerancja liczby nie potwierdza architektury. Pięć zasadniczych rozbieżności dotyczy liczby otworów, pomieszczeń, ich powierzchni, schodów i długości fragmentów ścian. Długość fragmentów i obwiednia podglądu mają różną semantykę.

Wszystkie działania urządzeniowe kierowano na `emulator-5570`, AVD `BuildPlan_API_36_16K`. W toku prac pierwotny renderer/emulator miał ANR SystemUI; Swiftshader dawał pusty Filament. Dowody poprawnych modeli pochodzą z uruchomienia tego samego AVD z GPU host. Nie czyszczono danych AVD ani globalnego serwera ADB. Wczesne puste/niegotowe obrazy nie są dowodem zgodności.

## Uczciwa ocena końcowa (72–77)

P0: w zweryfikowanych ścieżkach nie stwierdzono końcowego crasha, uszkodzenia danych ani przecieku referencji do release. Nie jest to deklaracja braku wszystkich możliwych P0.

P1: brak poprawnej dekompozycji nakładających się brył; brak balkonu/portalu; błędna kompozycja i wysokości większości przeszkleń; niedopasowane pokoje/schody; brak oceny perspektywy i pełnej automatycznej samonaprawy. Powierzchnie ilościowe nadal nie odpowiadają poprawionej obwiedni podglądu.

P2: okładziny, ramy i pokrycie, kominy/okna połaciowe, artefakty renderingu i uproszczony renderer release. Nie wszystkie te cechy są wyłącznie kosmetyczne: ich układ wpływa na rozpoznawalność projektu. W starym opisie diagnostycznym SIGNATURE_FEATURES pozostało zbyt szerokie zdanie o braku odczytu elewacji; brak dotyczy rekonstrukcji tych cech, a nie wszystkich obserwacji elewacji.

Ograniczenia źródła: niewielki przekrój i drobne napisy, watermark, miniatury, perspektywiczne zasłonięcia. Ograniczenia algorytmu: brak OCR tych etykiet, modelu otworu pod skosem/grup szklenia, nakładania brył, pełnego grafu i optymalizacji. Zdjęcia zawierają czytelne cechy, których analizator nadal nie wykorzystuje — nie można przypisać wszystkich braków jakości źródła.

Świadomie niewdrożone w tej części: zdalny interpreter, pełny EvidenceGraph, solver perspektywy, pełny wieloetapowy cykl self-repair, automatyczna publikacja domeny/kosztów. Powód klasyfikacji PARTIAL: implementacja nie spełnia wszystkich obowiązkowych cech silnika, a nie brak dostępu do emulatora lub zgody użytkownika.

Automatyczny jest obecnie przebieg URL → przybliżony kandydat → podgląd, bez odpowiedzi architektonicznych. **Nie jest jeszcze gotowy automatyczny silnik rekonstrukcji wiernej źródłu**. Rekomendacja: można wykonać techniczny OWNER retest przepływu importu i obejrzeć różnice, ale nie akceptować tego etapu jako architektonicznie gotowego ani używać go jako potwierdzonego kosztorysu.

## Kryteria akceptacji

| AC | Ocena | Uzasadnienie |
|---|---|---|
| 01 | TAK | 3 ogólne iteracje z A/B; trzecia cofnęła wykrytą regresję. |
| 02 | TAK dla kandydata | A z URL bez odpowiedzi; zgodność architektury osobno niezaliczona. |
| 03 | TAK dla kandydata | B z URL bez odpowiedzi. |
| 04 | CZĘŚCIOWO | Elewacje wpływają na hipotezę i po jednej wysokości otworu. |
| 05 | NIE | Brak skutecznej rekonstrukcji z perspektywy jako drugiego źródła. |
| 06 | CZĘŚCIOWO | Jest jawna QA, bez pełnej weryfikacji źródłowej. |
| 07 | CZĘŚCIOWO | Tylko poziom niższego dachu, brak pełnego zakresu niejednoznaczności. |
| 08 | NIE | Major mass topology nie została naprawiona. |
| 09 | NIE | Jedna wysokość to za mało wobec kompozycji elewacji. |
| 10 | CZĘŚCIOWO | Domknięcie do połaci; brak rozwiązania całego systemu brył/dachów. |
| 11 | CZĘŚCIOWO | Widoczna poprawa zamknięcia i paneli, bez pełnej zgodności ze źródłem. |
| 12 | TAK w zbadanym zakresie | W 8 widokach B nie wykryto nowej istotnej regresji; braki lukarny, otworów i detali pozostają. |
| 13 | TAK | Referencja tylko debug/test. |
| 14 | TAK | Brak źródłowych seedów w produkcji, test przeszedł. |
| 15 | CZĘŚCIOWO | Obwiednie są zachowane; pozostałe cechy nadal nie są odtwarzane. |
| 16 | TAK | Testy, lint, debug i release przeszły. |
| 17 | TAK z ograniczeniem | Niepewności zapisane, podgląd nie blokuje; pełne QA nie jest dostępne. |
| 18 | TAK | Raport wskazuje konkretne różnice i odrzuconą własną poprawkę. |

## Końcowe oględziny urządzenia

Rzeczywisty ekran importu i usługa Android wygenerowały A i B z podanych URL na wersji `0.4.1-stage025a`. Ekrany potwierdzają „Wynik pobrany ze strony”, automatyczny podgląd oraz opcjonalny przycisk przeglądu. `verificationOpened=false` w obu przebiegach; nie udzielono żadnych odpowiedzi architektonicznych.

| Przebieg | Wynik | Czas | Heap w chwili zakończenia | Maks. heap procesu |
|---|---|---:|---:|---:|
| A | Success, 15 pomieszczeń | 57 763 ms | 51 832 328 B | 201 326 592 B |
| B | Success, 14 pomieszczeń | 90 276 ms | 46 431 392 B | 201 326 592 B |

To pomiar pamięci przy zakończeniu, **nie peak**. Log obu przebiegów: największy raster 853×853, zero zachowanych RGB w fetcherze. Snapshoty Androida potwierdzają końcową hipotezę `plan-levels` i te same wyniki częściowej QA. Dowody: `E/final/product-a` i `E/final/product-b` — ekran, log, metryki, pełny snapshot. Testy te poprzedzały wyłącznie ostatnią zmianę komponentu Canvas; pipeline i debugowy ekran Filament nie zmieniły się po nich.

Osobiście sprawdzono 8 kamer A przed/po i 8 kamer referencji oraz 8 kamer B przed/po. Kamery mają ten sam yaw/pitch, odległość i światowy punkt skupienia; różne wartości offsetu focus kompensują różne środki granic modelu. A: odległość 31,903797, B: 33,84028. W B nie wykryto nowej istotnej regresji obrysu/dachów; domknięcie ścian i ograniczenie paneli poprawiają ciągłość. Nadal widoczne są artefakty przy dachu oraz nieprawidłowe otwory i brak lukarny. Ocena nie obejmuje niewidocznych lub nierozpoznanych elementów.

W końcowym teście dokładnego komponentu release, uruchomionego przez host debug, wykryto jeszcze błąd średniej kolejności rysowania: ściany zasłaniały część połaci. Zastąpiono sortowanie wielokątów buforem głębokości na piksel. Osobno sprawdzono testem przecinające się projekcje oraz wklęsły obrys. Ograniczenie rastra 1024×1024 utrzymuje bufory koloru/głębokości i bitmapę w granicy ok. 16 MiB dla największego kadru. Nie dodano biblioteki natywnej.

Ponowne oględziny Canvas A, obrotu gestem oraz B potwierdzają prawidłowe zasłanianie dachu. Pliki: `E/final/software-a.png`, `software-a-orbit.png`, `software-b.png`; odrzucony wcześniejszy obraz: `software-a-before-depth-fix.png`. To potwierdzenie tego samego komponentu skompilowanego również w release, nie twierdzenie, że zainstalowano osobno release APK. Pozostałe drobne artefakty przecinania geometrii i brak antyaliasingu nie zostały ukryte.

Jedno zestawienie źródła, referencji i końcowego kandydata: `E/final/source-reference-generated.png`. Wyraźnie pokazuje, dlaczego wynik pozostaje PARTIAL mimo poprawnych testów. Rejestr kontroli: `E/final/verification.json`, XML testów/lint w `E/final/test-results`, zestawienie referencji `E/final/geometry-diff.txt`. `git diff --check` nie wykazał błędów białych znaków (pozostają ostrzeżenia normalizacji CRLF).

PARTIAL_STAGE_025A_AUTOMATIC_ANALYZER
