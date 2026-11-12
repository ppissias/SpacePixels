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
| [SP-25](#sp-25-re-choose-the-reference-datasets) | Re-choose the reference datasets | Medium | Investigation first |
| [SP-26](#sp-26-review-of-the-sky-catalogue-fallback-services) | Unify sky-catalogue fallback chains (Item 6) | Medium | Small |

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

### SP-25. Re-choose the reference datasets

**Maintainer request (2026-11-08); investigate after the archive batch.** The [reference datasets](DEVELOPER.md#real-data-regression) grew in two batches: the original seven and the variable-star batch. Later test folders, such as the Integral spacecraft challenge and the two sessions used for the faint-mover work, serve individual investigations but are not on the list. The maintainer is running all 111 datasets of their own archive through a batch, to see them all before choosing again.

**Scope.**
- **Inventory every candidate:** the current test-data folder, and the archive batch's summary table and reports. For each, record the camera type, frame count and size, run time and memory, what it exercises, known objects or events (named asteroids, comets, satellites, variables), and its current results, including false candidates and failures.
- **Cover the conditions:**
  - good and poor data, calibrated and uncalibrated;
  - mono and colour sources;
  - tracked and untracked sessions (drift, field rotation);
  - gradients and extended objects such as galaxies and nebulae;
  - crowded and sparse fields, few and many frames;
  - missing timestamps and registration problems.
- **Keep the cases the archive batch exposed:**
  - dense false tracks around a galaxy and along a gradient ([PT-10](../JTransient/FEATURE-REQUESTS.md#pt-10-minimum-fill-for-timed-point-tracks), [XC-3](../JTransient/FEATURE-REQUESTS.md#xc-3-local-background-and-noise-for-frame-extraction));
  - 12-24 minutes of track linking in large DSLR sequences (accelerated by JT-06);
  - sequences where the auto-tuner returned no configuration;
  - star tails in a run of frames ([SM-F8](../JTransient/FEATURE-REQUESTS.md#sm-f8-frame-check-for-runs-of-leaky-frames)).
- **Arrange them in tiers:**
  - a quick release smoke set (`realDataSmokeTest`);
  - the full regression, within a practical time and memory budget (today about 4 minutes for 18 datasets run one at a time);
  - special-purpose sets (variable stars, faint movers, performance), run when a change touches them.
- **Record what to expect from each dataset** (main findings and known problems), so a regression can be judged without working it out again.

**Deliverable before changing anything.** A candidate table with the proposed set and the reasons, for the maintainer to choose from. Then update the list in [DEVELOPER.md](DEVELOPER.md#real-data-regression), which JTransient's DEVELOPER.md links to, and the smoke-test selection, and the local paths in `LOCAL-ENVIRONMENT.txt` (not tracked). Keep observing-site details out of tracked documents and published material; the archive's FITS headers and reports can contain them. The evaluations already under way (SM-F8, PT-10, XC-3) keep the current set as their baseline; rerun them on the new set only if agreed.

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
