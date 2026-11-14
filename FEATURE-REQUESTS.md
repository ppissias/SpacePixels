# Feature requests

New or changed functionality for SpacePixels: what the application does, as opposed to how its code is organised. Design and refactoring work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Detection and photometry algorithm proposals belong to JTransient's [feature requests](../JTransient/FEATURE-REQUESTS.md), and solver features to JPlateSolve's [feature requests](../JPlateSolve/FEATURE-REQUESTS.md).

A request is a proposal until the maintainer decides on it. A settled choice is marked **Decision (made)** and kept in the maintainer's words, followed by what that choice resolves. Points that are still open stay listed as decision points. Remove requests when implemented or closed; describe implemented behaviour in [MANUAL.md](MANUAL.md) and the developer documentation, and use git history for the proposal and its reviews.

| ID | Request | Status |
|---|---|---|
| [F04](#f04-mcp-agent-services) | MCP agent services over HTTP | Agreed: HTTP. The services table awaits agreement. |
| [F09](#f09-consolidated-super-report-for-iterative-runs) | Consolidated "super report" for iterative runs | Retained for future work; the matching policy is to be proposed. |
| [F23](#f23-fixed-camera-and-all-sky-sequence-analysis) | Fixed-camera and all-sky sequence analysis | Analysed 2026-11-02: streaming design proposed; needs camera details and sample data |
| [F24](#f24-calibrated-magnitudes-for-variable-stars) | Calibrated magnitudes for variable stars | Reviewed 2026-11-03; estimated-G scope proposed, implementation deferred |
| [F32](#f32-excluded-areas-for-runs-on-converted-copies) | Excluded areas for runs on converted copies | Noted 2026-11-14; to be understood first, kept for later |
| [F33](#f33-mpc-observation-export-and-find_orb) | MPC observation export and Find_Orb | Requested 2026-11-14; formats and service investigated, implementation proposed |
| [F34](#f34-moving-object-light-curves-and-rotation-periods) | Moving-object light curves and rotation periods | Requested 2026-11-14; measurement and presentation proposed, linked engine work required |

Registration quality is owned by JTransient: [registration quality](../JTransient/FEATURE-REQUESTS.md#pt-5-registration-quality-and-shared-jitter-tolerances). ST-7 is closed: the maintainer confirmed that the [existing single faint-streak retention](../JTransient/ALGORITHM.md#135-faint-streak-search), using the shared elongation/size and shape checks, meets the request; no separate threshold is needed. The requested [plain-English scientific photometry overview](../JTransient/ALGORITHM.md#photometry-in-plain-english) is now in the algorithm reference. Application settings, reports and callers must follow any agreed engine changes in the same work.

<a id="f01-slow-mover-and-per-frame-tuning-relation"></a>

**Slow and faint movers.** Digital tracking (JTransient SM-F2 / SpacePixels F25) and the agreed review/report controls are delivered; see the [manual](MANUAL.md#faint-movers-experimental) and [engine algorithm](../JTransient/ALGORITHM.md#faint-mover-search). Maximum-stack position/time review and track correspondences are also implemented. The investigation round is closed with current scientific filters and defaults retained. F01's separate tuning experiment is retired: stack and single-frame noise thresholds remain distinct, and Auto-Tune does not tune maximum-stack thresholds.

The faint and slow-mover investigation round is closed; no further experiment or integration is scheduled. Any future engine extension must update application settings, result provenance and review together. The [existing research collection](docs/research/README.md) preserves the completed evidence and delivery history.

The engine's [XC-3 background proposal](../JTransient/FEATURE-REQUESTS.md#xc-3-local-background-and-noise-for-frame-extraction) remains open and parked at the maintainer's request. Its current decision and proposed local-sky-only next step are kept there; the verified restart bundle is recorded in the ignored machine environment notes. No extraction change is selected.

<!-- Stable targets for retired/implemented request IDs in historical documents. -->
<a id="f15-report-themes-and-custom-css"></a>
<a id="f29-preview-frame-and-blink-redesign"></a>

## F04. MCP agent services

AI agents operate SpacePixels through an MCP server, beginning with what the [Java API](API.md) does. Use the headless application services, with the public API as the starting boundary; never drive `ApplicationWindow`.

**Decision (made):** "I propose to have an http interface. But first we should do a table with the services that we will provide and each service description. After we agree on the services we will proceed with an implementation."

### Proposed services (for agreement)

The server is an MCP server on the Streamable HTTP transport, so an agent connects to one URL and calls these services as MCP tools. Report files are served over the same HTTP server, so a client on another machine can read them. Nothing below is implemented yet.

| Service | What it does | Main inputs | Returns | First version? |
|---|---|---|---|---|
| `server_info` | Describes the server: SpacePixels, JTransient and JPlateSolve versions, the folders it may read, the memory limit, and whether a job is running. | none | versions, allowed folders, limits, busy/idle | Yes |
| `get_settings_schema` | Lists every detection setting an agent may change: field ID, title, unit, range, default, and basic or expert. It comes from the same definitions the settings panel uses, and hidden settings are not listed. | none | one entry per setting | Yes |
| `get_default_profile` | Returns the default detection profile, as saved in a profile file. | none | profile JSON | Yes |
| `inspect_dataset` | Checks a folder without processing it: frame count, size, mono or colour, FITS or XISF, timing, exposure groups, and whether it is ready or needs preparation. Same checks as the import. | folder | readiness, the problems found, and the exposure groups | Yes |
| `start_analysis` | Starts a full analysis of a folder, the same as the Java API's `run`: optional preparation, optional Auto-Tune, detection, then the report. Only one analysis runs at a time; a second request is refused while one runs. | folder, profile or setting overrides, preparation mode, Auto-Tune profile, report on/off | job ID | Yes |
| `start_auto_tune` | Runs only Auto-Tune on a folder and returns the tuned settings. Nothing is detected. | folder, profile, Auto-Tune profile, max candidate frames | job ID | Yes |
| `get_job_status` | Reports a job's state (queued, running, finished, failed or cancelled), its stage and percentage, the last progress message, and the elapsed time. | job ID | status | Yes |
| `cancel_job` | Asks a running job to stop. The job reports "cancelled" once the work has actually stopped. | job ID | accepted or not running | Yes |
| `get_analysis_result` | Summarises a finished analysis: the effective settings, Auto-Tune choice, rejected frames, counts per category, and the findings from `report-data.json` (ID, category, position, sky position, frames, variable-star tier). | job ID | structured summary | Yes |
| `list_artifacts` / artifact URLs | Lists a finished job's files (HTML report, `report-data.json`, CSVs, images) with an HTTP URL for each. The agent or the user opens the report from that URL. | job ID | file list with URLs | Yes |
| `extract_frame_sources` | Runs source extraction on one frame with given sigma, grow sigma and minimum pixels (or a profile), and returns the detections and an annotated preview. This is extraction on that frame alone, before the stationary-star veto. | folder, frame, settings | detections, preview image URL | Later |
| `inspect_frame_transients` | Returns the transients left on one frame after the whole dataset's frame selection, median reference, star mask and filtering, with an overlay. Same as Manual Transient Inspection. | folder, frame, settings | transients, overlay image URL | Later |

**Limits that apply to every service.** The server listens on localhost unless configured otherwise, and needs a token when it listens on a network address. It reads only inside configured root folders, and writes only report folders under the dataset (as the Java API does). Results and artifacts leave out observing-site coordinates and absolute paths. Settings files in the user's home folder are never changed.

**Still to decide after this table.** Whether the first version needs `start_auto_tune` as its own service (it could stay an option of `start_analysis`); how long finished jobs and their artifact URLs are kept; and how the server is started (its own launcher, or an option of the desktop application).

**Acceptance.** An agent submits a folder and a profile, follows the run, and retrieves the same findings and report as an equivalent Java API call. The single-frame services, when added, match Preview Frame and Manual Transient Inspection on the same frame and settings.

## F09. Consolidated super report for iterative runs

Basic iterative detection is implemented: independent runs over growing subsets of a directory, with an index of the per-pass reports. This request adds one combined report.

**Decision (made):** "Agreed. please also use a similar page layout for the super report (colors, background, ...) as the individual reports."

**Maintainer priority (2026-11-02).** Keep this request: the combined report will be useful at a later stage. The matching and evidence-selection policy still needs agreement before implementation.

**Proposal.** Read the completed pass reports and their saved run metadata, recognise findings that are the same physical object or event, and present each once with the best information from the passes that saw it. The individual reports, and their own identities, stay available.

- **Associate findings across passes** using input identities, timestamps, sky and pixel positions, motion and category-specific evidence. Report-local IDs (T, ST, A, V) are not enough. Handle split and merged tracks, missing WCS, ambiguous matches and incompatible datasets explicitly, and show disagreements instead of forcing a merge.
- **Combine evidence with provenance.** A finding may have its strongest detection in one pass, its longest track in another and its best astrometry, photometry or pictures in a third. Say how each measurement is chosen or combined, and link it to its pass and report anchor. Passes that reuse the same frames are correlated evidence, not independent confirmation.
- **Present a useful combined view:** distinct findings, the passes that contributed and their frame counts, coverage and limitations, the best pictures, and links to every source report. Combined counts are shown separately from per-pass counts.
- **Persist enough evidence.** `run-data.json` and the per-pass input manifests already record settings, outcomes, file names, dimensions, exposure and timestamps. `report-data.json` is a findings summary, not a per-frame record. Decide, and version, what per-frame positions, associations, quality flags, WCS and catalogue provenance or light-curve data must be added before promising consolidation of every category.
- **Work from saved data,** with bounded memory, including after a cancelled or failed pass.

**Next step.** Propose the matching and evidence-selection policy for agreement before implementing.

**Acceptance.** One finding seen in several passes, distinct nearby objects, split tracks, conflicting measurements, changed or missing inputs, different fields with matching file names, missing WCS and older summary-only reports. Chosen measurements stay traceable, ambiguous associations stay visible, unique counts are justified, and the individual reports are unchanged.

## F23. Fixed-camera and all-sky sequence analysis

**Request (2026-11-02).** Analyse thousands of short exposures from a stationary wide-field or all-sky camera across a night, without requiring aligned input, to find satellite/meteor streaks and optical flashes. Develop ideas and a preliminary design first.

**Application and engine ownership.** SpacePixels owns chronological file inventory, bounded reading, batching, progress, restart/cancellation and presentation; JTransient owns detection and calibration of the science. Investigate a separate input/processing mode with an explicit contract. The existing aligned-frame pipeline must continue to receive aligned frames.

**Questions to investigate.** Compare fixed batches of 50 or 100 frames with time-based windows and adaptive segmentation driven by sky brightness, clouds and stellar motion. Assess overlaps and state needed to avoid losing or double-counting events at boundaries. Explore robust temporal backgrounds in camera coordinates, moving-star suppression, sensor defects, lens distortion and horizon masks, intermittent clouds, per-window tuning and cross-window event association. Distinguish single-frame meteor evidence, multi-frame satellite evidence and point-like flashes; do not assume every isolated bright event is astronomical.

**Cost and validation.** Estimate throughput, I/O and memory for realistic nights before selecting batch sizes or an Auto-Tune strategy. Require representative camera sequences and labelled events/negative controls, with recovery, false alarms per unit observing time, boundary behavior and changing-condition tests. Record unresolved acquisition metadata, colour/raw handling and astrometric-calibration needs. A full night should be processable with bounded memory and traceable settings/evidence per interval.

### F23 analysis (2026-11-02)

**Star motion rules out batches of a fixed number of frames.** In a fixed camera the stars drift at about 15″/s × cos δ. With a star width (FWHM) of 2 px, or 2.5 px for the 50 mm lens:

| Setup | Scale | Drift of one FWHM at δ = 0 | Star trail in a 10 s exposure | Drift over 50 × 10 s |
|---|---|---|---|---|
| All-sky fisheye, 1.55 mm lens, 2.4 µm pixels | 319″/px | 42 s | 0.5 px | 24 px |
| 14 mm lens, 5.9 µm pixels | 87″/px | 12 s | 1.7 px | 87 px |
| 50 mm lens, 4.3 µm pixels | 18″/px | 3 s | 8.5 px | 424 px |

A median of a batch keeps the stars only while they move less than about half a FWHM: about two 10 s frames for the all-sky camera. Otherwise the stars drop out of the median, escape the star mask and become sidereal-rate "tracks". Warping the frames onto a common sky frame would allow batches, but then the horizon, trees and hot pixels move instead and must be masked in camera coordinates first. Fisheye distortion would also have to be modelled to about 0.1–0.2 px over tens to hundreds of pixels.

### F23 proposed design

- **Rolling reference.** Each frame's reference comes from its neighbours within a time window (about ±2–5 minutes, not a frame count). The sky part is motion-compensated; static scenery uses a plain temporal median.
- **Star-motion model from the data.** Bright stars tracked over 1–2 minutes give the motion field: a rotation about the image of the celestial pole for a rectilinear lens, a smooth field for a fisheye. The model also predicts each pixel's star-trail direction and length, which rejects star residuals and trailed stars. Geostationary satellites stay fixed in camera coordinates, like hot pixels, and need their own rule if they are wanted.
- **Segments by conditions.** A cheap first pass records each frame's sky level, noise, star count (a drop means cloud), FWHM and any exposure or gain change. It splits the night at twilight, clouds, moonrise and setting changes, and builds the hot-pixel map and the horizon mask. A reference never spans a segment boundary.
- **Boundaries.** A rolling window has no internal boundaries. At segment edges and on resume, open satellite chains are carried over and the window's frames are read again.
- **Detection.** On each difference image (frame minus its reference), reuse `SourceExtractor`, the streak classification and `StreakLinker`-style joining, in camera coordinates and only between neighbouring frames, because long paths curve.
- **Event classes.** No isolated bright event is assumed to be astronomical:
  - satellite: a streak that continues end to end across consecutive frames at a steady rate, possibly flaring;
  - meteor: a single-frame streak without continuation, varying in brightness;
  - aircraft: slow, with dotted lights blinking at about 1 Hz, often coloured;
  - flash: point-like and star-shaped in one frame, checked against hot pixels, cosmic rays (sharper than a star), star positions and glints on a satellite path.
- **Thresholds.** Set from the noise of the difference images, with false alarms per hour measured on negated difference images (the null test used for the faint-streak gate). The calibrated Auto-Tune works on aligned stacks and does not carry over.
- **Contract.** JTransient gets a new entry point that accepts frames one at a time in time order, says when each frame can be released, and returns events, a conditions timeline and timings. Its results must not depend on how the application reads the frames. SpacePixels does the chronological inventory (times, gaps, format checks), read-ahead, progress, cancel and resume (results saved per segment), and a night report. The aligned pipeline is unchanged.

**Cost estimate, to be confirmed by a prototype.** Extraction took 1.36 s per 61-megapixel frame on klangwolke-2, so about 0.15 s for a 6.4-megapixel all-sky frame. The rolling reference costs 0.2–1.5 s per frame depending on the method and is the main unknown, so 3,000 frames take about 15–90 minutes. A 16-bit 6.4-megapixel frame is about 13 MB, about 39 GB per night. Memory stays bounded whatever the length of the night: about 0.3 GB for 21 frames of 6.4 megapixels, about 1 GB at 24 megapixels, plus working buffers.

**Validation.** Use nights that are clear, partly cloudy and moonlit, with twilight edges and with heavy air traffic. Label events by manual review, by SatChecker once the camera is calibrated, and by a second camera when one is available. Inject synthetic streaks and flashes into real frames to measure recovery by brightness, length and rate. Use negated differences and a cloudy night as negative controls. Measure false alarms per clear hour, events across segment edges and resumes, throughput and peak memory.

### F23 open questions and next steps

Questions for the maintainer:

1. Camera and lens: all-sky fisheye or wide-field; the exposure, the cadence and the gap between frames.
2. Files: FITS, or JPEG/PNG from all-sky software (8-bit and stretched, which limits sensitivity); mono or colour; whether the capture software changes exposure or gain during the night.
3. Timestamps: exposure start or middle, and how the clock is synchronised (NTP, GPS). One second of error is up to 0.87° of motion for a satellite in low Earth orbit.
4. Sample data: one or two nights, or a few hundred frames with known events.
5. The first version's priority among satellites, meteors and flashes, and whether camera pixels and times are enough or sky coordinates are needed from the start.

Next steps:

1. A scratch spike: measure star motion and conditions, compare two or three reference methods on about 500 frames, and measure recovery of injected events, false alarms per hour, throughput and memory.
2. Write the input and output contract here and in JTransient, for agreement.
3. First version: detection in camera coordinates (satellites, meteors, flashes) in JTransient; chronological inventory, streaming reads, cancel and resume, and a night report with a timeline in SpacePixels.
4. Second version: an alt/az calibration per camera, which gives every frame a sky solution from its time; sky coordinates and SatChecker identification with the F21 comparison; meteor shower association; several cameras.

## F24. Calibrated magnitudes for variable stars

**Request (2026-11-02).** Use the magnitudes in a fetched sky catalogue to estimate calibrated magnitudes for variable stars alongside their instrumental magnitudes and relative light curves. Label the calibration's passband, uncertainty and limitations explicitly.

**Decision (made, 2026-11-03):** "Lets update the issue now and implement it a bit later". Record the review and revise the accuracy claims now; implementation is deferred. The first-version scope and unresolved policies below remain proposals.

**Review (2026-11-03).** The approach is feasible, and fitting on photometry's own ensemble means is the right starting point. The current code and saved data support an initial calibration experiment, but do not yet establish its accuracy. Before implementation, settle calibration-star selection, uncertainty estimation and acceptance limits. In particular, the diagnostic Gaia matcher is not yet a precision-calibration selector, and the scatter of stars used in the fit is not the complete uncertainty of a new target.

**What exists today.** Photometry reports each star's mean instrumental magnitude (main aperture, camera scale) and a differential light curve against that mean (`delta_mag`). A candidate card shows the matched star's catalogue values, Gaia DR3 G and BP−RP and the VSX magnitudes or amplitude, beside the measurements without converting them. Night & Equipment already matches the extracted stars with Gaia and fits a zero point per frame (Gaia G with one colour term in BP−RP for the session, plus a linearity slope), but only for the sky brightness and the linearity reading; the photometry does not use it.

The saved Gaia star model contains source identity when available, position, proper motion, G and BP−RP, but no photometric uncertainties or quality/variability flags. Night & Equipment's matcher uses a nearest match within 2.5 px and a neighbour veto within 8 px for stars less than 2.5 magnitudes fainter. It does not enforce one-to-one assignments. Those fixed distances and the roughly 10%-flux neighbour criterion do not establish calibration accuracy at the few-hundredths level; catalogue magnitude limits can also hide contaminating neighbours.

**Proposed first version.** Estimated Gaia G only, beside the existing instrumental values; differential light curves remain the default, with an optional calibrated view. Defer V/R transformations until the G calibration and its uncertainties have been validated. Withhold calibrated estimates for missing target colours or targets outside the validated colour or magnitude range. Preserve the existing differential measurements when calibration is unavailable.

**Proposed method.**

1. **Calibration stars.** Start with CONSTANT photometry stars with finite ensemble means, Gaia G and BP−RP, adequate usable measurements, and unsaturated, linear coverage supporting the main-aperture mean. Apply check B's linear range and relevant measurement flags, exclude known variables where available, and robustly reject outliers. CONSTANT means no detected variation during this session, not certified stability since Gaia's observations. Specify the usable-coverage rule instead of relying on "not mostly saturated".
2. **Associations and blends.** Reuse the demonstrated catalogue projection and matching mechanics, including proper motion at the observation epoch and the corrected plate solution, while adding unique assignments and ambiguity rejection. Assess contamination against the measured star width and actual apertures. Record match separations and reference source identities. Define how catalogue depth, missing quality fields and older caches limit calibration; do not silently treat unavailable quality information as a successful quality check.
3. **Fit and scale.** Robustly fit `G = meanMag + ZP + c × (BP−RP)` with the instrumental-magnitude coefficient fixed at 1. Use photometry's ensemble means, not Night & Equipment's fitted zero points: its measurements have a different flux scale. The means share the ensemble's transparency and spatial-plane reference conventions; describing them only as the mean at median transparency is incomplete. A session solution is appropriate only for a compatible passband and supported by residual checks. Diagnose the magnitude slope jointly with colour, and check magnitude/colour coverage and fit degeneracy. A non-unit slope can expose non-linearity or stretched input; it must not be used to rescale astrophysical variability amplitudes.
4. **Target values and apertures.** Estimate the mean as `meanMag + ZP + c × colour`, and anchor the differential curve by adding `delta_mag`. JTransient can select another aperture's differential curve while keeping the main aperture's mean magnitude. Respect and test that contract, including the main-reference validity, selected-aperture flags and error provenance. This gives a curve anchored to an estimated G mean; interpreting every point as G also assumes the target's colour correction remains applicable as it varies. Do not substitute a median colour with an invented uncertainty. A future fallback would need an explicit, validated colour prior and an assumption label.
5. **Quality and uncertainty.** Report accepted/rejected reference counts, colour and magnitude ranges, coefficients, the diagnostic slope, fit residual scatter and prediction errors on stars excluded from the fit. Check residual trends with brightness, colour, field position and observing conditions. Define an uncertainty estimate that accounts for target measurement error, coefficient/zero-point uncertainty, colour uncertainty and remaining transformation scatter without double-counting terms. Keep calibration uncertainty, including its common contribution to all curve points, separate from relative point precision; existing `mag_error` values are sky-noise lower bounds, not complete calibrated errors. Withhold values when coverage, fit conditioning, slope or validation errors fail the agreed limits, and state the reason.

**Where it would appear.** Estimated G beside the instrumental magnitude on candidate cards and tables, an optional calibrated light-curve axis, additive CSV columns for means, points and calibration quality, and a calibration note in the diagnostics. Retain the original values, column meanings, flags and magnitude-axis direction. With compatible VSX passbands, the catalogue comparison can show estimated observed extrema beside catalogue values, with the band, epoch and observed phase coverage stated. Historical extrema are context, not values that every session is expected to reach.

**Persistence.** Calculate from the admitted run's catalogue, astrometry and metadata snapshot. Save the calibration model/version, coefficients and uncertainties, reference-star identities, fit/validation ranges and residual summaries, acceptance/rejection reasons, filter information and catalogue provenance with the result. A saved report must remain interpretable offline without refetching the catalogue or using another session's live state. Define compatibility and unavailable-calibration behavior for older saved catalogues and reports.

**Limits to state in the report.**

- **Passband and accuracy.** Gaia G is broad; luminance, RGB, standard-filter and unfiltered observations have different responses. A fitted colour term may reduce the difference, but does not guarantee a standard-G measurement or a universal accuracy. Report the accuracy supported by validation for the relevant colour, magnitude and passband range. Published Gaia transformations have restricted colour intervals and population-dependent limitations; a few-hundredths accuracy claim is not established for these datasets.
- **Colour changes.** Gaia supplies a mean colour. Variable-star colour changes and colour-dependent extinction can affect the shape and amplitude of an anchored curve; a constant offset cannot correct them. The measured differential variation remains that of the observing passband.
- **Requirements and readiness.** A suitable plate solution, catalogue coverage and enough isolated, stable reference stars with adequate colour/magnitude coverage are required. Keep incompatible filters separate, and define handling of missing filter metadata. Internal photometric readiness does not certify catalogue-calibrated accuracy; calibration availability and diagnostics must remain separate from JTransient's readiness verdict.
- **Not automatically submission-ready.** These exports are estimates with the stated passband and uncertainty. AAVSO accepts appropriately labelled unfiltered as well as filtered observations; submission suitability requires the applicable calibration, comparison-star, metadata and quality checks. F24 alone does not establish that suitability.

**Ownership.** SpacePixels owns the catalogue and the plate solution, so the calibration belongs to the application. JTransient's instrumental means, differential curves, errors, flags, scores, candidates and readiness verdict remain unchanged. No engine algorithm change is proposed, but implementation and checks must follow the current ensemble-reference and aperture-selection contracts, including VS-12 changes.

**Decision points before implementation.**

1. Confirm the proposed estimated-G-only scope, additive presentation, default differential view and withholding of missing-colour/out-of-range estimates. V/R transformations and any colour-prior fallback remain later proposals.
2. Choose acceptance limits from a scratch calibration experiment: independent reference count, usable measurement coverage, colour/magnitude span, target validity range, matching/blend limits, slope departure, residual trends and held-out prediction error. Do not copy Night & Equipment's diagnostic tolerances as precision-photometry guarantees.
3. Decide which Gaia photometric uncertainties, quality/contamination and variability fields to fetch and persist, the treatment of absent fields in older caches, and the catalogue depth needed for blend checks.
4. Specify the uncertainty calculation and saved/exported fields, including the distinction between relative point precision and calibration uncertainty.
5. Define compatible filter/instrument cohorts and the policy for mixed or unknown `FILTER` metadata. One global solution must not pool incompatible passbands or observing configurations.

**Verification before and during implementation.**

- First run a scratch calibration experiment on existing klangwolke-2 and M81 Kumar exports and saved catalogues, without rerunning detection or generating new reports, to establish feasible selection and acceptance limits. The read-only review confirmed both sequences use `L`, and their saved catalogues stop at G = 15 and lack photometric quality/error fields. This inventory establishes data availability, not achievable calibration accuracy.
- Hold out reference stars independently of fit/clipping, inspect prediction errors and residual trends by magnitude, colour and field position, and use independent standard photometry where available to test accuracy beyond internal agreement with Gaia. Compare Night & Equipment's colour/linearity diagnostics as a consistency check, without copying its zero points or assuming the two measurement scales coincide.
- Use synthetic known zero points, colour terms, noise and non-linear inputs to check coefficient recovery, uncertainty behavior and rejection. Cover false/duplicate matches, blends, catalogue truncation, known variables, saturation, inadequate valid coverage, missing/extreme colours, narrow or degenerate reference ranges and too few references.
- Check m31-b/g/red or other separate filtered sequences as well as mixed/unknown filters, absent catalogue/WCS and missing quality fields. Withheld calibration must have an explicit reason while preserving the existing photometry.
- Verify the selected-aperture/main-mean convention and unchanged differential amplitudes, errors and flags. For VSX-matched variables, respect passband and epoch/phase coverage; Gaia means and historical VSX ranges are not exact predictions for the observing night.
- Check offline reopening, older caches/reports, additive CSV/schema behavior, saved provenance and uncertainty labels. Detection outputs, scores, candidates and engine readiness must be unchanged.

**Scientific references used in the review.** [Gaia DR3 photometric relationships](https://gea.esac.esa.int/archive/documentation/GDR3/Data_processing/chap_cu5pho/cu5pho_sec_photSystem/cu5pho_ssec_photRelations.html) describe the validity ranges and limitations of passband transformations. [AAVSO's guide to using its data](https://archive.aavso.org/index.php/quickstart-guide-using-aavso-data) distinguishes filtered and unfiltered observations and calibration limitations.

## F32. Excluded areas for runs on converted copies

**Note (2026-11-14), kept for later.** A headless or iterative run that converts its input first (for example 32-bit frames) works on a copy in a new subfolder and reads `excluded_areas.json` beside that copy, so areas saved in the original folder are not used. The catalogue and plate-correction files behave the same way. Areas are drawn in the window, on a session that is already 16-bit, so it is not yet clear how a user would reach this case; understand that before proposing a change.

## F33. MPC observation export and Find_Orb

**Request (2026-11-14).** For a reviewed moving-object track (T) or faint mover (FM), generate astrometric observations for the Minor Planet Center (MPC), use the Find_Orb service to estimate an orbit, and present both the observations and orbit in the desktop application and report. Support known objects and unidentified candidates; a possible catalogue association is not a confirmed identity. This records the feature and proposed design, not an implementation decision.

**Formats investigated.** MPC prefers the Astrometric Data Exchange Standard (ADES), available as XML or pipe-separated values (PSV), and also accepts legacy MPC1992/obs80. Propose ADES PSV as the initial editable/downloadable format, with an obs80 compatibility export from the same observation model. ADES preserves uncertainty and reduction metadata that obs80 cannot. Validate against the submission schema and current accepted field values: general ADES validity alone does not mean a file is acceptable for MPC submission. [MPC ADES documentation](https://docs.minorplanetcenter.net/mpc-ops-docs/observations/ades-format/)

### Required observations and provenance

| Information | Proposed source and checks |
|---|---|
| Object identity | Valid `permID`/`provID` for a reviewed known-object identity, or a stable, format-valid observer tracklet identifier (`trkSub`) for an unidentified target. Preserve the mapping to report-local T/FM IDs. Do not invent an official designation or automatically claim discovery. |
| Observation time | `obsTime` in UTC at the measured exposure midpoint, with source frame, exposure length and clock provenance. Record timing uncertainty where supported. A report's trial clock offset is diagnostic until explicitly reviewed. |
| Sky position | Measured topocentric RA/Dec in the required ICRF/J2000 reference system, in decimal degrees for ADES. Use the WCS and any validated correction appropriate to the registered image and observation epoch; retain solver, catalogue and registration provenance. Pointing hints, apparent-of-date coordinates and predicted ephemerides are insufficient. |
| Observing location | The actual acquisition telescope's station code (`stn`), with its resolved location, or an explicitly supported location representation. Check latitude, longitude, altitude, units and height datum where needed. Archive/remote-telescope data must use their acquisition site. Existing header/code/default resolution can supply candidates, but conflicting codes and coordinates need review; never silently substitute geocentre code 500 or an assumed height as verified information. |
| Reduction metadata | Detector mode (`mode`) and astrometric catalogue (`astCat`) actually used in the reduction, using accepted values. A subsequently fetched display catalogue does not establish the astrometric reference catalogue. Add `rmsRA`, `rmsDec`, `rmsTime`, supported covariance and exposure (`exp`) when justified, following ADES units and angular conventions. |
| Submission context | Observatory, submitter, measurer and telescope metadata required by the submission schema, plus observers and software provenance where applicable. Capture missing information in an export form. Keep contact-routing details outside free-text ADES fields as MPC specifies. |
| Optional photometry | Measured calibrated magnitude, actual passband and uncertainty when available. Omit when unavailable: detection flux, relative light-curve magnitude and JPL-predicted magnitude are not measured calibrated magnitudes. |

The [ADES submission schema](https://github.com/IAU-ADES/ADES-Master/blob/master/xsd/submit.xsd) defines the optical record and context fields. The [obs80 specification](https://docs.minorplanetcenter.net/mpc-ops-docs/observations/mpc1992-format/) defines fixed columns, packed designations, fractional UTC dates, sexagesimal RA/Dec, optional magnitude/band and station code. Use exact 80-character observation records without tabs; preserve appropriate precision through RA wrap, signed declination and date rollover. Clearly disclose metadata omitted by this export.

**Measurement readiness.** Ordinary tracks have measured pixel centroids, but still require checked timing, astrometry and uncertainty. FM `positionAt(...)` and the report's sampled FM positions evaluate one fitted path; they are not independent astrometric observations. Obtain independently fitted centroids from usable exposures, or investigate independently measured, non-overlapping time groups when individual exposures are too faint, with effective times, membership and shared motion-model uncertainty recorded. Validate grouping before enabling submission export. Never turn a fitted path or score-map samples into many apparently independent measurements. Flag blends, saturation, exclusions, padding and ambiguous frame identities. Review weak measurements instead of silently exporting every searched frame.

MPC recommends at least three observations per object per night when possible, generally a few good positions spread over an hour and observations on nearby nights. The UI should report counts, nights and time span, detect duplicates and explain weak coverage; observation count alone does not establish orbit quality. Export only reviewed independent measurements, with their rejection reasons retained locally. [MPC reporting guidance](https://docs.minorplanetcenter.net/mpc-ops-docs/astrometry/reporting-observations/)

### Find_Orb and visualization

**Service investigated.** [Online Find_Orb](https://www.projectpluto.com/fo.htm) accepts ADES PSV/XML and obs80 via its observation form/file upload. It offers JSON containing elements, observations/residuals and ephemerides, as well as an HTML pseudo-MPEC. Use the reviewed observation set to request structured output; retain the original response and request options. Treat the form and JSON contract as an integration to verify with fixtures, not an assumed stable REST API. Selecting an output ephemeris observer does not replace the acquisition station on each input observation. For a reviewed known identity, offer a separate, labelled choice to combine published MPC observations with this session, preserving sources and removing duplicates.

Find_Orb also documents a `COD XXX` header with a location comment carrying longitude, latitude and altitude for an unregistered site. Verify that route in the online integration and label it separately from an MPC-ready registered-station export; service-specific station extensions are not automatically acceptable MPC submissions. [Find_Orb station-location options](https://www.projectpluto.com/find_orb.htm)

**Proposed interaction.** Add **MPC / Orbit** to T and FM report cards and the desktop's completed-track review. Open a compact panel with an observation table (UTC, frame/group, RA/Dec, errors, site, quality and include/exclude), missing-data explanations and an editable designation/context form. Provide **Download ADES**, **Download obs80**, **Copy**, and **Calculate with Find_Orb**. Calculation sends the displayed observation set to the named service; MPC submission itself remains a separate user action. Preserve reviewed selection and any solver-excluded points. Support importing additional nights for the same reviewed target, rather than equating report-local IDs across runs.

| View | Proposed presentation |
|---|---|
| Observations | Sortable table and exact generated text, linked to the existing animation/cutout and selected frame. A tangent-plane sky plot shows measured points with error bars and the computed path, including RA-wrap handling. |
| Fit quality | Observed-minus-computed RA*cos(Dec)/Dec residuals against time, rejected points, RMS, weights, observation count, nights and arc length. Assumed weights remain labelled; a small RMS alone is not evidence of a unique orbit. |
| Orbit | Start with a heliocentric ecliptic-plane view for asteroid/comet solutions, showing Sun, Earth and relevant planets at the stated epoch, the fitted trajectory and the tiny observed segment highlighted. Show the reference frame, central body, epoch/time scale and elements with supplied uncertainties. An interactive rotatable 3D view is a useful extension. Support unbound trajectories without drawing a fictitious closed ellipse. |
| Follow-up | Predicted sky path/table over a selectable interval, with supplied uncertainty regions and acquisition-site visibility where available. Unknown uncertainty is shown as unavailable. |

Short single-night arcs can admit very different orbits. Present results as preliminary when appropriate, display competing solutions or uncertainty samples when supplied, and distinguish insufficient constraints from a service failure. Do not infer impact risk, a secure identity or a precise long-term ephemeris from a nominal fit. Natural-satellite/other central-body handling needs an explicit supported configuration rather than forcing every moving target into a heliocentric asteroid model. [Find_Orb short-arc and uncertainty discussion](https://www.projectpluto.com/find_orb.htm)

**Saved reports and ownership.** Persist a versioned observation snapshot with original frame/group identities, measurements, timing, WCS/site provenance and review state. Existing summary coordinates and FM fitted samples alone are insufficient. Embed the export payload in HTML so downloads work when opened from disk; save the structured counterpart beside the report. Desktop and headless exports share the same application service. Online calculation should reuse the owned localhost report proxy; when unavailable, retain offline downloads and a copy/open-Find_Orb fallback. Save returned elements, residuals, sampled orbit coordinates and ephemerides for offline viewing, keyed to the exact selected observations/options. Keep site/contact details deliberate in exported payloads, not in filenames or public screenshots. File/network work runs on workers; cancelled, stale or failed calculations preserve the completed detection/report. Numerical source measurement belongs in JTransient, WCS/export/provider integration in SpacePixels; no detection classifications are changed.

**Acceptance and decisions before implementation.** Validate ADES schema/enumerations and obs80 round trips using public examples; test UTC/day boundaries, RA wrap/negative zero declination, missing WCS/catalogue/site, remote-site conflicts, optional magnitudes, FM fitted-only evidence, grouped measurements and repeated inputs across iterative passes. Compare known-object residuals with independently reduced astrometry. Exercise underconstrained/multi-night arcs, mixed published/local observations, JSON errors/timeouts, cancellation, offline reopening and report/desktop parity. Set astrometric acceptance limits, grouping rules, provider limits/contract and central-body scope before implementation; an orbit request must remain useful even when it explains why export or fitting is unavailable.

## F34. Moving-object light curves and rotation periods

**Request (2026-11-14).** Draw a brightness-versus-time curve for moving-object tracks and faint movers in the report, with the same review available in the desktop application. Investigate measurements suitable for asteroid rotation-period analysis. A light curve can constrain rotation through changing projected shape/albedo; sky-plane motion is a different measurement, and a light curve does not establish the orbital period.

**Measurement proposal (simplified 2026-11-14).** Use the standard aperture photometry described in JTransient's [MP-1 proposal](../JTransient/FEATURE-REQUESTS.md#mp-1-moving-object-photometry): begin with an object-aperture radius of 1.5 x stellar FWHM and a sky annulus from 3 to 5 x FWHM, reusing existing photometry helpers. Follow the moving position on original linear pixels, subtract local sky and normalize by exposure duration. Reuse comparison-star correction when available, otherwise label the curve uncorrected. Retain faint/negative flux and flag contamination or significant trailing. Advanced PSF/trail modelling is outside the initial scope. Neither threshold-footprint `totalFlux` nor processed faint-search scores are suitable light-curve measurements. This feature must work independently of enabling variable-star candidate detection.

**Report and desktop presentation.** Add a compact **Light curve** below each eligible T/FM animation, with exposure-midpoint time in UTC/elapsed minutes and relative flux, error bars, quality flags and hover links to source frames. Offer relative magnitude for valid positive signals, retaining the flux view for faint sources. Label corrected versus uncorrected curves and approximate errors. Provide a measurement table and CSV with time, exposure, position, measured/predicted-position provenance, flux/error, correction, flags and optional relative magnitude. This requires no WCS or external catalogue for relative photometry. Absolute magnitudes need their own validated calibration, not simply a Gaia label or an assumed asteroid colour.

For faint targets offer optional non-overlapping time bins with membership and effective time shown, while keeping per-exposure measurements available. Binning improves precision at the expense of time resolution and can hide a fast rotation. Do not replace missing measurements with zero or select only positive/detected frames. Reuse the existing report chart infrastructure; save enough versioned data for headless, iterative and offline reports, independent of bounded animation/review samples.

**Rotation-period investigation.** Offer an optional period search after usable curves exist: an error-weighted floating-mean search and low-order Fourier models, followed by phase-folded curves for the strongest competing periods. Asteroids often have double-peaked rotational light curves, so compare half/double-period alternatives and daily/cadence aliases instead of automatically labelling the strongest sinusoidal period as rotation. Display the periodogram, candidate periods, amplitude, phase coverage, observed span and exposure/bin widths; distinguish a tentative synodic rotation period from a uniquely measured value. The [MOA-II asteroid study](https://ntrs.nasa.gov/api/citations/20230003067/downloads/stac674.pdf) illustrates Fourier/period analysis and double-peak checking. A low periodogram false-alarm probability does not select the correct alias. [Astropy's periodogram guidance](https://docs.astropy.org/en/stable/timeseries/lombscargle.html)

A short trend may remain useful even when no period is supported. Do not force a result from a fraction of a cycle or detrend away a slow rotation. For combined nights, preserve passbands and calibration offsets, establish the target identity, and account for changing distance/phase geometry and light travel time when ephemerides support them. Keep observed UTC times intact for F33 exports; any emission-time correction is a separate analysis coordinate. [PTF rotation and phase-function study](https://arxiv.org/abs/1504.04041)

**Acceptance and staging.** Begin with MP-1's simple aperture/annulus checks and a useful relative light curve, then consider period estimation separately. Period tests need single/double peaks, irregular cadence, daily aliases, partial cycles, long exposures/bins and multi-night offsets. Establish period-quality limits from those checks rather than a universal frame count. Preserve detection results and document added time/memory cost.
