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
| [SP-26](#sp-26-review-of-the-sky-catalogue-fallback-services) | Review findings for the sky-catalogue fallback services | Medium | Small to medium |

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

**Code review (2026-11-11) of the uncommitted fallback work** in `SkyCatalogueClient`, `SkyField`, `FetchStep`, `SkyCatalogue` and `CatalogueComparisonWriter`. That work adds the Harvard and IDIA copies of SIMBAD, ARI Heidelberg for Gaia (stars and depth patches), and the Harvard and IDIA VizieR cone searches for VSX. The catalogue unit tests pass. Live probes on 2026-11-11 found that ARI Heidelberg, both SIMBAD mirrors and both VSX cone searches accept the queries as written, with the expected CSV headers and VOTable columns. Strasbourg SIMBAD was answering HTTP 404 at the time, so the fallback is already in use. The findings below are proposals. Items 1-4 change behaviour and need a decision.

**Correctness**

1. **A cone search that hits its row limit is treated as complete.** `parseVotable` ignores `<INFO name="QUERY_STATUS" value="OVERFLOW">`. A 2.5° cone toward the Galactic centre returned a 62 MB answer cut off at 200,000 rows. The step still reads SUCCEEDED, and variables near the frame edge are lost without a warning. Treat OVERFLOW as a partial answer: add a problem sentence and an outcome such as "200,000 rows (truncated)". The same check could be added to the TAP paths, which also stop at `MAXREC` without saying so.
2. **The cone search downloads far more than it keeps.** It returns every VSX row in the covering circle, whatever its `V` flag or magnitude, and the filtering happens on the client. On both mirrors, `viz-bin/votable?-source=B/vsx/vsx&-c=...&-c.rd=...&V=<2&-out=OID,Name,...,n_OID` filters and selects columns on the server: 6,369 rows instead of 22,577 for a 0.5° cone. It also returns `n_OID`, which the cone-search output leaves out. Without it, `bibliographyAvailable` is false for every variable from a mirror. Keep the `max IS NULL OR max <= limit` test on the client unless a server constraint is shown to keep null magnitudes.
3. **`SkyField.containsSky` fails near the celestial poles.** It tests the polygon on the (RA, Dec) plane. When a field contains the pole, the corners span 360° of RA, and stars on the far side of the pole fall outside the polygon. At high declination the frame edges also differ from the great-circle edges that the TAP `CONTAINS(..., POLYGON)` uses. The mirror path then keeps a different set of variables from the VizieR path. Test the point on the tangent plane at the field centre (`SkyProjection`), where great circles are straight lines, and add pole and high-declination tests.
4. **A successful fallback still marks the catalogue incomplete.** Every failed copy adds a sentence to `SkyCatalogue#problems`. That list drives the ⚠ status, the controller's "(incomplete)" message and the tooltip. With Strasbourg SIMBAD down, a catalogue that Harvard filled completely shows four problem sentences. Keep failed copies in `fetchSteps`, which the fetch summary shows. Add to `problems` only when the data are missing: every copy failed, depth patches are still missing, or the result was truncated.

**Time spent on a service that does not answer**

5. **A service that failed is asked again for each later question.** Each SIMBAD question starts at Strasbourg again, and the depth patches start at VizieR even after VizieR failed on the stars. A CDS outage that hangs requests, rather than refusing them, therefore costs about 4 × 60 s on SIMBAD, 60 s on VSX, 150 s on the stars and 4 × 150 s on the depth patches before the mirrors are asked: about 17 minutes. Inside `depthFrom`, every remaining patch is also sent to a copy that has just timed out. Proposal: within one `Attempt`, remember the copies that timed out or could not be reached, and skip them for later questions with a NOT_ASKED step ("skipped: did not answer earlier"). Keep asking a copy again after a query error, such as an ADQL error or a full queue.

**Readability and design** (refactoring; no behaviour change)

6. **The fallback chains are written three ways.** SIMBAD uses a loop over parallel arrays (`endpoints`, `services`, `places`). VSX uses three try blocks one after another. The stars use try/catch nested three deep, with a `String[] source` out-parameter and two `Runnable` progress hooks. Following the "few patterns applied consistently" principle, use one small description of a copy (service name, source name, endpoint, query and column mapping) and one loop. That would remove the nesting, the out-parameter and the duplicated `depthQuery`/`depthArchiveQuery` box code.
7. **The planned-step bookkeeping is fragile.** `askSimbad` takes `nextService` and `nextQuestion` only so that it can insert a fallback step ahead of them. `fetchDepth` uses a `new Planned("unused", "unused")` sentinel. `pending` is already in asking order, and the question just recorded has been removed from it. Inserting the fallback at the front of `pending` does the same with no anchors. The parameters and the sentinel can then go.
8. **The service names are inconsistent.** The VizieR depth step is named "Gaia", but the star step is "Gaia via VizieR" and the depth fallbacks are "Gaia via the ESA archive" and "Gaia via ARI Heidelberg". The fetch summary shows these names side by side.
9. **Small points in `parseVotable` and `CatalogueComparisonWriter`.** `start.contains("SERVICE ERROR")` searches the whole page, despite the variable's name. `errorText(503, xml)` reports HTTP 503 when the real status was 200. `catch (Exception)` drops the cause; send it to `DebugOutput`. The three-argument `CatalogueComparisonWriter.match` overload is called only by tests; the tests can pass `null` for the source.

**Documentation and decisions**

10. **The manual is out of date.** MANUAL.md still says "The four faint patches are one row". Each Gaia copy now has its own depth row. The privacy sentence "An earlier service is skipped only when it does not answer" also reads as if a copy that failed were skipped later, which is not the case today (see item 5).
11. **Saving the observing site needs an explicit decision.** The same change removes `FetchStep.withoutSite`. Saved JPL addresses in the session folder now include the observer's latitude, longitude and altitude, and the manual says so. Before, they were stripped. This is a privacy change to saved files, which users may share. It is unrelated to the catalogue fallbacks and should be confirmed and committed on its own under the [working rules](DEVELOPER.md#working-rules).

**Verification.** Add recorded tests for: a real VizieR cone-search page, including OVERFLOW; a field that contains the pole; skipping a copy that timed out (with a counting transport that checks how often each copy is asked); and the `problems` contents after a successful fallback. Run one live fetch with a blocked CDS endpoint and compare the catalogue and the fetch-summary rows with a normal fetch.

**Assessment (2026-11-11), against the current code.** The descriptions of the code match it. Items 1–5 and 8–10 should be done. Items 6 and 7 should follow them, not block them. Item 11 is already decided.

**Implemented (2026-11-11).** Items 1–5, 7, 8, 9 and 10 are in the code and the manual. Item 6, one shared description of a copy and one loop, remains.

1. **Accepted.** `parseVotable` treats only `QUERY_STATUS` `ERROR` as a failure, so `OVERFLOW` stays a success. A truncated cone search must be a failed or partial step, with a problem sentence, and the outcome must say the answer was cut off. Apply the same rule to a TAP answer that reports `OVERFLOW`. Do not invent a warning when the service simply returns fewer rows than `MAXREC` and does not say the answer was cut off.
2. **Accepted.** The cone search should ask the mirror to select the columns and to keep `V` below 2, including `n_OID`, so a mirror result can record bibliography the way a VizieR result does. Leave the magnitude test on the client until a server constraint is shown to keep rows whose `max` is empty. The row counts in the finding were not remeasured here.
3. **Accepted, with a different home for the test.** The polygon test is done in RA and Dec, so a field that contains a pole, or lies close to one, does not match the great-circle outline sent to TAP. `SkyProjection` places catalogue objects on a frame through that frame's WCS. It is the wrong tool for a field that has only a centre and corners. Test the point in the tangent plane of the field centre, next to `SkyField`, and add a pole case and a high-declination case.
4. **Accepted.** `problems` is what turns on the warning. A copy that failed belongs in the fetch summary. It belongs in `problems` only when the catalogue is actually short: no copy answered, a depth patch is still missing, or an answer was cut off.
5. **Accepted.** The 17 minutes is the cost when a copy hangs until the timeout. A fast HTTP 404, which is what Strasbourg SIMBAD returned on 2026-11-11, does not cost that, but asking it again for every question is still pointless. Within one fetch, after a timeout, a connection failure, or HTTP 404, do not ask that copy again. Record `Not asked` and the reason. Ask it again after a query error, including the ESA archive's full-queue HTTP 503, because that can clear between questions.
6. **Accepted as a later refactor.** One description of a copy, and one loop, should replace the three chains. Do this after items 1–5, so the behaviour tests land on the code the tests describe.
7. **Accepted with item 6.** Insert the next fallback at the front of `pending`. The `nextService` arguments and the `"unused"` sentinel go away.
8. **Accepted.** Name the VizieR depth row `Gaia via VizieR`, like the star row and the two depth fallbacks.
9. **Accepted.** Small. Report the HTTP status the page actually had. Keep the parse failure's cause through `DebugOutput`. The three-argument `match` can take `null` for an old catalogue; the tests do not need a second method.
10. **Accepted.** The manual still says the four faint patches are one row. They are one row for each copy that was asked. The sentence "An earlier service is skipped only when it does not answer" describes item 5, not the code. Rewrite it when item 5 is done, so the manual does not say both things.
11. **Already decided.** The saved JPL address keeps the observing site. That was chosen so the summary can reopen the request that was sent. Published images, reports and screenshots still leave the site out. `DEVELOPER.md` already says so, including the fetch summary's Open link and `solar_system.json`. No further decision is waiting. It does not need its own commit apart from the fallback work.
