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
| [SP-27](#sp-27-auto-tunes-frame-pool-uses-the-engines-quality-measurement) | Auto-Tune's frame pool uses the engine's quality measurement | Medium | Small |

Keep dependency maintenance (SP-21 and SP-22) separate from refactorings, according to each item's timing.

Refactorings are intended to preserve behaviour. Changes to accepted inputs, viewer lifetime, settings policy or public contracts need explicit decisions under the [working rules](DEVELOPER.md#working-rules). Check implementation changes with the relevant unit and GUI mechanics tests. Items that touch detection or reports also need byte-identical deterministic `report-data.json`, `detection_config.json` and CSV content on the reference datasets, using the [normalization and comparison method](DEVELOPER.md#real-data-regression).

### SP-21. Upgrade nom-tam-fits after the dither fix

**Deferred until the upstream fix is released.** Upgrade nom-tam-fits from the [current version](DEVELOPER.md#dependencies) to 1.23.1 when available, after confirming the fix for the dither sequence at blank and zero pixels (#908).

With 1.23.0, dithered floating-point `.fz` frames still differ from cfitsio by about one quantization step in tiles that contain blank or zero pixels: 1 ADU after SpacePixels' 16-bit conversion, on a few hundred pixels of a frame. Integer frames are exact.

**Verification.** Coordinate the upgrade across the repositories that use nom-tam-fits, repeat the comparison with cfitsio (through astropy), and run the reference-data regression in DEVELOPER.md. Report any changed pixels and detection results before committing.

### SP-22. Upgrade Gson to 2.15.0

**Deferred to the next dependency update.** Upgrade Gson from the [current 2.14.0](DEVELOPER.md#dependencies) to 2.15.0 in all three repositories, reviewing its change of the default `Map` type to `LinkedHashMap`.

**Verification.** Round-trip saved settings files and check that their values and compatibility are preserved; run the relevant tests and reference-data comparisons from DEVELOPER.md.

### SP-27. Auto-Tune's frame pool uses the engine's quality measurement

**Problem (2026-11-14).** `AutoTuneCandidatePoolBuilder.scoreFrames` reads every frame and calls `FrameQualityAnalyzer.evaluateFrame` itself, over each frame's own area, to rank frames by noise × star size. Standalone quality control measures the same two values through the engine (`JTransientEngine.evaluateFrameQuality`), over the session's area and without the excluded areas, and also says which frames detection would reject. The pool builder does not know those verdicts, so a frame that detection will reject can be tuned on.

**Proposal.** Rank the frames from the engine's quality result (`QualityControlService`), the same loader-based measurement the Inspect step uses. Keep the 40 % best, 40 % median, 20 % spread rule. Belongs with [JT-07](../JTransient/DESIGN-IMPROVEMENTS.md#jt-07-one-front-end-for-frame-selection-and-one-veto-rule).

**Decision (made 2026-11-14).** Frames that quality control rejects leave the pool: Auto-Tune follows detection's rules (see JT-07). This changes tuned settings on sessions with rejected frames or more frames than the pool limit, and is shown on real data.

**Stays separate.** Preview Frame's single-frame detection (`DetectionSequenceFrame.read`) calls the engine's extractor directly with the frame's own area. It answers "what does this one frame contain" at once, without a session-wide pass, and uses the same extractor.

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
