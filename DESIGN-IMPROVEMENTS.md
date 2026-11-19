# Design improvements

This file lists design, refactoring and code-quality work for SpacePixels: changes to how the code is organised, not to what the application does. New or changed functionality is in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current design is described in [DEVELOPER.md](DEVELOPER.md) and [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md).

Every item here is a proposal until the maintainer decides on it. When an item is completed or closed, remove it from this file; describe implemented behaviour in DEVELOPER.md or HIGH_LEVEL_DESIGN.md, and use git history for the proposal and its reviews.

**How to use issue reviews.** Before implementing an item, evaluate its review comments against the current code. Record which comments are accepted, adjusted or rejected, why, and the relevant verification with the change. Reviews are input, not authorization to implement a proposal or automatic proof of a current bug. Recheck references and caller counts as other work lands.

## Pending work

| ID | Change | Priority | Size |
|---|---|---|---|
| [SP-21](#sp-21-upgrade-nom-tam-fits-after-the-dither-fix) | Upgrade nom-tam-fits after the dither fix | Low | Small |
| [SP-22](#sp-22-upgrade-gson-to-2150) | Upgrade Gson to 2.15.0 | Low | Small |
| [JT-04](#shared-logging-review-jt-04) | Shared logging review across all three repositories | Medium | Review first |
| [SP-26](#sp-26-review-of-the-sky-catalogue-fallback-services) | Unify sky-catalogue fallback chains (Item 6) | Medium | Small |
| [SP-27](#sp-27-asteroids--comets-window-review) | Asteroids & Comets window review | Medium | Small |

Keep dependency maintenance (SP-21 and SP-22) separate from refactorings, according to each item's timing.

Refactorings are intended to preserve behaviour. Changes to accepted inputs, viewer lifetime, settings policy or public contracts need explicit decisions under the [working rules](DEVELOPER.md#working-rules). Check implementation changes with the relevant unit and GUI mechanics tests. Items that touch detection or reports also need byte-identical deterministic `report-data.json`, `detection_config.json` and CSV content on the reference datasets, using the [normalization and comparison method](DEVELOPER.md#real-data-regression).

### SP-21. Upgrade nom-tam-fits after the dither fix

**Deferred until the upstream fix is released.** Upgrade nom-tam-fits from the [current version](DEVELOPER.md#dependencies) to 1.23.1 when available, after confirming the fix for the dither sequence at blank and zero pixels (#908).

With 1.23.0, dithered floating-point `.fz` frames still differ from cfitsio by about one quantization step in tiles that contain blank or zero pixels: 1 ADU after SpacePixels' 16-bit conversion, on a few hundred pixels of a frame. Integer frames are exact.

**Verification.** Coordinate the upgrade across the repositories that use nom-tam-fits, repeat the comparison with cfitsio (through astropy), and run the reference-data regression in DEVELOPER.md. Report any changed pixels and detection results before committing.

### SP-22. Upgrade Gson to 2.15.0

**Deferred to the next dependency update.** Upgrade Gson from the [current 2.14.0](DEVELOPER.md#dependencies) to 2.15.0 in all three repositories, reviewing its change of the default `Map` type to `LinkedHashMap`.

**Verification.** Round-trip saved settings files and check that their values and compatibility are preserved; run the relevant tests and reference-data comparisons from DEVELOPER.md.

### Shared logging review (JT-04)

**Maintainer decision (2026-11-02).** Review logging overall across SpacePixels, JTransient and JPlateSolve. The scope, review deliverables and verification are maintained once in [JT-04](../JTransient/DESIGN-IMPROVEMENTS.md#jt-04-review-logging-across-the-three-repositories).

SpacePixels' part covers GUI/CLI output, `DebugOutput`, library diagnostic bridges, progress versus logging, subprocess output, error presentation and the owner of verbosity and destinations. Evaluate actual duplication and operational value before choosing changes. Current logging behaviour remains documented in [DEVELOPER.md](DEVELOPER.md).

### SP-26. Review of the sky-catalogue fallback services

**Scope: Item 6 refactoring remaining.** Items 1–5, 7, 8, 9 and 10 are implemented in code and the manual (2026-11-11), and Item 11 was decided. Only Item 6 remains pending.

- **Completed items (2026-11-11):**
  - Items 1–5: Truncated cone search OVERFLOW handled as a partial step; server-side column selection and V < 2 filtering on mirrors; tangent-plane polygon containment near celestial poles (`SkyField`); failed copies recorded in fetch summary without flagging complete catalogue incomplete; unresponsive copies skipped for later questions in the same attempt.
  - Items 7–10: Cleaned planned-step bookkeeping at front of `pending`; standardized service names (`Gaia via VizieR`); exact HTTP status reporting and parse failure cause logging; updated manual documentation for fallback depth rows and query behaviour.
  - Item 11: Retained observing site in saved JPL addresses for reproducibility.

- **Pending refactoring (Item 6): Unify fallback chains.**
  - **Problem:** In `SkyCatalogueClient`, the fallback chains are written three ways: SIMBAD uses a loop over parallel arrays (`endpoints`, `services`, `places`), VSX uses three sequential try blocks, and Gaia stars use try/catch nested three deep with an out-parameter `String[] source` and two `Runnable` progress hooks.
  - **Proposal:** Follow the "few patterns applied consistently" principle: use one shared descriptor of a copy (service name, source name, endpoint, query and column mapping) and one loop. This eliminates nesting, the out-parameter, and duplicated `depthQuery`/`depthArchiveQuery` box code.
  - **Verification:** Live probe of each provider, catalogue regression on reference datasets, and unit tests covering error/fallback transitions.

### SP-27. Asteroids & Comets window review

**Code review (2026-11-17).** Review of `gui.AsteroidsCometsWindow` and the `mpc` types it uses, against the [F33](FEATURE-REQUESTS.md#f33-mpc-observation-export-and-find_orb) window as it stands. The split into Identify, Measure, Check and Export is sound: nothing is submitted by itself, weak sub-stacks and reports that lack a detection's size and signal-to-noise block the file, Find_Orb's result is kept only when its residual times match the observations sent, and the MPC test runs only after the user confirms. The points below are the ones worth changing. Recheck each against the current code before editing it.

- **Compute orbit ignores "This night only".** The ADES file leaves out linked nights when that box is ticked, and it leaves out a night already marked submitted. `openFindOrb` still adds every linked night whose site matches, and the paste for the online Find_Orb does the same. The fitted orbit and the file can describe different observations. The orbit should use the same nights as the file.
- **A designation drops the other nights without saying so.** Linking an unidentified object puts its nights in one tracklet. Saving an MPC designation (`MpcRecords.tracklet`), or taking a single known name from a linked night (`namedObject`), then writes only this night. The object row still shows "+ N nights", and the ready note only says the object is reported under that designation. The note and the row should say that the other nights are not in this file.
- **A failed station check forgets an earlier disagreement.** When "Check with JPL" finds the observatory code far from the observing site, export is blocked (`AdesPsv.stationProblem`). A later check that fails, for example because JPL cannot be reached, sets `stationProblem` back to null in `checkStation`, so the file becomes ready with the same code. Keep the disagreement until a later check agrees. Export also stays ready when the station has never been checked.
- **MPChecker is given code 500 when no observatory code is set.** `MpcExport.mpcheckerValues` substitutes the geocentre. Find_Orb and the ADES file refuse a blank code. MPChecker should too, and the copied line should not say 500.
- **Faint-mover measurements and the observation ticks last only while the window is open.** Tracks are reduced again from the report, so they return on the default six observations. A faint mover's sub-stacks do not: after the window closes there is nothing to export until they are measured again, and any changed ticks are gone. A measured light curve is lost the same way. Identifications (`LaterIdentifications`), designations and the submitted mark (`MpcRecords`), and night links (`NightLinks`) are already kept beside the report. The measurements need the same.
- **Closing the window leaves Find_Orb running.** `dispose` cancels Identify all, the light curve, the sub-stacks and the night reader. The Find_Orb fit is a separate worker and runs until it finishes or reaches its two-minute limit. Cancel it with the others.
