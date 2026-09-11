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

## Iteration 2 — facade coordinates, opening groups and source projection

Global four-side assignment evaluates 24 bounded mappings using metadata, opening distributions and roofline projections. Blue glazing components retain an outer polygon rather than only a rectangle. Facade clipping produces resolved wall and opening surfaces with source lineage; panel subdivisions do not multiply structural deductions. Source-backed bands, gable frames and railing/balcony candidates now have geometry. Their depth remains an explicit display assumption where source views do not establish it.

The former segmentation opening removed thin diagonal fascia. Bounded restoration of original fabric fixes that loss; horizontal extent stays within two pixels of the robust seed, preventing attached vegetation from expanding the building. A connected glazing/railing component can now be separated by a horizontal rail and supporting mass/band. Dark timber under a light border is accepted as interior contrast rather than requiring bright wood classification.

Local perspective projection searches yaw, pitch and perspective strength (distance at a canonical field of view, because crop scale/FOV cannot be independently identified). Source masks, aspect ratio, silhouette IoU, octile edge-distance residual and roofline residual drive structural QA. Duplicate near-identical page thumbnails are downweighted by scoring the largest matching view once. Opening QA includes false-positive candidate openings as well as missing source openings. RGB MSE is not used.

Final iteration-2 A has 7 opening groups, 4 frame pieces, 5 bands, 1 railing and 1 balcony; B has 14 groups and 8 bands. These are extracted hypotheses, not an owner MATCH declaration. New source objective: A 0.852222, B 0.825632; scores are not probabilities and historical 025A coarse scores are not directly comparable. Structural rendering/quantities remain to be unified in iteration 3.

Device capture caught two real defects: horizontal feature faces passed to a vertical-only primitive, then a length tolerance incorrectly applied to a Newell area normal, producing an empty GPU mesh for a tiny valid triangle. Plane orientation now selects the primitive and the normal uses area tolerance. The real A/B snapshots pass a surface-to-GPU mesh gate; a synthetic mixed-plane regression also passes. Concave wall remnants are tessellated before display, preventing fan triangulation from filling door cuts. Failed captures are retained as failures, not evidence of success. Verified captures use E/iteration2-verified. Source comparisons and the completed QA report follow below.

## Policy and holdout

Final full-gate verification exposed an obsolete quantity assertion: supplying opening heights was assumed to remove all joinery uncertainty, even when the preview clipped those openings at an assumed sill or roof profile. The resolver now distinguishes coordinate fidelity from area fidelity: an uncut known-width/known-height pane has a known area despite an assumed sill; a clipped pane retains uncertainty and a named diagnostic. A synthetic test proves the former; the existing verification test now proves the latter and checks that the reported area equals the resolved polygons. Open passages are deducted from walls but are neither joinery nor rendered glass. This fixes the geometry/quantity semantics rather than marking clipped geometry user-confirmed. Analyzer version is `0.5.1-stage025b-6` to invalidate older cached reports.

## Iteration 6 — competing open/slab/enclosed interpretations

Source-backed horizontal facade features generate four bounded alternatives: slab, open covered volume, facade projection and enclosed volume. Their vertical regions preserve projected overlap without double-counting enclosed volume. Source railing corroboration prefers a slab; physical plan closure rejects an invented enclosed balcony. The alternatives reach actual geometry resolution (including enclosure walls in the rejected variant), projection and repair acceptance. A selected SLAB over OPEN_COVERED and FACADE_PROJECTION; ENCLOSED was hard-rejected for extending outside structural plan closure. This closes the missing alternative-use path, but does not recover A's unsupported front mass depth or B's missing roof composition.

Exact measured fields and opening widths are protected across repairs. These checks preserve established metric facts; the initial raster-to-metre calibration and incomplete semantic ownership still limit architectural correctness. No seventh development iteration is used. Final test, freeze, holdout and visual evidence results are recorded below.

## Iteration 5 — secondary roof evidence and false-feature rejection

Added source-neutral secondary roof elements: perpendicular elevation constraints locate stack footprints; facade coordinate/height rays intersect individual roof planes for rooflights. These remain separate from the primary roof solver. Accepted elements participate in projection, feature scoring, resolved roof holes and quantities. Source observations now retain their actual outline in the evidence graph, and resolved masses/facades/groups/elements are linked back to their supporting observations.

