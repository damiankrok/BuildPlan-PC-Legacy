# STAGE-025B — execution ledger

This is a working report, not a PASS declaration. STAGE-025A remains PARTIAL.

## Baseline

- Initial branch `main`, HEAD `6f7159691d52a3eaedd7903887b206233c53da81`.
- All 36 dirty files matched the prior final manifest by SHA-256. No unknown, missing or mismatching files; no staged edits. Diff inspected; whitespace check passed (line-ending normalization warnings only).
- Re-executed `:analyzer:test testDebugUnitTest assembleDebug --rerun-tasks`: success in 4m54s, 48 tasks executed.
- Checkpoint: `d3db1db90c969b9178b866b9734b2c1c0a883af9`; clean tree verified afterwards. Existing Git identity retained.
- Evidence outside repository: `C:/Users/damia/.codex/visualizations/2026/09/11/01a08f8e-fc5a-79c2-9fcd-c63eecb5ca32/stage025b` (E).
- Fresh baseline A/B and reference A captures use only emulator-5570, eight fixed cameras each. Reference contributes zero to automatic scoring.

## Iteration 1 — evidence and vertical mass topology

The former reconstruction considered only a lower roof level alternative. New graph records source assets, measurements, registered storeys, wall lines, room regions, openings, stairs and image features, plus provenance and contradictions. New vertical regions retain XY overlap, adjacent regions and separate roof ownership. JTS overlay is confined to an internal adapter; its output contains only BuildPlan polygons. Holes are triangulated without filling them.

First A/B run exposed a wrong rule: ground-minus-upper closure also includes regions under the main hip roof. Treating all of these as flat annexes added false roofs to B. Rejected results are retained under E/iteration1-rejected-roof-ownership. Roof ownership now intersects the primary roof coverage before adding exposed lower roofs. This is a generic correction from A/B, before holdout freeze.

## Policy and holdout

Initial source weights: exact/plan-section 4, elevations 3, independent multi-view 2, render 1, assumptions 0.25. Exact constraints remain hard. Initial finite budgets: 32 initial hypotheses, beam 8, 4 repair cycles, 8 local alternatives, 288 hypothesis evaluations, 2048 projections. Scoring raster starts at 128 square (maximum 256). These are bounded defaults, not benchmark constants. Changes must document generic motivation, A/B effects, runtime and policy version.

Project C has not been run. It may run only after all used development iterations and a recorded freeze commit. After freeze, production weights, tolerances and search limits are immutable. Source-supported material algorithm/generalization failure blocks PASS; unavailable source evidence is SOURCE_LIMITATION, with affected claims withheld. The inability to extract an available visible feature is not by itself a source limitation. Only P0/crash/security repairs may follow holdout, with frozen parameters preserved and A/B/C rerun.

## Dependency boundary

JTS core 1.20.0 is an `implementation` dependency of `:analyzer`, pure Java with no new native library. JTS types occur only in `reconstruction/internal/PlanarTopology.kt`; contract/app/snapshot isolation is tested. Distribution uses the upstream EDL-1.0 option and includes its notice in `app/src/main/assets/licenses/jts-EDL-1.0.txt`. Original Maven POM is saved in E. Release contents audit is pending.
