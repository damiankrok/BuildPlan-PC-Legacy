# Architektura

Dokument opisuje **wyłącznie decyzje faktycznie podjęte**. Nie jest planem
docelowej architektury.

## Stan na dziś

Jedna natywna aplikacja Android. Brak backendu, persystencji, autoryzacji
i modelu danych.

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
wydań; aktualizacja to osobna, świadoma decyzja, nie efekt uboczny tego etapu.

### Minimalne zależności

Poza powyższymi nie dodano nic: brak Room, Retrofit, Ktor, Hilt, Koin, Firebase,
bibliotek wykresów, silnika 3D i bibliotek płatności. Zależność dodajemy dopiero
wtedy, gdy jest realnie potrzebna.

### Podział pakietów

- `ui/AppShell.kt` — ramka aplikacji.
- `ui/navigation/` — `AppSection` (lista sekcji), `BuildPlanNavHost`,
  `AppDrawerSheet`.
- `ui/screens/` — ekrany modułów.
- `ui/components/` — komponenty współdzielone.
- `ui/theme/` — kolory, typografia, kształty.

Celowo **nie** utworzono warstw `data/`, `domain/`, `repository/`, `usecase/`.
Nie istnieje jeszcze logika, która by ich wymagała, a **finalny model domenowy
nie został ustalony**.

`AppSection` jest jedynym źródłem prawdy dla listy sekcji: korzystają z niego
szuflada, `NavHost` i ekrany. To fakt nawigacyjny, nie model danych. Trasy mają
stabilne angielskie slugi, wszystkie etykiety to polskie zasoby `strings.xml`.

### Nawigacja

Aplikacja telefonowa: `TopAppBar` z nazwą bieżącej sekcji plus
`ModalNavigationDrawer` z pełną listą dziewięciu sekcji. Świadomie nie użyto
dolnego paska — dziewięć pozycji by się w nim nie zmieściło. Aktywna sekcja jest
oznaczona w dwóch miejscach: jako `selected` w szufladzie i jako tytuł paska.

### Motyw

Dark mode jest jedynym wyglądem: aplikacja nie podąża za jasnym motywem systemu
i nie używa dynamic color. Paleta to mała skala neutralna plus jeden chłodny
akcent (`ui/theme/Color.kt`). Dostosowano tylko kolory, typografię i kształty —
nie budowano własnego design systemu.

### `applicationId` / namespace

Roboczo `com.buildplan.app`. **To decyzja robocza** i wymaga potwierdzenia
przez OWNER-a przed publikacją w Google Play. Migracji nazwy pakietu nie wolno
wykonywać samodzielnie.

### Model 3D

Renderer **nie istnieje**. Na Dashboardzie i na ekranie `Model 3D` zarezerwowano
dla niego miejsce (panel placeholderowy z prostym rysunkiem liniowym rysowanym
w Compose `Canvas` — to ilustracja, nie renderer). Gdy powstanie, będzie osobnym
modułem/feature. **Technologia renderera nie została wybrana** — OpenGL, Vulkan,
Filament i inne pozostają otwarte.

## Czego jeszcze nie ustalono

Model domenowy, persystencja, API, autoryzacja, testy instrumentalne,
technologia renderera 3D, docelowy `applicationId`.
