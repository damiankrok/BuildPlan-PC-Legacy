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
| `./gradlew clean`              | czyszczenie artefaktów builda |

Na Windows użyj `gradlew.bat`.

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
    AppShell.kt      ramka aplikacji: TopAppBar + ModalNavigationDrawer
    navigation/      lista sekcji, NavHost, zawartość szuflady
    screens/         ekrany modułów (obecnie placeholdery)
    components/      komponenty współdzielone
    theme/           kolory, typografia, kształty

app/src/debug/java/com/buildplan/app/
  reference/         referencyjny projekt domu do prac deweloperskich,
                     bez geometrii (tylko debug, nie trafia do release)
    visual/          model wizualny Marcówek V1: geometria odrysowana
                     z aktualnych rzutów ARCHON, z klasyfikacją wiarygodności
                     każdej liczby; do wizualnej weryfikacji, nie do wymiarowania
  geometry/demo/     syntetyczny dom demonstracyjny z geometrią; wymyślone
                     wymiary, nie jest rekonstrukcją realnego projektu
  render/filament/   debugowy renderer i host podglądu 3D
```

Ekran **Model 3D** w wariancie debug otwiera się na modelu Marcówek. Przełącznik
u góry pozwala wrócić do syntetycznego domu (fikstura regresyjna renderera),
a pięć presetów widoku ustawia powtarzalnie kamerę i widoczność — od widoku
ogólnego po rzut poddasza z góry. Wariant release nadal pokazuje placeholder.

## Dokumentacja

- [ARCHITECTURE.md](./ARCHITECTURE.md) — decyzje techniczne podjęte do tej pory
- [PROJECT_STATUS.md](./PROJECT_STATUS.md) — co jest gotowe, a co otwarte
- [CLAUDE.md](./CLAUDE.md) — zasady pracy dla agentów kodujących