Image inspection rejected an apparent success: the first stack pairings in B used profile gaps inside the roof as if they were above-roof stacks. Background-above-top validation removed these false observations. Similarly, neutral watermark lettering produced small fake rooflights. Rooflight extraction now fills holes in the roof support mask and requires a sufficiently sized blue reflection component; monochrome reflection remains an extraction limitation. Current A recovers two source-visible rooflights on one slope; the opposite-slope rooflight and chimneys remain unresolved. B's original three chimneys are visible in source, but their 3D recovery remains P1. A high feature count from false positives was not retained as progress.

## Iteration 4 — global room assignment and stair rejection

Replaced room-by-room greedy selection with a complete-floor cost matrix over singleton and connected region groups, then deterministic exclusive assignment with a 128-state beam, 32 alternatives per room and 4096 unique connected groups (up to six regions). All area tolerances retain their published floor/usable semantics. A structural cue penalty was increased from 0.02 to 0.15 after A/B exposed a stair room being moved away from its detected treads merely for a slightly smaller area residual. This applies inside the admissible area band and cannot override it. No holdout data was used.

A retains 15/18 rooms; B recovers 15/20 rather than 14/20. This is bounded global optimization, not proof of label identity: near-cost alternative assignments remain diagnostics. Stair topology now separates shaft, flights, landing remainder and slab openings, and rejects wall-colliding or uncorroborated floor transitions. A/B still do not recover a complete accepted flight topology; no invented ascent direction is rendered. The remaining failure is explicit P1, not hidden by the improved room count. Synthetic collision and slab-opening tests pass.

## Iteration 3 — bounded repair and final geometry

The runtime evaluates a plan-only and source-opening hypothesis, then proposes source-backed feature families. Each trial resolves the full geometry, projects it with a shared bounded camera budget, and accepts only a source-score improvement with valid hard constraints and no weaker plan/room score. A accepted bands, frames, then balcony/railing (0.773712 to 0.838350, 8 evaluations/280 projections); B accepted bands (3 evaluations/250 projections). Four cycles include convergence, not four arbitrary vertex edits. Rejected sibling repairs remain in the trace. The initial viewpoint fit is reused for fair local comparisons. This is a real but still narrow repair vocabulary.

One final resolved surface set now supplies both preview and quantities: exterior walls after opening union/difference, internal traced walls, physical openings, roof facets, slabs, room finish faces, floor/ceiling polygons and integrated volume. Quantity generation runs after repair and carries the geometry fingerprint. Stale geometry is invalidated for both consumers after candidate edits. Roof quantity explicitly includes primary and secondary facets; the older roof publication comparison retains its primary-only scope. Internal lintels and stair slab voids are still unresolved at this iteration; geometry unification does not imply source accuracy.

Synthetic tests cover accepted repair, hard plan rejection even with a perfect visual score, convergence/determinism, and area/lineage equality between preview surfaces and quantity deductions. Full app tests pass after the raster release guard was narrowly updated to allow only the JTS license text (all reference rasters remain prohibited). Coalescing physical-element fragments removes triangulation diagonals without changing geometry.

Initial source weights: exact/plan-section 4, elevations 3, independent multi-view 2, render 1, assumptions 0.25. Exact constraints remain hard. Initial finite budgets: 32 initial hypotheses, beam 8, 4 repair cycles, 8 local alternatives, 288 hypothesis evaluations, 2048 projections. Scoring raster starts at 128 square (maximum 256). These are bounded defaults, not benchmark constants. Changes must document generic motivation, A/B effects, runtime and policy version.

Project C has not been run. It may run only after all used development iterations and a recorded freeze commit. After freeze, production weights, tolerances and search limits are immutable. Source-supported material algorithm/generalization failure blocks PASS; unavailable source evidence is SOURCE_LIMITATION, with affected claims withheld. The inability to extract an available visible feature is not by itself a source limitation. Only P0/crash/security repairs may follow holdout, with frozen parameters preserved and A/B/C rerun.

## Dependency boundary

JTS core 1.20.0 is an `implementation` dependency of `:analyzer`, pure Java with no new native library. JTS types occur only in `reconstruction/internal/PlanarTopology.kt`; contract/app/snapshot isolation is tested. Distribution uses the upstream EDL-1.0 option and includes its notice in `app/src/main/assets/licenses/jts-EDL-1.0.txt`. Original Maven POM is saved in E. Release contents audit is pending.
