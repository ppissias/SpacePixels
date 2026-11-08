# SpacePixels Release Notes

<!-- root-toc:start -->
## Contents

- [Unreleased](#unreleased)
  - [Added](#added)
  - [Changed](#changed)
- [2026.10-04](#202610-04)
  - [Changed](#changed-1)
  - [Removed](#removed)
  - [Build and release checks](#build-and-release-checks)
  - [Upgrading and API changes](#upgrading-and-api-changes)
- [2026.10-03](#202610-03)
  - [Highlights](#highlights)
  - [Detection changes](#detection-changes)
  - [Import](#import)
  - [The settings tab and the application](#the-settings-tab-and-the-application)
  - [Fixes](#fixes)
  - [Upgrading and API changes](#upgrading-and-api-changes-1)
- [2026.10-02](#202610-02)
  - [Highlights](#highlights-1)
  - [Detection changes](#detection-changes-1)
  - [Variable-star photometry](#variable-star-photometry)
  - [The report and application](#the-report-and-application)
  - [Fixes](#fixes-1)
  - [Upgrading and API changes](#upgrading-and-api-changes-2)
- [2026.10-01](#202610-01)
  - [Highlights](#highlights-2)
  - [The report](#the-report)
  - [The application](#the-application)
  - [Detection changes](#detection-changes-2)
  - [Fixes](#fixes-2)
  - [Upgrading](#upgrading)
  - [Under the hood](#under-the-hood)
<!-- root-toc:end -->

## Unreleased

- **Main-window correction control.** A compact Apply checkbox beside plate-correction status shares the viewer setting and its saved state. Save failures accurately describe the current session choice.
- **Saved findings in viewers.** Import restores the newest compatible completed report's findings, including compatible iterative passes. Preview and Blink show all available categories by default, with measured positions, fitted faint-mover paths and explicitly labelled reference markers. Reports add portable frame identities, measured geometry and variable hover text; older exports retain limited reference display. `DetectionOverlays` gains a fourth constructor component for other findings.

- **Faint-mover review and motion controls.** The report pixel allowance increases to 96 MiB alongside the 64 MiB engine pool. Catalogue comparisons check every searched fitted epoch and show fitted-frame counts/residuals, distinct from measured tracks. Both motion controls show arcsec/min with WCS, with explicit fallback units and a 2-FWHM/session floor; reports show the completed search's angular range.

### Added

- **All-frame quality control under Inspect.** Run the detection engine's exact quality checks before detection, with cancellation, subtle table verdict colours, measured FWHM/star count/elongation, rejection reasons and detailed session limits. All imported frames and selections remain intact. Changed detection settings visibly stale previous results; cancelled/failed checks preserve the last completed result. JTransient adds the corresponding immutable quality-only result and bounded-memory loader APIs; existing pipeline calls and scientific decisions are unchanged.

- **Known-object report comparisons (F22).** A saved-prediction table adds magnitude ranges, sampled path/mask coverage, ordinary-track position/rate evidence, existing FM comparisons and a cautious common-clock diagnostic. New Horizons fetches include fourteen-hour sampling padding; older caches may need a refetch for clock review. Missing uncertainties, ambiguity, passband differences and incomplete search/fetch coverage stay explicit; timestamps and detection outputs are unchanged. Original/trial comparison times, positions and residuals are retained in optional `knownSolarSystem` data within report schema version 1.
- **Known-object report presentation.** The inventory adds JPL object-information links, compact finding badges and expandable comparison evidence. Ordinary track cards show possible asteroid/comet names for nominal-time position/rate agreements, retaining competing matches. Ephemeris uncertainty wider than the field is explicitly unavailable for ordinary-track identification and clock comparison; it no longer makes distant tracks appear compatible. Supplied values remain recorded, and detection results are unchanged.
- **Local-rescue report associations (F27).** Reciprocal possible-continuation links compare separate rescue frames with the measured ordinary track at exposure midpoints, preserving every finding and competing association. Optional `rescueAssociations` JSON records the measured residual/displacement evidence. No engine linking or merging rule changes.
- **Faint-mover work previews (F28).** Long tiles now report estimated phase work and completed tiles. The shared three-second live view shows a bounded example source crop with active-tile/trial-path geometry, explicitly distinguished from retained candidates. Hidden views make no snapshots; pending pictures are coalesced and dropped at phase changes. Skipped and time-limited searches report distinct outcomes.

- **Slow-mover centroid review (SM-F3).** Each maximum-stack candidate gains copied supported positions, a descriptive capture-time drift fit with residual/correlation/coverage, and a positions CSV. Missing clocks/support stay explicit. Track overlap notes now show actual common-frame centroid separations when available and avoid claiming that a spatial crossing is already the same source. Detection thresholds, candidate selection and both findings are preserved; temporal rejection and automatic deduplication remain experimental.

- **Faint movers (F25, experimental).** An optional digital-tracking search, off by default, has a dedicated settings page, shared Overview switch, approximate resource estimate, FM report cards/JSON/CSV, score-stack cutouts and source-frame blinks. Blink and Preview Frame can display the fitted paths on searched frames. Saved known-object predictions are captured at run admission and compared only after detection; matches and unmatched candidates remain unverified. Skips, partial coverage and unavailable evidence are explicit. Profiles store speed in px/min and time in seconds; Auto-Tune preserves these settings. The report JSON gains optional `faintMoverSearch`, `faintMovers` and `includedGroups.faintMovers` fields within schema version 1. The supported Java request/result signatures are unchanged; internal summary, inclusion, sky-snapshot and overlay constructors now carry the additional category.
- **Satellite motion comparison (F21).** Streak-track reports can compare their saved observations with SatChecker predictions over the actual exposure intervals, showing path distances, direction, speed evidence, per-exposure overlays and orbital provenance. Results and provider responses reopen offline. Incomplete searches, missing historical orbits and sampling/metadata limits stay explicit; the feature does not assign a satellite identity probability. Existing reports keep their candidate lookup flow. The report JSON gains an optional `streakObservations` map without changing existing schema-version-1 fields; this map includes the observing context needed for reproducible comparisons.

### Changed

- **Stronger Asinh stretch.** **Stretch Strength** now goes up to 100 and **Black Point** up to 95 %, in the viewers, on the Image Stretch tab and for Batch Stretch. Settings up to the old limit of 50 look as before; above 50 each strength step lifts faint parts about 10 % more. The sky of a frame with a very wide brightness range stayed dark at the old limit.
- **Main window redesign.** The four steps have number badges with their status directly under the heading, so their buttons line up, and **2 Astrometry** and **3 Inspect** say they are optional. An empty window shows a drop zone that explains the supported inputs, with **Choose Folder…**, **Prepare Raw Frames…** and a link to the manual's workflow. One action is highlighted as the next step: choosing a folder, **Convert to Mono**, **Detect Moving Targets**, or **Open last report** after a run that wrote a report, until the settings change. The settings box labels **Sigma**, **Grow** and **Min px**. **Auto-Tune Settings** now sits over that box and the report links under the Detect buttons, so the steps are shorter and the table no longer moves when a report appears. The frame list gains a **#** column with the frame numbers of the viewers and reports, and its toolbar counts the frames, the kept and rejected frames and the selection, says **QC stale**, warns when too few frames would remain, and offers **Clear selection**. The button captions name what they act on, such as **Preview Frame 3**, **Blink Selected (5)** and **Plate Solve Frame 3**. **User Manual**, **Tips** and **Acknowledgements** move into a **Help** menu at the right end of the tab bar. The note on the Auto-Tune settings shows a saved run's date once and no longer loses the end of its lines. Detection, frame selection and results are unchanged.
- **Tips.** A **Did you know?** screen opens when SpacePixels starts, with a new tip each time and a link to the manual, and stays available from the **Help** menu. It does not block the main window, and **Show tips at startup** switches it off.
- **Blink's controls above the image.** The zoom, **Export GIF…**, **Open in Preview ↗**, the panel button and the transport bar now sit directly above the image, so the frame list and the side panel reach the top of the window.
- **Findings show on every frame.** In the viewers, every report finding with measured positions now shows on all frames: faint where it was measured in another frame, vivid in the frame it was measured in, as tracks already did. Single-frame streaks and anomalies can therefore be found from any frame, and hovering over a faint mark names its frame. Manual Transient Inspection now shows all the findings too, not only the tracks.
- **Report findings switch.** The Report findings section gets a switch in its header (key R), like Raw detections and Catalog annotations. In all three, and under Other findings, switching a group off now disables the controls under it; they keep their choices for when it is on again. Trial values no longer switch the raw detections on, since the fields are disabled while they are off.
- **Moving object and streak tracks are separate.** The Tracks row becomes **Moving object tracks** (key T) and **Streak tracks** (Shift+T), with their own counts and switches.
- **Show in Preview replaces Show full size.** The Image Stretch editor's **Show in Preview** shows its frame in Preview Frame, and the separate full-size window is gone. Preview Frame now shows colour frames, in colour and without raw detections. The editor also loses its **Close** button: close its window or press Esc.
- **Double-click a frame.** Double-clicking a frame in the main window's list shows it in Preview Frame.
- **Blink without a selection.** With fewer than three frames selected, the main window's blink button reads **Blink All** and plays every frame, as **Blink ↗** in Preview Frame already did; select three or more to blink only those.
- **Detect from any settings page.** **Detect Moving Targets** now sits at the top right of Detection Settings on every page, so a changed setting can be tried at once. It replaces the Overview's own button.
- **Satellite comparison follows your frames.** The overlay of **Compare track with satellites** now lies as the frames do, using the plate solution captured at the track, with a north/east compass and a note when the frames are mirrored compared with how the sky looks by eye, as some optics do. Distances and rankings are unchanged. Each streak observation in `report-data.json` gains an additive `astrometry.imageAxes`; reports without it keep the north-up overlay.
- **Streak cards read like the others.** The text and lookup tables in a report's streak cards are no longer all orange; the card's coloured edge still marks it.
- **Report styles and scripts in their own files.** Each report folder now has `report.css` and `report.js` beside `detection_report.html`, which keeps the report's data. Iterative result pages get their own `report.css`. Copy the whole folder to keep a report complete, as was already needed for its pictures.
- **Quality control keeps the table's columns in place.** The quality columns are filled where they are instead of moving beside the file name, and **Run quality control** and **Quality details…** now sit above the frame list, on its left. **Quality details…** shows the verdict, the session's counts and each measurement beside its limit, with **Copy details** for the text; it is also on the table's right-click menu once a frame has a result.
- **Simpler variable-star settings.** Eight expert numeric controls remain for candidate requirements and measurement/comparison choices. Ten linearity and artifact-gate controls are hidden from the GUI while their `DetectionConfig` fields, defaults and saved values remain unchanged. The enable switch and report presentation controls are unchanged.
- **Local rescue (#32).** Local repeats now need at least three detections instead of two. The check still counts detections rather than distinct frames; clustered detections may share a frame. Other rescue thresholds are unchanged.

- **Night & Equipment optics readings (#27, #28).** Tooltips explicitly identify each region's measured stretch or light-to-core offset. Captions distinguish local maxima from shared or fitted field-wide components, and an uncertain radial coma fit is labelled unresolved. Small overall components no longer imply that every region is undistorted. Measurements and detection results are unchanged.

- **Viewer side panel (F29, first part).** Blink, Preview Frame and Manual Transient Inspection move their layer switches, catalogue annotations and display stretch into a side panel on the right. Each layer has a row with a swatch matching its marks, its count, a label switch (**Aa**) and an eye; a layer without anything to show says why. Other findings unfold into the report's categories. Catalogue objects are drawn in off-white and findings in their report colours. The choices are shared by all viewers and remembered; the panel can be resized and hidden. In Blink, ↑ and ↓ step through the frames while paused and still change the speed while playing.
- **Preview Frame toolbar and Raw detections (F29, second part).** One toolbar row: frame stepper with a number field (Enter jumps), one Fit/100/200/400 % zoom group, **Blink ↗** and the panel button. The trial thresholds move into the side panel's **Raw detections** section with its switch (D) and the frame's counts; a changed field has an amber outline and its saved value underneath, and the header says **Not saved**. **Reset** becomes **Revert** (back to the saved values) and **Apply to Settings** becomes **Save to settings**, enabled only when something differs; Manual Transient Inspection and Test Star Mask use the same names. **Blink ↗** blinks the selected frames, or all frames when fewer than three are selected, from the frame shown; Blink's **Open in Preview ↗** pauses the blink and shows that frame in Preview. Both windows stay open, and **Blink ↗** comes back to an open blink without reading its frames again. Tracks from older reports, which saved only one reference position per track, now appear under **Tracks** as dashed **ref** squares instead of among the other findings; reports written now restore full tracks.
- **Blink transport bar and Export GIF (F29, third part).** Play/Pause and stepping move to a bar above the image, with a timeline that places the frames by capture time (hollow ticks for frames left out, gap labels, click to jump), the frame's place (also tagged on the image) and the speed in frames per second, now from 0.5 to 30. Stepping passes over the frames left out of the loop. The frame list shows a band at each gap in the capture times (an interval over 1.5 times the median) and strikes through the frames left out; **Skip this frame** is gone (untick the frame or press S). **Select GIF region** becomes an **Export GIF…** mode that dims the image outside the selection, with **Cancel (Esc)** and **Export GIF** over the image. With the frame list, the image and the side panel side by side, the Blink window is at least 1000 × 520 (scaled with the interface); when it is narrow, the speed moves to a second line of the transport bar.
- **Viewer status bar and shortcuts (F29, fourth part).** The viewers' status bar is one line: the frame on the left; the cursor's pixel position, RA and Dec on a plate-solved frame, and value on the right. The two-line key footer is replaced by **? Shortcuts** (also the **?** key), which lists each window's keys and mouse actions, and by the keys named in the controls' tooltips.
- **Stretch histogram (F29).** The viewers' Display stretch section shows the frame's pixel values with the values shown as black drawn darker and the black point marked. It reads the stretch's own table, so it is right for every stretch function.
- **Your own report style (F15, last part).** Each report folder has a `report-custom.css` that the report applies after its built-in styles; editing it and refreshing the browser restyles that report. The new expert setting **Report Style File** (Detection Settings → Report Visualization) gives each new report a copy of your CSS file, so reports stay self-contained and work offline. `config/report-custom-example.css` lists every colour role of both themes with its built-in value. The iterative summary pages read a `report-custom.css` too.
- **Report themes and text size (F15, second part).** The report toolbar has **Theme** (Dark, the default, or Light) and **Text** (Small, Normal, Large, Extra large). The pages switch at once; pictures keep their size and dark background, and the charts, status colours and lookup results follow the theme. The browser remembers the choice where it can, also for the iterative summary pages, which are now dark by default like the reports. Each finding type has one colour in its cards, the legend, the maps and the labels: anomaly, slow-mover and local-activity cards now use the legend's colours. On the light page finding colours are darker shades of the same hue, with at least WCAG AA contrast.
- **Report styles prepared for themes (F15, first part).** The report's styles now come from shared stylesheets with about 50 named colour roles and text sizes relative to one base size, replacing some 200 hard-coded shades. Reports look the same; their data, CSVs and pictures are unchanged.
- **Uniform number fields.** Every numeric input in the settings pages, Astrometry Config (including the observatory code and site coordinates), Appearance and the viewers has the same width, instead of a width set by its decimal places, and the settings rows right-align their inputs just before the Reset button.

- **Consistent faint-mover motion units.** Both controls display FWHM over the session, with pixel and angular rates underneath. Timing/seeing conversions are labelled estimates, missing metadata remains explicit, and the maximum retains a stored-px/min fallback without usable timing. Profile values and detection semantics are unchanged; Save/Revert and session changes preserve the exact stored maximum.

- **Faint-mover review (F26).** Compact evidence cards add native-intensity Normal/Strong/Adjustable stretches, synchronized source/residual blinks, frame stepping, zoom, annotation visibility, a motion-centred view, shared-scale half stacks and actual scoring-path support. Offline assets are bounded and missing evidence stays explicit; CSV/JSON gain finite sample metadata without changing existing score or fitted-position definitions. JTransient supplies the residual/support cutouts. Search thresholds and candidate-selection rules are unchanged.
- **Detailed settings alignment.** Category pages fit a shared column to their inputs and units, reserve Reset space beside each input and keep a consistent scrollbar width. Visualization sliders sit below their number fields and do not widen other pages; checkboxes use their natural size. Faint-mover guidance distinguishes WCS unit conversion, estimated minimum speed and assumed versus measured seeing.

  With faint-mover search off, regression on the 18 reference datasets preserves scientific results, report JSON and 5,310 deterministic assets (including CSVs); HTML changes are limited to the shared animation-player registration after normalising the documented generation/timing fields. Full-size Klangwolke-2 with the search on retains all 11 reference candidates and their scores/cutouts, with review evidence for all 28 searched frames per candidate. Half-map central maxima reproduce the half scores. Offline browser checks cover stronger/faint movers, the bright-star residual, original frame gaps, paired stepping/panning, stretch/zoom/annotations, reduced motion and unavailable-asset fallback. Settings mechanics and light/dark checks at normal and 150% scaling cover the compact layout and Reset visibility.

- **Slow asteroids are no longer set aside as hot pixels.** When the frames move little between exposures, a slow asteroid could stay at one camera position for several frames and be listed under **Camera-Fixed Groups** instead of in its track. Such a group is now kept for tracking when its positions follow a straight path on the sky much better than on the camera (JTransient). On Klangwolke-2, the two camera-fixed groups of the report were the asteroid 611 Valeria; its track now has 27 frames instead of 16.
- **Variable-star error bars match the measured aperture.** A light curve measured in a smaller or larger aperture than the main one, as most faint stars are, showed and exported the main aperture's uncertainties. Its error bars, tooltips and `mag_error` now come from its own aperture; on the reference data those errors are about 0.6 to 0.9 times the old ones. The candidate card names the **Measuring aperture** and its radius, and the session's aperture summary gives each aperture's radius range over the frames instead of one radius from the session FWHM. `photometry_stars.csv` and `photometry_lightcurves.csv` have a new `aperture_fwhm_factor` column (after `amplitude` and after `mag_error`): scripts that read these files by column position must adjust. Detection, scores and candidates are unchanged.
- **Contaminated measurements are counted per frame.** The Per-Frame Measurements table gains **Bad Flux** and **Contaminated** columns, and `photometry_frames.csv` a final `contaminated` column: measurements whose star shape was spoiled by a hot pixel, cosmic ray or faint passing object, which photometry already left out. On klangwolke-2 these are 17,007 measurements.
- **Rejected frames no longer decide which stars are measured.** Frames that photometry had already rejected still counted when deciding whether a star was too faint or mostly non-linear to use, and when setting the tolerance for spoiled star shapes. They no longer do (JTransient). On M81 Kumar 73 more stars are scored and the candidates change slightly (one borderline high-confidence candidate without a catalogue match becomes constant, one possible candidate is added; all seven catalogue-matched high-confidence candidates stay). The other reference sessions keep their candidates. A saturated or non-linear measurement on a rejected frame no longer fails a candidate's linearity check either, and the check's description in the report says so; no reference session changed through this.
- **Comparison-star fit status.** The Variable Stars readiness diagnostics show how the comparison-star fit ended, for example *Finished after 6 rounds*, highlighted when it or a comparison-aperture fit reached the limit of 15 rounds. JTransient now judges the fit finished only when the brightness gradient across the field has settled as well as the transparency offset. On the reference data no result changed, and the run time is the same.
- **Satellite-trail masks are reproducible.** Where a streak track skipped a frame, the stars masked in that frame depended on the order of the trail's detected pieces. They no longer do (JTransient). On klangwolke-2 a few stars gain or lose one measurement and two borderline stars become rejected candidates; the reported candidates are unchanged.
- **Star-cap warning matches the star-shape check.** With a photometry star cap (**Max Stars Measured**), the settings warning now appears below the fewest stars from which the star-shape check can count its reference stars: 55 for the default 50, because the check counts only the stars measured precisely enough, up to the 90th brightness rank. A cap of 50 to 54 used to pass without a warning. The warning explains that a real field usually needs several times more; 0 measures every usable star. Results are unchanged (JTransient).
- **Photometry errors use the clipped sky sample.** The sky-noise uncertainty of each measurement now counts the sky pixels its sky level actually came from, after outliers such as hot pixels and faint stars are clipped (JTransient). Errors grow by about 0.01-0.03 %; a few stars at the signal-to-noise limit are no longer scored, and no reported candidate changed.
- **Fairer choice of measuring aperture.** Apertures are compared on the same measurements, and a smaller aperture's minimum radius is checked on the radii actually used on the frames (at least 1.5 px), not estimated from the session's star size (JTransient). A star whose chosen aperture lacks some of its measurements keeps the main aperture, and the aperture summary says how many do. On the reference data no result changed.
- **The aperture check compares the light curves through time.** A variable-star candidate's small and large apertures must now show the same pattern, not just similar ranges (JTransient): curves moving in opposite directions or shifted in time are rejected, and faint real variables are no longer failed by noise. The card shows the **Aperture pattern difference** with its uncertainty, and the setting is now labelled **Max Aperture Difference** (same value, 0.3; saved profiles keep it). On klangwolke-2 eight possible candidates, three of them catalogued variables, become high confidence; on M81 Kumar one catalogued variable does.
- **Single-frame deviations around a missing measurement are treated as outliers.** Two deviating measurements on either side of a frame where the star could not be measured, for example because a satellite crossed it, used to keep each other in the light curve (JTransient). They are now removed like any single-frame deviation. On Eagle-noisy this removes its only possible candidate, two such deviations around a crossing; M81 Kumar gains one possible and one rejected candidate and UG 6524's possible candidate becomes high confidence, through the statistics of their neighbours. No candidate with a VSX match changed. The manual now says how long a change must last to be found.
- **Error bars include the noise neighbouring pixels share.** Registration interpolates the frames, so neighbouring pixels share noise, and the error bars and `mag_error` were up to 2.9 times too small even for faint stars (JTransient). Each frame now measures how much noisier an aperture is on blank sky, and the errors include that factor. The readiness diagnostics show it as **Sky-noise factor**, for example ×1.82 on klangwolke-2 and ×2.71 on m5-reg, and `photometry_frames.csv` has a new final `sky_noise_factor` column. Detection, scores and candidates are unchanged. The errors remain a lower bound, because the star's own photon noise and the atmosphere are not included.
- **Brief fadings from removed star peaks are flagged.** Hot-pixel removal before registration can take the peak of an undersampled star for a hot pixel and remove its light. With dithering, the light curves then show brief fadings that last one pointing. On M81 Kumar these made most of the possible candidates and a high-confidence one (JTransient). When the lost light of brief fadings sits in the brightest pixel far more often than in brightenings, a readiness message explains it. Those measurements are then shown as flagged points and left out of the scores. On M81 Kumar the candidates go from 10/18/22 to 9/9/13 high/possible/rejected, and the other datasets are unchanged. The readiness diagnostics show the check's counts, and the Per-Frame Measurements table and `photometry_frames.csv` gain a final **Peak Removed** / `peak_removed` column.

## 2026.10-04

### Changed

- **Faint-streak search (on by default).** Detect Moving Targets also searches every frame a second time, on a copy smoothed to the size of the stars, for streaks too faint for the normal detection (JTransient's faint-streak search). Its streaks extend or join normal streak tracks or appear as new streaks; normal streaks keep their tracks and no point detection changes. Cards and `report-data.json` (`faintSearchDetections`) say which streaks it found or added to, Diagnostics lists its counts, and its measurements are marked as made on the smoothed frame. **Faint Streak Search** on the Streak Detection page switches it off; profiles saved without the setting get it on. Preview Frame and Manual Transient Inspection show the normal detection only. On the reference data it found new or longer real trails on 8 of 19 datasets and added about a third to detection time on the largest (61-megapixel) frames.

- **Enhanced stack (on by default).** The report combines the kept frames into a less noisy picture than their median: a mean that leaves out values that stand out in one frame (hot pixels, trails), with each frame's noise measured from frame differences and noisier frames weighted less. The session picture, the Map of All Detections, the footprint time map, the variable-candidate map and the Unclassified Transient Inspector show it, in the report and in their full-size files, with an automatic asinh stretch that brings out faint structure near the sky and keeps bright stars from clipping; detection, Diagnostics and the finding cutouts keep the median stack and the frames. It is saved as a linear 32-bit floating-point FITS file, `enhanced_stack/enhanced_stack.fits`, with NaN where no frame has data and a new header without the observing site. **Enhanced Stack** under Detection Settings → Report switches it off. It needs about 4 bytes of disk per pixel (about 240 MB for 61-megapixel frames) and took about 10 seconds for 28 such frames.
- **Report folders.** A report's pictures are now in folders: `field` for the whole field, one folder per kind of result (`moving_targets`, `streak_tracks`, `single_streaks`, `anomalies`, `slow_movers`, `local_rescue`, `local_activity`, `variable_stars`, `camera_fixed`, `common_motion`), `unclassified_frames` and `diagnostics`. Only `detection_report.html`, `report-data.json` and `detection_config.json` stay at the top. The photometry CSV files moved into `variable_stars`: scripts that read them by path must adjust. Local-rescue pictures are named `local_rescue_<n>_…` instead of `micro_drift_<n>_…`. Earlier reports are not changed and still open.
- **Finding cutouts stay inside the frame.** The pictures and animations of streaks, tracks, anomalies and residual findings never reach beyond the frame: a cutout that would run over an edge is moved inside, and none is larger than the frame. A streak's cutout follows the streak's own extent, so a long streak no longer gets a large square with black margins.
- **Automatic stretch.** Report cutouts and animations, the median-stack pictures, the run's live view and the star-mask window set their black and white points from the sky median and the robust sky noise, so stars and gradients no longer flatten the picture. The dark margins left by registration are left out of the levels and drawn black. Because the robust noise is smaller than the old standard deviation, **Auto Stretch Black Sigma** now defaults to 2 (was 0.5) and **Auto Stretch White Sigma** to 30 (was 5). Saved settings keep their values, so settings saved with the old defaults need the new values entered under Detection Settings → Report. Each track animation keeps one stretch across its frames, so the sky no longer changes brightness from frame to frame. The Transient Cluster Map is drawn on the field picture (the enhanced stack when the report builds one). Batch Stretch output is unchanged.
- **Stretch preview.** Auto Stretch Black Sigma and White Sigma (Detection Settings → Report) have sliders, and under them up to three crops show the stretch as you drag or type: around the tracks of the last detection run, or, before a run, a quiet patch of sky, a busy star field and the brightest star of a frame. Under each crop is the share of its pixels drawn black and drawn white.
- **Number settings step cleanly.** The up and down arrows of decimal settings, in Detection Settings, the trial fields of Preview Frame and the Star Mask Explorer, and the catalogue limits, step in decimal: 19.2 up by 0.1 is 19.3, no longer 19.2999999…
- **Known asteroids and comets.** **Fetch Asteroids and Comets** in 2 Astrometry asks NASA JPL which known asteroids and comets cross the field during the session, and where they are at every frame's time, also for frames taken years ago. **Annotate** (A) in Blink, Preview Frame, Manual Transient Inspection and Show full size draws each one where it is predicted at the middle of the exposure, with its name, magnitude, motion during the exposure and path over the session; **Layers** switches them off. The depth is set on the Astrometry Config tab (V 19 by default, the same for comets). The fetch sends JPL the field, the frame times and the observing site or IAU code, and takes from half a minute to a few minutes; the predictions are saved as `solar_system.json` beside the frames. On the reference data the predictions fell within a pixel of the detected tracks of 194 Prokne, 611 Valeria, 4767 Sutoku (2026) and 99942 Apophis (2021), with the plate-solution correction on.
- **The report's JPL lookup finds comets.** The JPL small-body lookup of a moving object or streak in the report never returned a comet: JPL leaves out objects without asteroid magnitude parameters unless asked not to. It now asks, so comet C/2018 W2 (Africano), for example, is identified. The lookup's request is written into the report, so reports written by earlier versions keep the old one: run the detection again for a report that finds comets.

- **Auto-Tune live view.** Standalone tuning and tuning before iterative passes share detection's optional three-second picture updates. Actual frame checks, trial star masks, artificial-star recovery and noise-control survivors show what the current trial is doing. Regional details keep markers readable; Hide picture stops image work. Calibration settings and results are unchanged.

- **Safe plate-solver shutdown.** Closing waits for the running job's cleanup before exit, including ASTAP termination and sidecar cleanup. An accepted WCS write finishes before closing; Appearance restart retains its existing wait for running work.
- **FITS header preservation.** Writing a plate solution preserves all HDUs, table/image payloads and tile compression. WCS cards are written to the stored image header and verified after reopening; incomplete files are refused without replacing the original.
- **Blink region GIF export.** **Select GIF region** pauses Blink for rectangle selection; right-click inside to export the included loop with its current speed, stretch and enabled annotations. Dimensions follow the selected viewport in screen pixels at any zoom and display scale. Export streams frames, reports progress and supports cancellation without replacing an earlier complete file.
- **External-process ownership.** Preparation verifies process start times while following live parent-child links, preventing Windows PID reuse from adopting an unrelated process tree.
- **Consistent conversion policy.** FITS, Siril and XISF share sequence-wide linear scaling with bounded headroom and an explicit decision for extreme values. API preparation defaults to rejecting extremes with structured diagnostics; optional policies explicitly select clipping or full-range preservation. Normal negative noise and zero registration margins remain supported, and repeated conversions preserve earlier results.
- **Registration exclusions and run folders.** Preparation continues with a validated subset of at least three registered exposures, records excluded inputs and reports the counts. Run folders use the report date/time pattern and collision suffixes.

- **Preparation validation.** Opening the preparation window automatically checks a configured Siril path. Installation checks enforce the Siril version required by generated recipes. Late cancellation is honoured through final validation and cleanup, and legacy FITS timestamp corrections preserve nanosecond precision without overflowing header cards.
- **Observing-session preparation.** **Prepare Frames…**, above ordinary import, opens a dedicated window for Siril installation checks, explicit input roles, camera/Bayer interpretation and calibration/registration on private copies. It preserves individual exposures and the supported legacy FITS metadata corrections, writes a validated mono16 sequence with common linear scaling, and offers ordinary import afterwards, closes on successful import and confirms the new dataset is ready. Live Siril output and elapsed time are shown during processing. Cancellation joins owned external processes. PixInsight WBPP output continues through the normal aligned-frame importer.
  The Siril heading is emphasized. Colour-camera presets supply common nominal Bayer patterns, with file metadata as the default. Row order and X/Y phase offsets are grouped under **Show advanced de-bayer options**; the readiness summary includes their effective values.
  Registration retains the reference-sized canvas and dark margins for drift/dither analysis. Negative-value offsets preserve these margins at zero.
  Working copies and intermediate files are removed by default; **Keep working files** retains them when requested. Final aligned frames, an optional reference stack and run records remain available.
  Session scans replace earlier input selections, recognize prefixed/filter-labelled folders and offer separate choices for nested camera, filter and exposure groups. Named calibration masters require explicit file selection and cannot be recombined through a folder containing their source exposures.

- **Windows installer.** An unsigned EXE includes a private Java 21 runtime, a desktop launcher and console launchers for batch detection and artificial-star injection. Configuration files and examples are shared with the Java distribution. Setup installs per user, offers a folder chooser and optional shortcuts, and uses a stable upgrade identity. ZIP/TAR distributions remain available for users with their own Java installation.

- **Report animation controls.** A shared toolbar pauses animations, steps through frames and changes playback speed from 0.25× to 4×. Frame sheets support immediate changes in the browser; standalone GIF downloads remain available. Reports honour reduced-motion preferences.

- **Appearance.** A new tab offers Dark, High contrast dark and Light themes, interface sizes from 100% to 300%, Restore defaults, and an optional editable FlatLaf properties file. Theme and Interface Size have separate bordered sections, with Custom style marked as advanced beside them. Saving offers Restart now or Later; choosing a custom file with Browse also offers to save and restart. Automatic restart waits for running work and saves, and each appearance button explains its action on hover. Inputs use compact widths. Viewer and popup windows protect their controls with font-aware minimum sizes, with scrolling when the display cannot fit everything. Help text, active settings, charts and image-viewer annotations follow readable colours and the selected font size. Appearance preferences are saved separately in `spacepixels_appearance.json`; the distribution includes an example stylesheet.

- **Batch Stretch and monochrome conversion.** A prominent, theme-accented **Batch Stretch** button on Image Stretch processes the entire imported dataset and writes separate `_stretched` copies. The enable checkbox is removed. Convert to Mono writes only linear `_mono` copies. Importing colour frames offers to start conversion automatically; the existing completion prompt then lets you import the mono dataset. Declining leaves the colour dataset available.

- **Siril light-exposure validation.** Preparation accepts the same small camera-reported exposure variations as ordinary import, instead of rejecting differences above half a millisecond. It compares the whole exposure range, preserves recorded durations and still rejects genuinely mixed groups. Dark and dark-flat calibration checks remain strict.

- **Manual Transient Inspection.** The window keeps the master star mask, including its jitter footprint, from its first pass. Trying Detection Sigma, Grow Sigma or Minimum Pixels re-detects only the frame on show. Cached results account for all detection settings, including background clipping and mask overlap. Apply to Settings copies the edited values. If the master settings change while the window is open, it says so and keeps the reference until the window is opened again. Closing during re-detection lets the cancelled worker close its engine safely.

- **Clean moving-object downloads.** Narrow and Wide GIFs contain no highlight circle or frame label. Wide shows twice the narrow field where the input frame permits and scales the complete picture to at most 1000 pixels per side, preserving both ends of long tracks.

- **Variable-star cutouts.** Brightest/faintest pictures now match their local sky backgrounds and use the ensemble zero-point difference to normalize transparency on one shared display scale. This makes their brightness comparison agree with the corrected light curve; photometry, light curves and data exports are unchanged.

- **Live view during detection.** The progress window shows at most one new picture every 3 seconds, including when the stage changes. Source extraction, tracking and photometry can show a picture of the work in progress, and only when the view would display it. Photometry cycles through sampled stars and can show a candidate crop beside its current light curve. A short stage may show nothing. The pictures are not repeated in a loop. Detection results are unchanged.

- **Dataset quality warnings.** Import and both detection workflows offer recommended cancellation or an explicit override for sampled hot-pixel/alignment warnings. Alignment now matches coherent star centroids, handles normalized floating-point images, and avoids mistaking brightness changes for star shifts. Inspection responds to cancellation and reports read failures. Headless callers can inspect the warning result or select fail-fast policy.

- **Responsive, cancellable workflows.** Import, detection, Auto-Tune, conversion, stretching and plate solving run as owned background jobs with progress and cancellation. Declining an import preserves the open session. Failure dialogs offer **Copy details**, and **Open last report** reopens the session's latest report.

- **Preview Frame and viewer controls.** Try the per-frame detection thresholds directly in Preview Frame, then apply only the values you edited. Closing discards unapplied trials. Preview Frame and Manual Transient Inspection can overlay the last run's tracks, and image-navigation shortcuts leave text editing to the focused field. Catalogue name and magnitude controls are retained across viewer openings.

- **Iterative detection.** Run ordinary detection on progressively larger, evenly spread subsets of a chosen dataset, with optional one-time Auto-Tune and input preparation. Each pass gets its own report and selected-input list, linked by an index. Cancellation preserves completed passes, and the session open in the main window stays available.

- **Anomaly review.** Single-frame events show before/event/after comparisons, detection evidence and links to related tracks. Satellite lookups search across the full exposure and present possible associations for review.

- **Variable-star report presentation.** Choose the detailed candidate-card limit and presentation order. Every candidate remains in the tables, maps and CSV exports, with stable candidate identifiers across presentation changes.

- **Astrometry.net accounts.** Choose anonymous solving or your own API key. Online solving uploads a temporary copy with observing-site and observer header details removed, preserving the source frame.

- **Auto-Tune.** The calibrated tuner is now the only tuning algorithm. Saved tuning results retain the chosen profiles and chart in a smaller file; existing results are compacted when their folder is imported again.

- **Large text and display scaling.** Viewer controls wrap onto additional rows, and the star-mask sidebar scrolls when needed. The main workflow area keeps the frame table visible; Astrometry guidance and the Overview page reflow, with local horizontal scrolling for the wide Auto-Tune results table. Tab reveals controls below a scrollable viewport.
- **Compressed (.fz) frames are decoded more exactly.** nom-tam-fits 1.23.0 fixes the decoding of 32-bit PLIO frames and of quantized floating-point frames: their blank pixels, and the rounding of frames without dithering. Integer frames (RICE, GZIP, HCOMPRESS) decode as before. Frames from 16-bit cameras are unaffected.
- **A dataset folder that cannot be written.** When the plate-solution correction or the sky catalogue cannot be saved there, for example in a read-only folder, it stays in use until SpacePixels closes, in the viewers and in the reports, and the window says it was not saved. Before, a correction that could not be saved was switched back, and a catalogue that could not be saved was missing from the reports. A detection also no longer waits for a pending save before it starts.
- **Libraries:** FlatLaf 3.7.2, nom-tam-fits 1.23.0, Gson 2.14.0 and the Gradle 9.8.2 build. Saved settings files load as before. FlatLaf 3.7.2 shows brighter text in the dark theme and rounded scroll bars on Windows; number fields keep the caret where you click, as before.
- **Runs with fewer than three usable frames.** When quality control keeps fewer than three frames, the detection engine (JTransient) now stops there itself, and the report shows the quality-control results and says why the detection stopped. Before, SpacePixels removed the multi-frame results afterwards, and such a report also showed the median and maximum stacks. A run in which quality control keeps no frame at all now gives this report instead of failing.
- **Every report picture uses the run's display stretch.** The corner mosaic of the pipeline diagnostics and the rainbow cluster map used the built-in stretch in reports from the batch command, even when the saved visualization preferences changed it. They now use the run's stretch, like the other pictures. Reports made in the window look as before.

### Removed

- **The AI report sections.** Codex's **Signal Weave**, Claude's **The Night, Retold** and the **Include AI Creative Report Sections** setting are gone. A saved visualization preferences file that still holds the setting loads as before; the next save drops it.

### Build and release checks

- **Release validation.** `mavenCentralBundle` now includes `releaseCheck`: ordinary tests and packaging checks, GUI mechanics, required report JavaScript tests, API consumers using both Gradle and Maven POM metadata, and selected small real-data checks on scratch copies. Node and an unlocked desktop are required. Optional datasets are configured through local `realDataRoot`; missing data is reported as skipped. Run `releaseCheck` explicitly before distributing desktop archives or installers.
- **Simpler dependency configuration.** Library versions are declared directly with the other dependencies in `build.gradle`. Separate version properties and expected-version validation have been removed; API consumer tests continue to check the packaged artifact. See [PUBLISHING.md](PUBLISHING.md).

### Upgrading and API changes

- **Headless Maven artifact.** `eu.startales.spacepixels:spacepixels` contains the Java API, supporting report resources and command-line tools, with transitive dependencies. Desktop classes, icons and FlatLaf are supplied through the application distributions. API consumers require Java 21.

- **Auto-Tune API migration.** Replace `JTransientAutoTuner.AutoTuneProfile` with `io.github.ppissias.jtransient.engine.AutoTuneProfile` and recompile. The request's `autoTuneAlgorithm(...)` builder method and `getAutoTuneAlgorithm()` are removed; choose a sensitivity profile through `autoTuneProfile(...)`.

- **Domain package reorganization.** Core application and pipeline code previously located in `eu.startales.spacepixels.util` and `eu.startales.spacepixels.util.reporting` has moved into dedicated domain packages:
  - `eu.startales.spacepixels.fits`: FITS/XISF image loading, metadata, preparation, pixel conversion, batch operations and preview rendering (`FitsFileInformation`, `FitsImportService`, `FitsBatchService`, `FitsPixelConverter`, `FitsPreparation`, `FitsVisualizationRenderer`, `BlinkSequence`, etc.).
  - `eu.startales.spacepixels.detection`: detection pipelines, execution data, auto-tuning, previews and overlays (`StandardDetectionPipelineService`, `IterativeDetectionPipelineService`, `AutoTunerRunner`, `DetectionOverlays`, `LiveFindings`, etc.).
  - `eu.startales.spacepixels.platesolve`: plate-solving service, Astrometry.net client and WCS corrections (`PlateSolveService`, `PlateSolutionCorrection`, `AstrometryNetAccount`).
  - `eu.startales.spacepixels.skycatalog`: external catalogue querying, projection, fitting and caching (`SkyCatalogueClient`, `SkyCatalogueStore`, `PlateCorrectionFitter`, `SkyProjection`, etc.).
  - `eu.startales.spacepixels.report`: HTML/JSON report generation, section writers, painters and summary models (`SessionReports`, `ReportExporter`, `ReportRequest`, `ReportData`, etc.). Reporting template and web assets moved to `eu/startales/spacepixels/report/`.
  - `eu.startales.spacepixels.report.lookup`: ephemeris and solar system proxy servers and lookups.
  - `eu.startales.spacepixels.util`: retained as a small utility package holding 6 cross-cutting general utilities (`BuildInfo`, `Cancellation`, `DebugOutput`, `SkyMath`, `StagedFile`, `Statistics`).

- **API change and binary break: `FitsFileInformation`.** The public frame metadata descriptor `FitsFileInformation` moved from package `eu.startales.spacepixels.util` to `eu.startales.spacepixels.fits.FitsFileInformation`. This is an intentional source and binary API break: embedders and library consumers must replace `import eu.startales.spacepixels.util.FitsFileInformation;` with `import eu.startales.spacepixels.fits.FitsFileInformation;` and recompile. No legacy shims are retained in `eu.startales.spacepixels.util`. In addition, `FitsFileInformation` is now immutable: mutators (`setFilePath`, `setFileName`, `setMonochrome`, `setSizeWidth`, `setSizeHeight`) and `snapshot()` are removed; use `new FitsFileInformation(...)` or `fileInfo.withFitsHeader(...)`. See [Frame metadata](API.md#frame-metadata-fitsfileinformation).

- **The processing facade is gone.** The internal `util.ImageProcessing`, `ApplicationWindow.getImageProcessing()` and `ProcessingSession.getProcessing()` are deleted; the services are used directly.
  - Read a folder with `new FitsImportService(folder, interaction)` and `readFiles()`, then close it; null interaction reads strictly. The exposure-group offer is `FitsImportService.offerToImportLargestExposureGroup`.
  - `ProcessingSession` takes a `PlateSolveService` in place of the processor: `ProcessingSession(solver, files, lastReport[, metadata, storage, ui])`. Build the solver on the application's live `AppConfig`; the session closes it. The session also gives `getPlateSolveService()` and `getBatchService()`, the batch operations on its folder.
  - `FitsImportTask(directory[, storage[, interaction]])` takes no application settings, and its `Result(files, lastReport, metadata)` holds no processor.
  - `BatchConvertMonoTask` and `BatchStretchTask` take a `FitsBatchService`, `PlateSolveTask` and `WriteWcsTask` a `PlateSolveService`, and `GeneratePreviewsTask` a `FitsVisualizationRenderer`.
  - The static helper moved to its owner, `FitsInput.getImageHDU`. `ImageProcessing.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS` is replaced by JTransient's `JTransientEngine.MIN_KEPT_FRAMES`, and `PipelineResult.tooFewFrames` says when the engine stopped for it.
  - `FitsImportService`, `FitsBatchService`, `FitsVisualizationRenderer`, `PlateSolveService` and `FitsInput` are public classes for these callers, but stay internal: they are not part of the supported API. The `api` package is unchanged; the API no longer creates a plate solver.

- **Frame metadata and import ownership.** `ProcessingSession` owns its frame array and returns a copy from `getFiles()`; metadata objects are immutable, and a WCS write replaces the session's descriptor while admitted runs keep their earlier metadata. `FitsImportService.readFiles()` returns fresh metadata without caching it and replaces `getImportedFiles`, `getFitsfileInformation` and `getFitsfileInformationHeadless`. `SessionFrames` and `getSessionFrames()` are removed, `hasPlateSolvedFrame()` belongs to the session, and `ImageProcessing.RedirectImportException` moves to `fits.RedirectImportException`. Detection and inspection jobs now take admitted frame arrays: `DetectionTask(files, config)` uses built-in options, while its full constructor takes `files, config, DetectionRunOptions, SkySnapshot, DetectionSafetyPrompt`; `ManualTransientInspectionTask` takes `files, config`. Capture application options at admission with `DetectionRunOptions.capture(..).withVisualization(..)`. Missing frames still fail during job execution. Every no-interaction import now uses the API/CLI's readiness-first error order; an input failing both readiness and sequence consistency can therefore show a different first error through the former noninteractive `getFitsfileInformation` path. Accepted inputs, interactive conversion choices and supported pipeline request/result contracts are unchanged.

- **Detection without the processing facade.** Detection no longer passes through the internal `ImageProcessing`: its `detectObjects`, `runDetectionPipeline`, `exportDetectionReport`, `captureRunOptions`, `getLastTrackOverlay` and `getLastVariableStarOverlay` are gone. Callers run `detection.StandardDetectionPipelineService` with explicit frames (`ProcessingSession.getFiles()` in the window, `FitsImportService.readFiles()` for headless callers), as `DefaultSpacePixelsPipelineApi` does, and capture options with `DetectionRunOptions.capture(..).withVisualization(..)`. The nested types `ImageProcessing.DetectionSummary`, `ImageProcessing.PipelineExecutionData` and `ImageProcessing.DetectionSafetyPrompt` are now the top-level `detection.DetectionSummary`, `detection.PipelineExecutionData` and `detection.DetectionSafetyPrompt`, unchanged. A finished run's overlays (`detection.DetectionOverlays`) belong to `ProcessingSession` (`setLastRun`; `getTrackOverlay` and `getVariableStarOverlay` as before), and the session also owns its frame array. The supported pipeline API is unchanged.

- **Desktop storage construction.** The internal `SettingsStorage`, `SessionStorage` and `SessionPersistence` classes are replaced by `session.ApplicationStorage` and the existing `ProcessingSession` lifetime. Embedders constructing desktop/session infrastructure pass the shared storage to `ApplicationWindow` or `FitsImportTask`, and storage plus a UI executor to the metadata-aware `ProcessingSession` constructor. Metadata saving uses `ProcessingSession.saveCatalogue` and `saveAutoTune`.

- **API change: `VisualizationSettings`.** `ExportVisualizationSettings` (package `util.reporting`) is replaced by `eu.startales.spacepixels.config.VisualizationSettings`, which `DetectionRunOptions` now carries. It also holds the detection marker sizes and the most animated detections of the report. Start from `VisualizationSettings.defaults()` and change values with its `with…` methods; the positional constructors are gone. `DetectionRunOptions.capture(AppConfig, int)` takes only the observing site and uses the built-in visualization settings, and `AppConfig` no longer has `variableStarReportOptions`. See [Run options and isolation](API.md#run-options-and-isolation).

## 2026.10-03

This release searches each session only where every frame has image data, sets hot pixels and dust aside before tracking, and builds a better star mask on frames with a sky gradient. It also makes SpacePixels safer with your files and settings. Detection engine: **JTransient 1.0.4**.

### Highlights

- **Detection keeps to the session data area.** Registration fills the parts of a shifted frame that have no data with black. Before, a fixed border and darkness probes around each object kept detection away from that fill, and on drifted sessions they left 10–25 % of the frame unsearched.
  - The engine now measures where every kept frame has image data and searches only there.
  - Sky levels, frame quality, slow movers, photometry and both auto-tuners use the same area.
  - Frames with some fill no longer have their noise overestimated, so they are searched as deeply as the others.
  - The report draws the area as a blue dashed outline on the Overview picture and the detection maps. It says how much of the frame the area covers and which frame limits it most. See [Overview](MANUAL.md#overview).
- **Hot pixels and dust are set aside before tracking.** Some defects stay at one place on the camera while the dither moves the sky. Detections within 2 px of one camera position in at least 3 frames, while those frames moved at least 4 px, are now removed before tracks are linked, so they no longer become tracks.
  - The report lists them as camera-fixed groups (FC1, FC2…) with positions and animations.
  - The switch is **Reject Camera-Fixed Sources** on the Track Linking page, on by default.
- **Likely Artifacts.** The camera-fixed groups and the common-motion groups are folded into one collapsed **Likely Artifacts** group after the variable stars. Their trail and animation pictures are drawn in colour.
- **A better star mask on frames with a sky gradient.** The master star map also looks for stars against the local sky. A gradient no longer hides the faint stars on its darker side, which used to come back from the frames as many stationary leftovers.
- **Variable stars across the field.** A new picture circles every reported variable candidate on the median stack, with full-size light curves. Blink and Preview Frame can mark the candidates of the last run with **Show variable stars** (key V). See [Light curves and exports](MANUAL.md#light-curves-and-exports).

### Detection changes

- **Suspected streaks are gone.** Anomalies of one frame that lie on a line are kept as a streak only when they complete a confirmed streak, for example the pieces of a broken satellite trail. Otherwise they stay separate anomalies; on noisy sessions such groups were mostly noise. The suspected-streak category, its report sections and the **Suspected Streak Line Tolerance** setting are removed.
- **Streak checks.**
  - A streak seen in several frames that does not move is no longer confirmed as a streak track.
  - Streak motion is compared in pixels, so a tiny step between distant fragments no longer merges unrelated streaks.
- **Quality control.** The sky and the frame-quality statistics are measured inside the data area. A frame that alone shrinks the data area by 5 % of the frame or more is left out as "Shifted away from the other frames", at most a tenth of the frames.
- **Residual analysis** counts gaps and coverage over the kept frames, so a frame left out by quality control no longer counts as a gap.
- **Point tracks** always need at least three points, whatever the profile says.
- **Auto-Tune.**
  - The auto-tuners count noise rates per searchable pixel and can recover test stars near the edge of their crops.
  - The Legacy tuner scores the same star mask that the detection run applies.
- The **edge margin and void settings** are removed; the data area replaces them.

### Import

- **One exposure time per session.** Frames whose exposure time differs by more than 2 % (at least 0.1 s) are refused. SpacePixels names the odd frames and offers to copy the largest set into a subfolder and import that. The batch command and the API refuse the folder with the same list.
- **Signed 16-bit FITS.** Frames not stored as unsigned 16 bit (`BZERO` 32768) were read 32768 ADU too high.
  - The window offers to convert them, the batch command refuses them with a clear message, and the API's input preparation converts them.
  - Pixels below zero either get the same offset in every frame (recommended; recorded as `SPOFFSET`) or are set to 0.
  - See [Tips and troubleshooting](MANUAL.md#9-tips-and-troubleshooting).
- **Sequence checks before conversion.** XISF folders are checked before conversion: every frame must have the same size, channels, colour space and sample format, and either all frames have timestamps or none do. FITS sequences must share `BZERO` and `BSCALE`.

### The settings tab and the application

- **Invalid input is shown, not silently replaced.**
  - Text that is not a number, or is outside a setting's range, stays in the field, outlined in red. The line at the bottom says what to enter.
  - Save and the runs do not start until it is corrected; SpacePixels shows you the setting instead.
  - A value such as "4,5" where "4.5" is expected is no longer read as 4.
- **A run uses the settings as they are when it starts.** Editing the settings during a run no longer affects that run. Preview Frame keeps the settings it was opened with until you open it again.
- **Typed values keep their decimals.** Before, 4.25 typed into a field showing one decimal was saved as 4.2.
- **Pairs of settings are explained.** When a change crosses a pair, such as Grow Sigma above Detection Sigma, the bottom line says which setting was moved. See [Detection settings in brief](MANUAL.md#8-detection-settings-in-brief).
- **Image Stretch preview.** The preview always shows the last frame and stretch you chose, however quickly you change them, and renders one frame at a time.
- **A quiet console.** By default only problems are printed. The progress and diagnostics appear with `-Dspacepixels.debug=true` (Gradle: `-PspacepixelsDebug`).
- **Report details.**
  - The report records the SpacePixels and JTransient versions.
  - Navigation and the explanations of detections and rejected frames are clearer.
  - Groups left out of a large report are also left out of the Unclassified Transient Inspector.

### Fixes

- **Files are replaced safely.** Writing a plate solution into a frame no longer deletes the original first. The updated frame is written beside it, read back to check it, and swapped in; if anything fails, the original is kept.
  - The settings, session files, report files and converted copies are written the same way.
  - A settings Save that fails says which file was not saved and keeps showing unsaved changes.
- Manual Transient Inspection could change the saved detection settings; it now works on a copy.
- The **In report** checkboxes of the high-detection-count summary now control the right groups.

### Upgrading and API changes

- **Saved settings and profiles.** Your saved detection settings are kept. Profiles with the removed fields (`suspectedStreakLineTolerance`, `edgeMarginPixels`, `voidThresholdFraction`, `voidProximityRadius`) or without the new `enableCameraFixedRejection` trigger the existing one-time profile migration, with a backup.
- Java 21 or newer is still required. Detection engine: **JTransient 1.0.4**.
- **API changes.**
  - `PipelineResult.dataArea` and `PipelineResult.cameraFixedGroups` are new, and the telemetry reports `dataAreaFraction` and `dataAreaLimitingFrames`.
  - Input preparation takes `NegativeValueHandling`, and a folder with mixed exposure times fails with `MixedExposureException` as the cause. See [Input preparation modes](API.md#input-preparation-modes).
  - The engine throws `IllegalArgumentException`, naming the frames, when frames differ in size, only some frames have timestamps or exposure times, or exposure times differ.
  - `Track.isSuspectedStreakTrack` is removed.

## 2026.10-02

This release adds **Night & Equipment** diagnostics, remembers each session's Auto-Tune results, improves faint-streak and common-motion handling, and strengthens the variable-star checks. The report now explains where processing time went and how a candidate's measured variation compares with its catalogue entry.

### Highlights

- **Night & Equipment.** A new report section measures star size, relative transparency and, when catalogue calibration is available, sky brightness through the session. It also shows pixel scale, field of view, camera angle, focal length and sampling, with maps of star size, shape, coma, star brightness and sky background across the field. These help investigate focus drift, tilt, optical aberrations, gradients and flat-field problems. Temperature is shown when recorded in the FITS headers. See [Night & Equipment](MANUAL.md#night--equipment).
- **Saved Auto-Tune results and charts.** Results are saved beside the frames in `auto_tune.json` and restored when that session is imported again. Calibrated and Legacy results are kept separately. **Show Auto-Tune Chart…** shows the noise-versus-sensitivity trade-off for each profile, its budget and chosen setting, and the recovery of test stars by brightness. A compact summary keeps the chart available without retaining every tested combination. See [Auto-Tune](MANUAL.md#step-3-auto-tune).
- **Choose what a large report includes.** The high-detection-count summary now has **In report** checkboxes for optional result groups. Leave out a noisy group without repeating the detection: its section, crops and animations are omitted from that report. Moving-object tracks, streak tracks, single streaks and suspected streak groupings remain included. The detection settings are unchanged. See [Detect](MANUAL.md#step-4-detect).
- **Catalogue comparison for variable stars.** Each matched candidate gets a note showing observation coverage, available filters and catalogue passbands, and any amplitude or source-metadata discrepancy that needs review. These notes provide context independently of detection confidence: catalogue disagreement alone never rejects a candidate. See [Variable stars](MANUAL.md#variable-stars).

### Detection changes

- **Faint streak fragments:** aligned pieces in the same frame can extend a confirmed streak or establish a new single-frame streak. This helps collect broken satellite trails without lowering the ordinary streak-detection thresholds.
- **Anomalies near streaks:** standalone anomalies close to a confirmed same-frame streak are retained with a low-confidence note, so possible trail fragments are easier to distinguish from isolated flashes.
- **Common-motion filtering:** separated compact sources with matching displacement across at least three shared frames are grouped as likely hot-pixel or registration artifacts. The filter runs before local rescue and activity clustering, so those observations are not rescued again. Associations must be unambiguous, and an observation cannot be counted in multiple groups. Common motion is a reason for review, not proof of an instrumental origin.
- Common-motion filtering has its own switch, **Reject Common Motion**. Turning off **Enable Residual Transient Analysis** skips local rescue and activity clustering while leaving common-motion filtering available. New expert settings control faint-fragment assembly and common-motion matching tolerances.

### Variable-star photometry

- **More reliable local comparisons.** The local variability check uses sufficiently precise neighbouring stars, including other candidates. It can catch a shared local brightness fault even when that fault makes several neighbours appear variable; correlation alone is not enough without a substantial shared change.
- **Spatially uneven frames are excluded from photometry.** After ensemble correction, populated regions of a 3 × 3 field grid are compared with the centre. A residual brightness offset of at least 0.10 mag excludes that frame, and the ensemble is recomputed before scoring. Both compared regions need at least 30 usable stars. The report and CSV retain the exclusion reason. These exclusions do not remove frames from moving-object detection.
- **Clearer measurements and checks.** The report labels the measured P95–P5 range as **Observed variation in this session**, separately from catalogue ranges and full-cycle amplitudes. Period coverage is stated numerically; a short part of an orbital period can still contain a substantial eclipse. Scientific measures keep their names, with plain explanations of the checks and their outcomes.
- **Qualified catalogue comparisons.** Notes show usable measurement count, time span, largest gap, period coverage and passbands, including candidates beyond the detailed-card limit. Amplitude review prompts account for the measured noise and identify cross-band limitations. Missing or uncertain metadata is explicit, and a smaller observed variation is not flagged merely for being below the catalogue amplitude.
- **Source metadata stays visible.** Selected, independently verified CoRoT catalogue discrepancies are annotated with the publication, original values and relevant unit conversion. The notes do not replace the catalogue values or change detection confidence.
- Comparison notes update after successful VSX lookups and restore from saved report data offline. Existing sky catalogues remain readable; older entries with shared passbands or missing uncertainty metadata are marked provisional. Fetching the sky catalogue again supplies the additional metadata when available.
- A **Not ready** photometry result keeps its readiness explanation and CSV exports while omitting the empty detailed charts, candidate section and frame table.

### The report and application

- **Detection Stage Timings**, under Diagnostics, shows elapsed time and the share of engine time for each major detection step. Faint-fragment assembly is shown within tracking, where its time is already included. Image loading, Auto-Tune and report generation are outside the engine total. Diagnostics now starts expanded.
- **Common Motion** has a field map and per-group illustrations and animations. Group counts appear in the detection summary, and the Unclassified Transient Inspector distinguishes points assigned to these groups from the remaining detections. Streak fragments, anomalies and residual candidates have clearer explanations.
- The Main tab shows the active detection and star-mask settings beside **Detect Moving Targets**, plus the saved Auto-Tune profile and run date. Variable-star photometry is switched on from the Detection Settings Overview or Variable Stars page.
- **Preview Frame** on the Auto-Tune Overview now opens the same preview window as the Main tab.
- The [User Manual](MANUAL.md) has updated screenshots and instructions, including the frame limit needed to reduce memory use in iterative mode and its omission of variable-star photometry.

### Fixes

- A frame plate-solved after import now supplies its solution to the detection run and report immediately, without importing the folder again.
- Plate-solution correction uses relative validation error when choosing the polynomial order, avoiding unnecessary loss of accuracy on already precise solutions. No correction is offered when the header solution is already good or a fitted correction brings no meaningful improvement.
- Missing VSX magnitude and period values are no longer formatted as zero. Catalogue amplitude flags and separate endpoint passbands are retained for the comparison.

### Upgrading and API changes

- Your saved detection settings are kept. The four new faint-fragment and common-motion fields trigger the existing one-time profile migration, with a backup. Both new passes are enabled by default and can be switched off in the expert settings.
- Existing matching Auto-Tune files remain usable. Older files containing the full calibration sweep are rewritten in compact form when imported; chosen settings and chart summaries are retained. **Run Auto-Tune** still performs a new measurement.
- Java 21 or newer is still required. Detection engine: **JTransient 1.0.3**.
- API users can inspect ordered per-stage wall-clock timings in `PipelineTelemetry.stageTimeMs`, faint-fragment time within tracking, spatial photometry exclusion counts, anomaly low-confidence flags and common-motion groups. Common-motion groups remain available even when residual analysis is disabled. See [Detection diagnostics](API.md#detection-diagnostics).

## 2026.10-01

This release adds variable-star photometry, a new auto-tuner that measures your own frames, a sky catalogue for the viewers and the report, and a reworked window, report and settings panel. It comes with a new [User Manual](MANUAL.md).

### Highlights

- **Variable-star photometry.** An optional analysis that measures the brightness of the field stars in every frame and reports stars that change, with light curves, comparison stars and a verdict on whether the data is fit for photometry. Candidates can be looked up in the AAVSO Variable Star Index (VSX) from the report. See [Variable stars](MANUAL.md#variable-stars).
- **Calibrated Auto-Tune.** The new default tuner measures how many noise detections each setting produces and how faint a test star it still finds, then offers a ready set of settings for four profiles: **Low**, **Medium**, **High** (default) and **Maximum**. One run fills the table for all four; switch profiles without running it again. The previous tuner is still available as **Legacy**. See [Auto-Tune](MANUAL.md#step-3-auto-tune).
- **Sky catalogue and Annotate.** **Fetch Sky Catalogue** downloads the Gaia stars, deep-sky objects, bright named stars and VSX variable stars of the field. The viewers can draw them on your frames (**Annotate**, key A), and the report uses them. See [Annotate](MANUAL.md#annotate-stars-deep-sky-objects-and-variable-stars).
- **Correct plate solution.** SpacePixels checks the plate solution in the FITS headers against the Gaia stars and offers a correction for the session. On the reference sessions it brought the stars from 0.3–4.9 px down to 0.07–0.3 px (median). When switched on (key C), the viewers, the cursor RA/Dec, the report and its JPL, SkyBoT, SatChecker and VSX lookups all use it. Your FITS files are never changed. See [Correct plate solution](MANUAL.md#correct-plate-solution).
- **New Blink window.** Speed control, live stretch, pause and step, zoom and pan, a frame list to leave out cloudy frames, the tracks of the last detection, and Annotate. Preview Frame, Manual Transient Inspection and Show full size now work the same way. See [Blink Selected](MANUAL.md#blink-selected).
- **Star Mask Explorer.** See the star mask exactly as a detection run builds it, over the median stack, and try other mask settings before you run. See [Star Mask Explorer](MANUAL.md#star-mask-explorer).

### The report

- Results come first: a session line (field, time span, frames, exposure, camera, plate solution), a **Jump to** bar with counts, and an **Overview** with a card for each kind of result. The processing diagnostics are collected at the end, collapsed.
- **The session picture:** the median stack with switchable layers for the detections, the star mask and the catalogue (Messier, NGC, IC and Sharpless objects with their outlines and common names, bright named stars and the best-known variable stars).
- **Field depth:** with the sky catalogue, the report says how faint the detection reached, as a Gaia G magnitude, for the session and frame by frame.
- The **Map of All Detections** names the well-known objects of the field.
- Variable-star candidates are matched with the session's catalogue when the report is written, and each candidate card says what VSX and Gaia know about the star: type, range, period, and how much of the period the session covers.
- The **Unclassified Transient Inspector** plays a short animation of the frames around each detection.
- Plainer wording throughout, and the configuration lists the settings that differ from the defaults.
- AI sections: Claude's **The Night, Retold** replaces Gemini's sections; Codex's **Signal Weave** stays.

### The application

- The **Main** tab follows the workflow in four groups (**1 Prepare**, **2 Astrometry**, **3 Inspect**, **4 Detect**), each with a status line. A greyed-out button's tooltip says why it is disabled. **Auto-Tune Settings**, to the left of **Detect Moving Targets**, opens the Detection Settings and runs Auto-Tune. Shortcuts: Ctrl+D (detect), Ctrl+T (Auto-Tune), Ctrl+B (blink), Ctrl+P (preview).
- **Import Aligned Frames…** replaces the File menu, and you can drag a folder onto the window. Import checks that all frames of the folder belong together (same type, bit depth, size and layout).
- The window can be resized and remembers its size and position.
- **Detection Settings** has pages with search (Ctrl+F) and **Show only unsaved changes**. Settings are basic or expert: the fine-tuning settings are shown with **Show expert settings**. Changed settings are marked, with a reset button for each. Settings that only matter while a feature is on appear when you switch it on.
- **Quality Control** has a **Strictness** choice (Lenient, Normal, Strict) for leaving out frames that differ from the rest of the session.
- **Astrometry Config:** the ASTAP check also looks for the star database, the observing site is checked as you type, and **Fill Site from FITS Header** fills the fields. Astrometry.net submissions are private.
- **Image Stretch:** the preview shows the whole frame with zoom and histograms, and the stretch is shared with every viewer.
- A **User Manual** link and, when a newer release exists, a **New version** button at the right end of the tab bar.
- A new application icon for the windows, the taskbar and the macOS Dock.

### Detection changes

- Blank frames and failed registrations are left out at the quality stage, also in **Detect Iteratively**.
- Frames are no longer rejected for a shift of the sky background smaller than the frame noise. This happened on very stable nights and on sessions whose sky levels were equalised during stacking.
- The slow-mover settings no longer follow the detection settings; they are set on their own.
- **Edge Margin** is no longer a visible setting: the void check already keeps detections away from the frame edges.
- Plate solutions with TAN-SIP distortion terms are read in full. Before, stars in the corners of such fields could be placed several pixels off.

### Fixes

- Importing 32-bit FITS frames.
- **Fill Site from FITS Header** no longer crashes.
- The frame numbers in the Unclassified Transient Inspector are right when frames were rejected.
- Blink uses the session's plate solution when the blinked frames are not solved themselves.
- The command-line tool always exits when it finishes.
- On Linux and macOS, the `batchDetect` and `injectStars` launchers can be run directly (they were not marked executable).
- Preview Frame, Manual Transient Inspection, the Star Mask Explorer and Correct plate solution read frames whose image is not in the first part of the FITS file, as import and detection already did.
- If an optional part of the report (session picture, field depth, catalogue names on the map) cannot be made, the rest of the report is still written.

### Upgrading

- **Your saved detection settings** are kept. Because new settings were added, SpacePixels offers a one-time **Migrate profile** step when it loads them; it keeps a backup of the old file.
- **Profile names** changed: conservative, balanced and aggressive are now **Low**, **Medium** and **High**, and **Maximum** is new. The command line accepts both the new and the old names.
- **Command line:** `--auto-tune <low|medium|high|maximum>` and the new `--tuner <calibrated|legacy>`. The bundled `config/default_detection_profile.json` now lists every setting.
- Java 21 or newer is still required.

### Under the hood

- Detection engine: JTransient 1.0.2.
- Java API: `SpacePixelsPipelineRequest` takes the auto-tune algorithm (`autoTuneAlgorithm`). The Maven artifact now brings JTransient to API users at compile time, so the examples in [API.md](API.md) build with the SpacePixels dependency alone.
- The release archives include the license.

<sub>Gaia data: ESA/Gaia/DPAC. SIMBAD and VizieR: CDS, Strasbourg. VSX: AAVSO.</sub>
