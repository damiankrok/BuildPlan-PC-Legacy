# Planer kosztów budowy

Natywna aplikacja **Android** do organizowania i analizowania kosztów budowy domu.

> **Bardzo wczesny etap.** Repozytorium zawiera powłokę aplikacji (App Shell,
> nawigacja, puste ekrany modułów), kanoniczny model domenowy oraz kontrakt
> geometrii budynku. Nie ma jeszcze renderera 3D, parsera rzutów,
> persystencji, backendu ani logiki biznesowej — nic nie zapisuje
> i nie odczytuje danych. Wszystkie wartości widoczne w interfejsie to
> placeholdery (`—`), a nie dane.

## Stack

Kotlin · Jetpack Compose · Material 3 · Navigation Compose · Gradle Kotlin DSL

## Wymagania

- **JDK 17+** (projekt budowany na JBR 21 z Android Studio)
- **Android SDK** z `compileSdk 36`
- Android Studio (aktualna stabilna wersja) — opcjonalnie, do pracy z UI

`minSdk` to 24, `targetSdk` 36.

## Otwarcie projektu

Otwórz katalog repozytorium w Android Studio (**Open**, nie *Import*). Studio
wykryje Gradle i utworzy `local.properties` ze ścieżką do SDK.

Przy pracy z linii poleceń ścieżka do SDK musi być dostępna: albo przez zmienną
`ANDROID_HOME`, albo przez `local.properties` (plik jest lokalny i nie trafia
do repozytorium):

```properties
sdk.dir=C\:/Users/<user>/AppData/Local/Android/Sdk
```

## Uruchomienie

Zainstaluj i uruchom na emulatorze lub urządzeniu:

```bash
./gradlew installDebug
```

albo użyj przycisku **Run** w Android Studio.

## Komendy

| Komenda                        | Opis                          |
| ------------------------------ | ----------------------------- |
| `./gradlew assembleDebug`      | build APK (debug)             |
| `./gradlew installDebug`       | instalacja na urządzeniu      |
| `./gradlew lintDebug`          | Android Lint                  |
| `./gradlew testDebugUnitTest`  | testy jednostkowe (JVM)       |
| `./gradlew :analyzer:test`     | testy modułu analizatora      |
| `./gradlew clean`              | czyszczenie artefaktów builda |

Na Windows użyj `gradlew.bat`.

Testy ewaluacyjne analizatora (dwa realne projekty ARCHON) są opcjonalne:
uruchamiają się tylko, gdy `BUILDPLAN_ANALYZER_EVIDENCE_DIR` wskazuje katalog
**poza** repozytorium; pierwszy bieg pobiera źródła i buforuje je tam,
kolejne odtwarzają bufor (`BUILDPLAN_ANALYZER_LIVE=1` wymusza sieć).
Pobranych rysunków nie commituj.

**Analyzer Lab** (tylko debug) to osobna ikona launchera obok aplikacji:
adres strony projektu → fakty, rzuty, kandydat, przedmiar, porównanie ze
źródłem, pytania i podgląd 3D kandydata; migawka JSON ląduje w pamięci
aplikacji (`files/analyzer/<klucz>/snapshot.json`). Wariant debug ma przez to
uprawnienie `INTERNET`; release nie.

## Struktura

```text
app/src/main/java/com/buildplan/app/
  MainActivity.kt
  domain/            kanoniczny model domenowy (bez zależności od Androida)
    model/           encje, identyfikatory, kontrakt kosztów, zapytania o budynek
    money/           Money i waluty
    units/           jednostki miar
  geometry/          kontrakt geometrii budynku (metry, Y w górę, rzut w XZ);
                     bez renderera, kamery i zależności od Androida
  ui/
    AppShell.kt      ramka aplikacji: ModalNavigationDrawer + NavHost
    navigation/      lista sekcji, NavHost, zawartość szuflady
    screens/         przestrzeń robocza (dom jako kanwa) i placeholdery sekcji
    components/      komponenty współdzielone: szkło, szyna narzędzi, oś czasu
    workspace/       stan otwartego chromu i polityka ruchu
    theme/           kolory, typografia, kształty

app/src/debug/java/com/buildplan/app/
  reference/         referencyjny projekt domu do prac deweloperskich,
                     bez geometrii (tylko debug, nie trafia do release)
    visual/          model wizualny Marcówek V1: geometria odrysowana
                     z aktualnych rzutów ARCHON, z klasyfikacją wiarygodności
                     każdej liczby; do wizualnej weryfikacji, nie do wymiarowania
  geometry/demo/     syntetyczny dom demonstracyjny z geometrią; wymyślone
                     wymiary, nie jest rekonstrukcją realnego projektu
  presentation/      profile prezentacji po identyfikatorze elementu:
                     dekompozycja widoku i pokrycie dachu (tylko debug)
  render/filament/   debugowy renderer, kanwa Filamenta i scena modelu
  ui/screens/        debugowa kanwa przestrzeni roboczej: narzędzia i inspektor
  analyzer/lab/      Analyzer Lab: debugowy harness prototypu analizatora

analyzer/src/main/kotlin/com/buildplan/app/analyzer/
  source/            bezpieczeństwo adresu, pobieranie, rozpoznanie strony
  site/              pakiet źródłowy i adapter witryny (archon/)
  asset/             manifest i pobieranie rysunków do pamięci aplikacji
  raster/ plan/      maski binarne, kawałki ścian, regiony, kalibracja, matcher,
                     obrys pomieszczenia (pierścień prosty albo nic), schody
  roof/ vertical/    szkielet prostoliniowy dachu, łańcuch rzędnych
  text/              szew odczytu tekstu z rysunku: lokalizacja przebiegów
                     glifów, bramka czytelności, parser i bramka jakości
  candidate/         kandydat analizy (nigdy nie jest modelem kanonicznym),
                     walidacja pierścienia
  quantity/          przedmiar: lica pomieszczeń, ściany, sufity, dach
  validate/          porównanie ze źródłem, braki, pytania
  snapshot/          deterministyczna migawka JSON
  pipeline/          ProjectAnalyzer — złożenie etapów
```

Ekran startowy **Dom** jest przestrzenią roboczą: w wariancie debug model
Marcówek wypełnia ekran od krawędzi do krawędzi, a narzędzia siedzą na prawej
krawędzi — warstwy, ujęcia (kamera i widoczność, powtarzalnie), styl, powrót
kamery i źródło modelu z przełącznikiem na syntetyczny dom (fiksturę
regresyjną renderera). Oś czasu kotwiczy dolną krawędź, a dotknięty element
opisuje inspektor. Wariant release pokazuje w tej samej przestrzeni
zarezerwowaną rycinę zamiast renderera.

## Dokumentacja

- [ARCHITECTURE.md](./ARCHITECTURE.md) — decyzje techniczne podjęte do tej pory
- [PROJECT_STATUS.md](./PROJECT_STATUS.md) — co jest gotowe, a co otwarte
- [CLAUDE.md](./CLAUDE.md) — zasady pracy dla agentów kodujących
