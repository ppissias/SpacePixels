# Feature requests

New or changed functionality for SpacePixels: what the application does, as opposed to how its code is organised. Design and refactoring work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Detection and photometry algorithm proposals belong to JTransient's [feature requests](../JTransient/FEATURE-REQUESTS.md), and solver features to JPlateSolve's [feature requests](../JPlateSolve/FEATURE-REQUESTS.md).

A request is a proposal until the maintainer decides on it. A settled choice is marked **Decision (made)** and kept in the maintainer's words, followed by what that choice resolves. Points that are still open stay listed as decision points. Remove requests when implemented or closed; describe implemented behaviour in [MANUAL.md](MANUAL.md) and the developer documentation, and use git history for the proposal and its reviews.

| ID | Request | Status |
|---|---|---|
| [F04](#f04-mcp-agent-services) | MCP agent services over HTTP | Agreed: HTTP. The services table awaits agreement. |
| [F09](#f09-consolidated-super-report-for-iterative-runs) | Consolidated "super report" for iterative runs | Retained for future work; the matching policy is to be proposed. |
| [F15](#f15-report-themes-and-custom-css) | Report themes and custom CSS | Planned for the next release; not implemented |
| [F01](#f01-slow-mover-and-per-frame-tuning-relation) | Slow-mover and per-frame tuning relation | Proposed experiment |
| [F22](#f22-known-asteroids-and-comets-in-the-field) | Known asteroids and comets in the field | First version implemented 2026-11-02 (viewer overlay); report table and track comparison open |
| [F23](#f23-fixed-camera-and-all-sky-sequence-analysis) | Fixed-camera and all-sky sequence analysis | Analysed 2026-11-02: streaming design proposed; needs camera details and sample data |
| [F24](#f24-calibrated-magnitudes-for-variable-stars) | Calibrated magnitudes for variable stars | Reviewed 2026-11-03; estimated-G scope proposed, implementation deferred |
| [F25](#f25-experimental-faint-mover-search-settings-and-presentation) | Experimental faint-mover search: settings and presentation | Proposed 2026-11-03: off by default, dedicated settings section; depends on JTransient SM-F2 |

The other investigations from 2026-11-02 are owned by JTransient: [remaining deferred photometry work](../JTransient/FEATURE-REQUESTS.md#vs-11-photometry-strictness-defaults-and-recovery-review) and [readiness policy](../JTransient/FEATURE-REQUESTS.md#vs-9-selected-stars-and-the-linearity-check-minimum), [single faint-streak elongation](../JTransient/FEATURE-REQUESTS.md#st-7-elongation-threshold-for-single-faint-streaks), and [registration quality](../JTransient/FEATURE-REQUESTS.md#pt-5-registration-quality-and-shared-jitter-tolerances). The requested [plain-English scientific photometry overview](../JTransient/ALGORITHM.md#photometry-in-plain-english) is now in the algorithm reference. Application settings, reports and callers must follow any agreed engine changes in the same work.

The full-frame investigation of three possible additional experimental high-sensitivity moving-object detectors is owned by [JTransient SM-F2](../JTransient/FEATURE-REQUESTS.md#sm-f2-experimental-high-sensitivity-moving-object-detection), requested 2026-11-03. Its measured Klangwolke-2 recovery, injections, controls, resources and pending integration decisions are recorded there. SpacePixels controls and presentation will follow an agreed engine contract.

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

## F15. Report themes and custom CSS

Offer a light report theme alongside the current dark appearance, and make report colours easier to customize through CSS.

**Decision (made, 2026-10-29):** "I will do it on the next release". Deferred from the current release; implementation has not started.

**Proposed scope:**

- Built-in **Dark** and **Light** palettes, with Dark as the default and a selector in the report toolbar. Switching changes the open report immediately, without rerunning detection, and is independent of the desktop application's theme.
- A shared **`report.css`** with named CSS variables for backgrounds, text, borders and accents, replacing hardcoded presentation colours. An optional **`report-custom.css`** provides advanced overrides; editing it only requires refreshing the browser. Stylesheets travel with the report folder and work offline.
- Apply the same themes to ordinary reports and iterative summary/index pages. Review controls, tables, status messages, legends and inline SVG charts for contrast and consistent category meanings in both themes.
- Preserve astronomical image/GIF pixels and their dark viewing areas. Review colours already drawn into diagnostic images separately; changing page CSS does not update those image assets.

A graphical colour editor is outside this proposal. Still to decide: whether custom CSS can also be selected once for future exports, and whether/how the browser remembers a theme selection.

**Acceptance.** Check both themes on representative complete reports and iterative indexes, including charts, warnings, filters and lookup results. Verify custom overrides and offline use after copying the report folder. Theme changes must not change measurements, detection results or exported scientific data.

## F01. Slow-mover and per-frame tuning relation

Proposed investigation only. Compare the maximum-stack slow-mover thresholds with the tuned per-frame thresholds on the same real datasets before changing defaults. Measure recovery, false positives and run time. A maximum stack's sigma is not a single-frame sigma; the current distinction is described in JTransient's [algorithm reference](../JTransient/ALGORITHM.md). This remains a comparison of existing behaviour; any algorithm or default change needs a separate maintainer decision.

## F22. Known asteroids and comets in the field

**Request (2026-11-02).** Like the stellar catalogue, obtain the known asteroids and comets expected within the field over the observing sequence, and show their changing positions in Blink and Inspect.

**Decisions (made, 2026-11-02):**

- "I propose to have yet another button, for this data to be fetched and then displayed."
- On what is sent: "The command should send what it needs in order to get the information, no problem with sending also the coordinates of the observer, this rule I had was more for exporting data I pushish as examples."
- JPL only (SB-Ident and Horizons), with SkyBoT kept as a report link, and a first version with the viewer overlay only: agreed.
- On the magnitude limit: "I propose to have this configurable, like we do for the sky catalogue and propose to have the same magnitude for all objects."

**Implemented (2026-11-02): the first version.** **Fetch Asteroids and Comets** and the asteroid and comet layer of **Annotate** are described in [MANUAL.md](MANUAL.md#known-asteroids-and-comets), the design in [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md#sky-catalogue-and-plate-correction). The analysis and its measurements are in git history. Two of its findings shaped the code: JPL's field search leaves out every comet unless `mag-required=false` (now set for the report's per-finding lookup too), and it picks its candidates by two-body motion of current orbits, which missed Apophis on 2021-03-06 (4.4° off) until near-Earth objects were searched 5° around the field.

**Remaining work.**

1. A report table of the known objects in the field: predicted magnitude against the field depth, and whether each was detected. Bright known objects that were not detected give a completeness check of the detection on real data.
2. An explicit comparison of tracks with predictions (positions within the uncertainty over at least three frames, agreeing rates) before a track is called consistent with a known object. The satellite exposure-path geometry is implemented in `report.lookup`; reuse the demonstrated calculations where the minor-planet inputs and uncertainty model match.
3. A clock check: when the matched known objects all sit ahead of or behind their detections along their paths, report the implied frame-time offset (for example camera local time written as `DATE-OBS`) without correcting it silently.

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

## F25. Experimental faint-mover search: settings and presentation

**Request (2026-11-03).** Independently discover faint moving objects that the per-frame detection misses, using the methods of [JTransient SM-F2](../JTransient/FEATURE-REQUESTS.md#sm-f2-experimental-high-sensitivity-moving-object-detection).

**Decisions (made, 2026-11-03):**

- "I would not like to do a known object stack, I would like to independently discover these using the methods we are exploring." Fetched known asteroids ([F22](#f22-known-asteroids-and-comets-in-the-field)) are only a reference to check the results, never used to find them.
- "I think this feature should be disabled by default, I think we need another dedicated section for this and any settings it may need."

**Settings.** A dedicated **Faint Movers (experimental)** section in Detection Settings, listed under **Analyses in This Run** like variable-star photometry:

- **Search for faint movers**, off by default.
- **Slowest motion**, shown as a displacement over the session in FWHM.
- **Fastest motion**, in arcsec/min when a plate solution exists and px/min otherwise; stored in px/min.
- **Minimum score**: higher is stricter; the default follows SM-F2's calibration.
- **Time limit**, in minutes.
- An estimate of the extra run time for the current session (frames, frame size, speed range), shown before the run.

Profiles save these keys. Auto-Tune leaves them unchanged in the first version.

**Report.** A separate category, **Faint mover candidates (FM#)**, apart from moving-object tracks. Each candidate shows:
- position and motion: arcsec/min and position angle with a plate solution, and RA/Dec along the path;
- score and half-session scores;
- the shift-stacked cutout and a blink of the frames with the fitted path.

With fetched known asteroids, a candidate that follows a prediction within its uncertainty is labelled with that object's name, as a comparison only. The run summary states the search domain (speed range, frames, area searched) and whether the search was truncated.

**Viewers.** A **Show faint movers** layer in Blink and Preview Frame draws each candidate's path and its position in the current frame.

**Depends on** the JTransient SM-F2 engine stage and its result fields. Until they exist, nothing changes in SpacePixels.
