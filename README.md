# Planer kosztów budowy

Natywna aplikacja **Android** do organizowania i analizowania kosztów budowy domu.

> **Bardzo wczesny etap.** Repozytorium zawiera dopiero szkielet aplikacji:
> App Shell, nawigację i puste ekrany modułów. Nie ma jeszcze modelu domenowego,
> backendu, bazy danych ani żadnej logiki biznesowej. Wszystkie wartości widoczne
> w interfejsie to placeholdery (`—`), a nie dane.

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
  ui/
    AppShell.kt        ramka aplikacji: TopAppBar + ModalNavigationDrawer
    navigation/        lista sekcji, NavHost, zawartość szuflady
    screens/           ekrany modułów (obecnie placeholdery)
    components/        komponenty współdzielone
    theme/             kolory, typografia, kształty
```

## Dokumentacja

- [ARCHITECTURE.md](./ARCHITECTURE.md) — decyzje techniczne podjęte do tej pory
- [PROJECT_STATUS.md](./PROJECT_STATUS.md) — co jest gotowe, a co otwarte
- [CLAUDE.md](./CLAUDE.md) — zasady pracy dla agentów kodujących
