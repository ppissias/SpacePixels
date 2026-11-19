# Completed Design Improvements Archive

Historic record of completed items moved out of [DESIGN-IMPROVEMENTS.md](../../DESIGN-IMPROVEMENTS.md). Earlier October 2026 completed items remain in git history (`git show 0490239^:docs/archive/design-improvements-completed-2026-10.md`).

---

## SP-26. Review of the sky-catalogue fallback services (Items 1–5, 7–11)

**Completed (2026-11-11).**

- **Items 1–5:** Truncated cone search `OVERFLOW` handled as a partial step; server-side column selection and `V < 2` filtering on mirrors; tangent-plane polygon containment near celestial poles (`SkyField`); failed copies recorded in fetch summary without flagging a complete catalogue incomplete; unresponsive copies skipped for later questions in the same attempt.
- **Items 7–10:** Cleaned planned-step bookkeeping at front of `pending`; standardized service names (`Gaia via VizieR`); exact HTTP status reporting and parse failure cause logging; updated manual documentation for fallback depth rows and query behaviour.
- **Item 11:** Retained observing site in saved JPL addresses for reproducibility.

---

## SP-27. Asteroids & Comets window review (Items 2–6)

**Completed (2026-11-19).**

- **A designation drops the other nights without saying so:** Updated `AsteroidsCometsWindow` so the object table's *Observations* column explicitly distinguishes linked nights included in the ADES file (`· +N nights`) from linked nights omitted due to an MPC designation, known name, *This night only*, or submitted status (`· N linked nights (not in file)`), and Export Ready notes state `(this night only; N linked nights are not in this file)`.
- **A failed station check forgets an earlier disagreement:** Tracked `checkedStationCode` in `AsteroidsCometsWindow` so a failed JPL Horizons lookup keeps an earlier disagreement blocking export for the same observatory code until a subsequent check agrees (or the code changes). Export stays allowed when never checked, with an explicit note in the Export Ready area (Option A).
- **MPChecker is given code 500 when no observatory code is set:** `MpcExport.mpcheckerValues` now rejects blank or malformed observatory codes instead of silently substituting `500`. Clicking *Check with MPChecker* with no configured code prompts the user with an explanation of `500` (geocentric) and options to either use `500` explicitly or enter a 3- or 4-character MPC observatory code.
- **Faint-mover measurements, observation ticks, and light curves last only while the window is open:** Added `mpc.SavedMeasurements` (`mpc_measurements.json` written atomically beside the report) so faint-mover sub-stacks, changed observation ticks, and measured light curves are restored when reopening the window and picked up across linked nights.
- **Closing the window leaves Find_Orb running:** Tracked `findOrbWorker` (and `stationWorker`) in `AsteroidsCometsWindow` and cancelled them with interruption in `dispose()`, terminating the external Find_Orb process cleanly.
