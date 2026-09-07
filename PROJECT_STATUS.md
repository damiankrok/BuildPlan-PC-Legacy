# Status projektu

## COMPLETE

- STAGE-001 shell — powłoka aplikacji Android (App Shell, nawigacja, ekrany
  placeholderowe)
- STAGE-002 domain model — kanoniczny model domenowy MVP
- STAGE-002A 3D domain alignment — `BuildingElement` ma jeden logiczny zakres
  (BUILDING/FLOOR) i relację 0..N do pomieszczeń; ściana dzielona to jeden
  element; czyste zapytania ukrywania kondygnacji/dachu i izolacji pomieszczenia;
  semantyka `Cost.amount` (brutto/zapłacone) i `Cost.stageId` (klasyfikacja)
- STAGE-010A reference structured building — jeden deterministyczny projekt
  referencyjny (dwie kondygnacje, 18 pomieszczeń, podane powierzchnie)
  w źródłach `debug`, wraz z proweniencją trzymaną poza domeną. Nie jest to
  prawda produktu ani dane użytkownika; release go nie kompiluje. Nadal nie ma
  parsera, geometrii ani renderera — kontrakt geometrii należy do STAGE-011.

## OPEN

- persistence
- real project UI/data
- 3D renderer
- costs UI
- domain model extensions (User, Document, Vendor, Contractor)
- authentication
- stages UI
- timeline
- statistics
- budget calculations (spent / remaining / overrun)
- cost allocation rules (STAGE-015)

## PARKED

- parser projektu
- OCR
- benchmarki
- PRO
- wykonawcy
- analiza rynku

## OWNER LATER TEST PACK

Ocena subiektywna, do wykonania przez właściciela produktu. Nie blokuje
technicznego PASS.

- ogólny feeling UI
- estetyka dark mode
- wygoda szuflady nawigacyjnej
- wielkości tekstu
- spacing
- ergonomia na telefonie trzymanym w jednej ręce
- wygląd Dashboardu i proporcje miejsca na model 3D
- zachowanie na realnym urządzeniu

## Do potwierdzenia przez OWNER-a

- `applicationId` `com.buildplan.app` jest decyzją roboczą i wymaga
  potwierdzenia przed publikacją w Google Play.
- Czy koszt może mieć kwotę ujemną (korekta / faktura korygująca). Model na
  razie tego nie ogranicza.
- Tryb firmowy: rozbicie kwoty na netto/VAT. W pierwszym MVP `Cost.amount` to
  kwota brutto, faktycznie zapłacona, i nie ma pól podatkowych.
- DEC-COST-ALLOCATION-001 (zasady podziału kosztów) pozostaje otwarta do
  STAGE-015. Obecny kształt `CostAllocation` jest prowizoryczny.
