# SpacePixels Release Notes

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

- The **Main** tab follows the workflow in four groups (**1 Prepare**, **2 Astrometry**, **3 Inspect**, **4 Detect**), each with a status line. A greyed-out button's tooltip says why it is disabled. Shortcuts: Ctrl+D (detect), Ctrl+B (blink), Ctrl+P (preview).
- **Import Aligned Frames…** replaces the File menu, and you can drag a folder onto the window. Import checks that all frames of the folder belong together (same type, bit depth, size and layout).
- The window can be resized and remembers its size and position.
- **Detection Settings** has pages with search (Ctrl+F) and **Show only unsaved changes**. Settings are basic or expert: the fine-tuning settings are shown with **Show expert settings**. Changed settings are marked, with a reset button for each. Settings that only matter while a feature is on appear when you switch it on.
- **Quality Control** has a **Strictness** choice (Lenient, Normal, Strict) for leaving out frames that differ from the rest of the session.
- **Astrometry Config:** the ASTAP check also looks for the star database, the observing site is checked as you type, and **Fill Site from FITS Header** fills the fields. Astrometry.net submissions are private.
- **Image Stretch:** the preview shows the whole frame with zoom and histograms, and the stretch is shared with every viewer.
- A **User Manual** link and, when a newer release exists, a **New version** button at the right end of the tab bar.

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
