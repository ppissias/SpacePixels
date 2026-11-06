# <img src="src/main/resources/eu/startales/spacepixels/gui/icons/spacepixels-128.png" alt="" width="40" height="40" /> SpacePixels

Find moving objects and brightness changes in your astrophotography exposures. SpacePixels analyzes aligned image sequences and produces an HTML report with tracks, image crops, animations and optional variable-star light curves.

Use the individual exposures before stacking. Rejection during stacking can suppress moving objects and brief events; SpacePixels helps you inspect those signals. Findings are candidates for review, not automatic confirmation of an object's identity or a new discovery.

[Download releases](https://github.com/ppissias/SpacePixels/releases) · [Website](https://startales.eu/SpacePixels/) · [User manual](MANUAL.md) · [Release notes](RELEASE_NOTES.md)

## Start here

- [Installation and launch](#installation-and-launch)
- [Your first detection](#your-first-detection)
- [What SpacePixels does](#what-spacepixels-does)
- [Report output](#report-output)
- [Video tutorials](#video-tutorials)
- [Screenshots and sample reports](#screenshots-and-sample-reports)
- [Acknowledgements](#acknowledgements)


## Installation and launch

### Running a release build

Choose an application package under **Assets** at [SpacePixels Releases](https://github.com/ppissias/SpacePixels/releases).

**Windows x64 installer:** download the EXE under Assets and run `SpacePixels-<installer-version>.exe`. It includes Java 21, installs for your Windows account and offers desktop and Start menu shortcuts. Launch the installed **SpacePixels** shortcut or `SpacePixels.exe`. See the [manual's installation instructions](MANUAL.md#installing) for unsigned-installer notices, upgrades and uninstalling.

**ZIP/TAR archives (Windows, Linux and macOS):** install Java 21 or newer, for example [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21) or [Oracle Java](https://www.oracle.com/java/technologies/downloads/), then unpack the complete archive and run from its folder:

- Windows: double-click `StartSpacePixels.bat`, or run `.\StartSpacePixels.bat` in PowerShell.
- Linux/macOS: `./StartSpacePixels`.

The **Source code** ZIP/TAR links are source snapshots, not runnable application packages.

<a id="typical-gui-workflow"></a>

## Your first detection

1. Start SpacePixels and choose **Import Aligned Frames…**. Select one folder of calibrated, registered exposures. Accept any required format conversion and import the resulting monochrome sequence.
2. Use **Blink Selected** to check alignment and image quality. If the exposures need calibration or registration first, see [Preparing an observing session](MANUAL.md#preparing-an-observing-session).
3. Optionally plate-solve one frame for sky coordinates and catalogue searches. Fetch the sky catalogue before detection if you want catalogue context in the report.
4. Run **Auto-Tune Settings** and start with **High**. Variable-star photometry is optional: enable it under **Detection Settings → Overview → Analyses in This Run**.
5. Click **Detect Moving Targets**, open the report when prompted, and inspect candidates in the animations and original frames.

The [illustrated manual](MANUAL.md#3-workflow-find-moving-objects-in-a-session) explains each step and how to interpret the results. Local detection works without an online solver; catalogue downloads and online identification require internet access.

For supported formats and detailed preparation rules, see [Input requirements](#input-requirements).

## What SpacePixels does

- Runs a standard multi-frame detection pipeline for moving targets, streak tracks, single-frame streaks, and bright anomalies.
- Reviews maximum-stack elongated morphology candidates using geometric shape and exact median-mask overlap filters; these are not confirmed moving tracks.
- Provides an iterative detection mode for large datasets and very slow targets.
- Auto-tunes the detection settings for each session. The calibrated tuner measures false detections and sensitivity on the session's own frames and offers four profiles: low, medium, high (the default) and maximum (as sensitive as possible, for small sensors or targeted searches for a faint object).
- Optionally measures the photometry of the stationary stars and reports variable-star candidates with light curves, readiness checks and a lookup in the AAVSO VSX catalogue. Switch it on under Analyses in This Run on the Detection Settings Overview; plate-solve one frame so candidates can be matched against VSX.
- Generates an HTML report with diagnostics, GIFs, geometric overlays, global maps and object-identification links.
- Supports manual transient inspection and frame-by-frame single-image detection preview, where the per-frame detection thresholds can be tried directly on the frame.
- Long jobs, such as a detection run or Auto-Tune, can be cancelled from their progress window.
- Blinks aligned frames for traditional visual inspection.
- Plate-solves images through ASTAP or Astrometry.net and uses WCS for RA/Dec overlays, SkyBoT/JPL moving-object identification, SatChecker satellite identification for streaks, and Stellarium Web context links.
- Offers linear monochrome conversion after importing color FITS, and separately batch-stretches entire datasets from Image Stretch.
- Includes headless utilities for batch detection.
- **Prepare Frames…** runs a separately installed Siril to calibrate and register exposures on private copies. The [manual](MANUAL.md#preparing-an-observing-session) explains the required inputs, and [how Siril is used](MANUAL.md#annex-preparing-data-with-siril) explains each option in the recipe.

## Report output

The standard pipeline exports an HTML session report plus PNG and GIF assets. Depending on the data and configuration, the report can include:

- A session header (field, time span, frames, camera, plate-solve status), a sticky section navigation and an overview whose cards jump to each result
- Target visualizations for moving tracks, streak tracks, single-frame streaks, and anomalies
- WCS-aware identification helpers: SkyBoT and JPL Small-Body Identification for moving-object tracks, SatChecker for streak tracks, and Stellarium Web sky-context links for both moving tracks and streaks
- Deep-stack anomalies and maximum-stack streak hints
- Global trajectory and transient maps
- Interactive unclassified-transient map with time-colored source footprints and markers, metadata, and a zoomed inspection view that animates the detection frame and the two nearest quality-checked frames on either side
- Variable-star photometry (when enabled): readiness verdict with its reasons, checks and star/frame funnels, noise model, per-frame diagnostics, a candidate table with "Identify all in VSX", candidate light curves, CSV exports
- Collapsed diagnostics: astrometric context, frame quality control and rejected frames, the detection configuration (values that differ from the defaults highlighted, exported as `detection_config.json`), star mask, dither and drift, extraction, star removal and track linking

## Video Tutorials

These videos show the versions available when recorded. Use the manual for current controls and installation instructions.

### Latest tutorial

<a href="https://youtu.be/LhA1izPFql0" target="_blank" rel="noopener noreferrer">
  <img src="docs/images/video-tutorial-latest-thumbnail.jpg" alt="Watch the latest SpacePixels full tutorial on YouTube" width="320"/>
</a>

[Watch the latest SpacePixels tutorial on YouTube](https://youtu.be/LhA1izPFql0)

### Earlier tutorial

<a href="https://youtu.be/7XBdYh0Wn7A?si=ymQmXHg92X651RmZ" target="_blank">
  <img src="docs/images/video-tutorial-thumbnail.jpg" alt="Watch the SpacePixels video tutorial on YouTube" width="320"/>
</a>

[Watch the earlier SpacePixels tutorial on YouTube](https://youtu.be/7XBdYh0Wn7A?si=ymQmXHg92X651RmZ)

## Screenshots and sample reports

Many thanks to Klangwolke at Cloudynights for providing amazing data for this report.

Many thanks to Cloudynights user TheStarsabove for providing data.

Many thanks to [Kumar](https://github.com/chvvkumar) for providing amazing data.

Many thanks to Austral Kiwi at Cloudynights for providing data.

**Check out sample reports.**

<a href="https://startales.eu/various_files/detections_20261015_144623/detection_report.html" target="_blank">Sample SpacePixels session report</a><br/>
<a href="https://startales.eu/various_files/detections_20261004_131720/detection_report_public.html" target="_blank">Earlier SpacePixels session report</a><br/>
<a href="https://startales.eu/various_files/detections_20260327_104057/detection_report.html" target="_blank">Sample SpacePixels report (Apophis)</a> <br/>
<a href="https://startales.eu/various_files/detections_20260327_104830/detection_report.html" target="_blank">Sample SpacePixels report</a> <br/>

![Main Interface](docs/images/user-interface-2026-10-01.png)
<br/>*The SpacePixels Main Workspace.*

![Sample Detection](docs/images/streak-track-1.png)
<br/>*Sample Detection - Streak track*

![Asteroid Bondia detection report](docs/images/Bondia-Screenshot.png)
<br/>*Asteroid Bondia - hiding in a 2017 dataset*

![Asteroid Bondia animation](docs/images/Bondia-moving_track_1_star_centric.gif)
<br/>*Asteroid Bondia - hiding in a 2017 dataset*

![Sample Detection](docs/images/Prokne-detection.png)
<br/>*Sample Detection - asteroid Prokne (data credit Klangwolke @ Cloudynights)*

![Sample Detection](docs/images/Prokne-moving_track_2_star_centric.gif)
<br/>*Sample Detection - asteroid Prokne (data credit Klangwolke @ Cloudynights)*

![Sample Detection](docs/images/Valeria-Screenshot.png)
<br/>*Sample Detection - asteroid Valeria (data credit Klangwolke @ Cloudynights)*

![Sample Detection](docs/images/Valeria-moving_track_1_star_centric.gif)
<br/>*Sample Detection - asteroid Valeria (data credit Klangwolke @ Cloudynights)*

![Sample Detection](docs/images/Sutoku-Screenshot.png)
<br/>*Sample Detection - asteroid Sutoku (data credit Klangwolke @ Cloudynights)*

![Sample Detection](docs/images/Sutoku-micro_drift_1_context.gif)
<br/>*Sample Detection - asteroid Sutoku (data credit Klangwolke @ Cloudynights)*

![Sample Detection](docs/images/variable-star-summary.png)
![Sample Detection](docs/images/variable-star-detection.png)
<br/>*Sample Detection - Variable star DK Per*

![Variable star TSVSC1 TN-N213111103-25-82-2 detection and light curve](docs/images/variable-star-tsvsc1-tn-n213111103-25-82-2.png)
<br/>*Sample Detection - Variable star TSVSC1 TN-N213111103-25-82-2 (W UMa-type eclipsing binary), showing 0.61 mag variation over 299 minutes.*

![Sample Detection](docs/images/aptrack_1_star_centric.gif)
<br/>*Sample Detection - Comet Africano 2019*

![Sample Detection](docs/images/aptrack_1_object_centric.gif)
<br/>*Sample Detection - Comet Africano 2019*

![Sample Detection](docs/images/track_5_object_centric.gif)
*Sample Detection - Apophis asteroid 2021 (raw images credit Duncan Warren), object-centric view.*

![Sample Detection](docs/images/track_5_star_centric.gif)
<br/>*Sample Detection - Apophis asteroid 2021 (raw images credit Duncan Warren), star-centric view.*

![Sample Detection](docs/images/streak_track_3_star_centric.gif)
<br/>*Sample Detection - streak track.*

![Sample Detection](docs/images/anomaly_17_context.gif)
<br/> *Sample Detection - single-frame anomaly candidate (cause unconfirmed), data credit cloudynights user TheStarsabove.*

## Input requirements

- Use individual, linear (unstretched), calibrated sub-exposures registered on the stars. Keep the zero-filled registration borders; do not crop every frame to the common overlap. Detection needs at least three usable frames after quality control; Auto-Tune needs at least four input frames. Variable-star photometry needs at least 20 frames after quality control and must pass additional readiness checks.
- All frames in a sequence must share the same dimensions, filename extension (case-insensitive), bit depth, sample scaling, colour layout, compression state and exposure time, and either all frames have timestamps or none. Import refuses a mixed folder. When the exposure times differ (exposures within 2 %, with a minimum tolerance of 0.1 s, count as the same), the GUI offers to copy the largest set of frames with one exposure time into a subfolder and import that.
- The GUI can import FITS directories (`.fit`, `.fits`, `.fts`). Directories containing XISF files and no native FITS files are converted to 16-bit monochrome FITS during import. Compressed `.fz` inputs are detected and can be decompressed into a new directory during import.
- The GUI can also standardize 32-bit FITS data, and signed or scaled 16-bit data (`BZERO` other than 32768), to standard unsigned 16-bit during import. When the conversion finds pixel values below zero, it asks whether to add an offset to every frame (recommended) or set them to 0.
- Automated detection requires 16-bit monochrome frames. If you import color images, use `Convert to Mono` before running the detection pipelines.
- The headless batch detector is stricter than the GUI: it expects uncompressed unsigned 16-bit monochrome FITS files (`BZERO` 32768, `BSCALE` 1).

## Command-line utilities

The Windows installer includes `spacepixels-batch.exe` and `injectStars.exe` beside `SpacePixels.exe`, with its example detection profile at `app/config/default_detection_profile.json`.

Archive distributions keep the generated application and tool launchers in `bin`, with top-level `StartSpacePixels` wrappers that delegate to the generated launcher. After building or unpacking an archive, you should find:

- Release root:
  - `StartSpacePixels.bat`
  - `StartSpacePixels`
- Windows:
  - `bin\SpacePixels.bat`
  - `bin\batchDetect.bat`
  - `bin\injectStars.bat`
  - `config\default_detection_profile.json`
- Linux/macOS:
  - `bin/SpacePixels`
  - `bin/batchDetect`
  - `bin/injectStars`
  - `config/default_detection_profile.json`

### Headless batch detection

Use the native Windows `spacepixels-batch.exe`, the archive's `batchDetect` launcher or the Gradle task.

From a Windows installation folder:

```powershell
.\spacepixels-batch.exe "C:\astro\sequence" "app\config\default_detection_profile.json" --auto-tune high
```

Archive launcher examples:

- Windows:
  - `.\bin\batchDetect.bat "C:\astro\sequence" "config\default_detection_profile.json"`
  - `.\bin\batchDetect.bat "C:\astro\sequence" "config\default_detection_profile.json" --auto-tune high`
- Linux/macOS:
  - `bin/batchDetect "/data/sequence" "config/default_detection_profile.json" --auto-tune high`

Gradle examples from a source checkout (the example paths contain no spaces):

```powershell
.\gradlew.bat batchDetect '-PbatchArgs=C:/astro/sequence src/dist/config/default_detection_profile.json --auto-tune high'
```

```bash
./gradlew batchDetect -PbatchArgs='/data/sequence src/dist/config/default_detection_profile.json --auto-tune high'
```

For paths containing spaces, prefer the packaged launchers above: quote each path as its own argument.

`batchDetect` accepts a SpacePixels detection-profile JSON and can optionally run Auto-Tune with `low`, `medium`, `high` or `maximum` (the earlier names conservative, balanced and aggressive still work).

With the Gradle task, `-PbatchMaxHeap=8g` sets a fixed JVM heap instead of the default of up to 80% of RAM. A tested session of 33 frames of 61 megapixels needed about 11 GB of heap; requirements vary with the enabled analyses and the data.

### Artificial star injection

Use the native Windows `injectStars.exe`, the archive's `injectStars` launcher or the Gradle task. From a Windows installation folder:

```powershell
.\injectStars.exe "C:\astro\sequence" 15 10.0 4500 4.0
```

Packaged launcher examples:

- Windows:
  - `.\bin\injectStars.bat C:\astro\sequence 15 10.0 4500 4.0`
- Linux/macOS:
  - `bin/injectStars /data/sequence 15 10.0 4500 4.0`

Gradle task examples:

- Windows:
  - `.\gradlew.bat injectStars -PinjArgs="C:\astro\sequence 15 10.0 4500 4.0"`
- Linux/macOS:
  - `./gradlew injectStars -PinjArgs="/data/sequence 15 10.0 4500 4.0"`

This utility injects synthetic moving stars into a FITS sequence for testing and validation.

## Java API

The supported Java integration surface is [`eu.startales.spacepixels.api`](src/main/java/eu/startales/spacepixels/api).

Use `eu.startales.spacepixels:spacepixels:<version>` for headless embedding with its declared dependencies. This sole Maven artifact includes the API, preparation, detection, report resources and CLI tools, without GUI classes or FlatLaf. There is no separate core project/publication. Desktop distributions still include an unpublished GUI companion and all application dependencies. Java 21 and headless AWT (`java.desktop`) remain required.

For setup, request/result behavior, and usage examples, see [API.md](API.md).

## For developers

See [DEVELOPER.md](DEVELOPER.md) for source builds, tests, working rules and code organization; [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md) for architecture; and [PUBLISHING.md](PUBLISHING.md) for desktop packaging and Maven publication. Proposed features and design changes are tracked separately in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) and [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md).

The [research evidence index](docs/research/README.md) links dated slow-mover investigations, numerical tables, figures and delivered engine/application code history.

Report reproducible problems through [GitHub Issues](https://github.com/ppissias/SpacePixels/issues). Include the application version and relevant error details, removing private paths and observing-site information before sharing.

### Running from source

Use JDK 21. From the project root:

- Windows: `.\gradlew.bat run`
- Linux/macOS: `./gradlew run`

To generate a local distribution with launch scripts:

- Windows: `.\gradlew.bat installDist`
- Linux/macOS: `./gradlew installDist`

On Windows x64, `.\gradlew.bat windowsAppImage` builds an application folder with its own runtime; distribute the entire `build/windows/image/SpacePixels` folder. `.\gradlew.bat windowsInstaller` wraps it in an EXE installer and also requires WiX 3. See [PUBLISHING.md](PUBLISHING.md#windows-installer).

### Console output

SpacePixels prints only problems to the console; the batch detector also prints the path of the report it wrote. For diagnostics (progress, per-frame statistics, timings and the detection engine's debug output), start the JVM with `-Dspacepixels.debug=true`. Archive launchers accept `JAVA_OPTS=-Dspacepixels.debug=true`; native Windows launchers use `java-options=-Dspacepixels.debug=true` under `[JavaOptions]` in `app/SpacePixels.cfg` or `app/spacepixels-batch.cfg`. From source, add `-PspacepixelsDebug` to the Gradle command.

## JTransient engine documentation

SpacePixels uses the [JTransient Engine](https://github.com/ppissias/JTransient) for the core extraction, purification, and track-linking pipeline.

For the underlying detection logic and the meaning of the engine configuration options, see:

- [JTransient repository](https://github.com/ppissias/JTransient)
- [JTransient algorithm overview](https://github.com/ppissias/JTransient/blob/main/ALGORITHM.md)
- [JTransient configuration reference](https://github.com/ppissias/JTransient/blob/main/CONFIG.md)
- [JTransient Auto-Tune](https://github.com/ppissias/JTransient/blob/main/ALGORITHM.md#auto-tuning)
- [JTransient variable-star photometry](https://github.com/ppissias/JTransient/blob/main/ALGORITHM.md#variable-star-photometry)

## Acknowledgements

### Astronomical Services & Data Providers

These services supply catalogue data, predictions and optional plate solutions for sky annotations, diagnostics and identification checks in SpacePixels.

#### <img src="src/main/resources/eu/startales/spacepixels/gui/logos/nasa.png" alt="NASA logo" width="48" height="40" /> NASA JPL (Jet Propulsion Laboratory)

- [SB-Ident API](https://ssd-api.jpl.nasa.gov/doc/sb_ident.html): Searches for known asteroids and comets in the field and around report findings.
- [Horizons System](https://ssd.jpl.nasa.gov/horizons/): Supplies observer ephemerides for known-object paths and exposure-midpoint annotations, including positional uncertainties when available. [Data citation guidance](https://ssd.jpl.nasa.gov/about/).

#### <img src="src/main/resources/eu/startales/spacepixels/gui/logos/esa.png" alt="ESA logo" width="72" height="28" /> European Space Agency (ESA)

- [Gaia DR3](https://www.cosmos.esa.int/web/gaia): Star positions, proper motions, *G* magnitudes and *BP−RP* colours support sky overlays, plate-solution correction, field-depth and observing-condition diagnostics. Queried through VizieR TAP, with the ESA Gaia Archive as fallback for the main star catalogue.

Credit: **ESA/Gaia/DPAC**. Gaia data are processed by the [Data Processing and Analysis Consortium](https://www.cosmos.esa.int/web/gaia/dpac/consortium); national institutions support DPAC through the Gaia Multilateral Agreement. Requested references: [Gaia Collaboration (2016), mission paper](https://doi.org/10.1051/0004-6361/201629272), and [Gaia Collaboration (2023), DR3 release paper](https://doi.org/10.1051/0004-6361/202243940). [Gaia citation guidance](https://gea.esac.esa.int/archive/documentation/GDR3/Miscellaneous/sec_credit_and_citation_instructions/).

#### <img src="src/main/resources/eu/startales/spacepixels/gui/logos/cds.png" alt="CDS logo" width="67" height="40" /> CDS (Centre de Données astronomiques de Strasbourg)

- [SIMBAD](https://simbad.cds.unistra.fr/simbad/): Supplies deep-sky objects and named bright stars for sky annotations; reports also link to object searches. [Wenger et al. (2000)](https://doi.org/10.1051/aas:2000332).
- [VizieR](https://vizier.cds.unistra.fr/): TAP access to Gaia DR3 (*I/355/gaiadr3*) and AAVSO VSX (*B/vsx/vsx*), including faint Gaia samples for field-depth estimates. [Ochsenbein, Bauer & Marcout (2000)](https://doi.org/10.1051/aas:2000169); [service DOI](https://doi.org/10.26093/cds/vizier).

#### <img src="src/main/resources/eu/startales/spacepixels/gui/logos/astrometry.png" alt="Astrometry.net logo" width="72" height="22" /> Astrometry.net

- [Nova Astrometry.net](https://nova.astrometry.net/): Optional online blind plate solving through JPlateSolve, using the user's configured account. [Lang et al. (2010)](https://doi.org/10.1088/0004-6256/139/5/1782).

#### Other Services & Data Providers

- **AAVSO (American Association of Variable Star Observers)** — [International Variable Star Index (VSX)](https://vsx.aavso.org/): Variable-star positions, classifications, periods and brightness ranges, fetched via CDS VizieR for annotations and report cross-checks. Details open on AAVSO's site. [Watson et al. (2006–), catalogue B/vsx](https://cdsarc.cds.unistra.fr/viz-bin/cat/B/vsx).
- **IAU CPS (Centre for the Protection of the Dark and Quiet Sky from Satellite Constellation Interference)** — [SatChecker](https://satchecker.cps.iau.org/): On-demand satellite-pass and ephemeris queries for report lookups and measured streak speed/direction comparisons. [API documentation](https://satchecker.readthedocs.io/en/latest/).

#### External Report Links

These open additional checks or information in a browser:

- **IMCCE / Observatoire de Paris** — [SkyBoT](https://ssp.imcce.fr/webservices/skybot/): Cone searches at a finding's sky position and observation epoch. Reference: Berthier et al. (2006), *ADASS XV*, ASP Conf. Ser. 351, 367.
- **NASA JPL** — [Small-Body Database (SBDB)](https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html): Object details for asteroid and comet lookup results.
- [CelesTrak](https://celestrak.org/) & [N2YO](https://www.n2yo.com/): Catalogue and satellite-information links for returned NORAD identities.
- [Stellarium Web](https://stellarium-web.org/): Sky views centred on report findings. Local Stellarium control is described in Software & Utilities.

### Software & Utilities

#### Astronomical Software & Utilities

These optional tools are installed separately and used when configured:

<img src="src/main/resources/eu/startales/spacepixels/gui/logos/astap.png" alt="ASTAP logo" width="40" height="40" />

**ASTAP** by Han Kleijn — The Astrometric STAcking Program supplies SpacePixels' local plate-solving backend through JPlateSolve ([ASTAP](https://www.hnsky.org/astap.htm)); its local star database is required for solving.

<img src="src/main/resources/eu/startales/spacepixels/gui/logos/siril.png" alt="Siril logo" width="40" height="40" />

**Siril** by the Siril Development Team — The command-line backend for observing-session preparation: calibration, demosaicing, registration and optional reference stacking, according to the selected recipe ([Siril](https://siril.org/)).

<img src="src/main/resources/eu/startales/spacepixels/gui/logos/stellarium.png" alt="Stellarium logo" width="40" height="40" />

**Stellarium** by the Stellarium Team — Report buttons send sky position, time, observer location and field of view to the local planetarium's Remote Control plugin ([Stellarium](https://stellarium.org/)).

#### Open Source Software & Libraries

SpacePixels is built with the following open-source Java libraries and components:

- **FlatLaf** by FormDev Software — Desktop Look and Feel, themes and interface scaling ([FlatLaf](https://www.formdev.com/flatlaf/)).
- **nom.tam.fits** — Java library for Flexible Image Transport System (FITS) I/O ([nom-tam-fits](https://nom-tam-fits.github.io/nom-tam-fits/)).
- **Gson** by Google — Java library for JSON serialization and parsing ([github.com/google/gson](https://github.com/google/gson)).
- **JTransient & JPlateSolve** by Petros Pissias — Detection, tracking and photometry engine; ASTAP and Astrometry.net plate-solving integration ([JTransient](https://github.com/ppissias/JTransient), [JPlateSolve](https://github.com/ppissias/JPlateSolve)).
- **Java / OpenJDK** — Java 21 runtime and Swing desktop; Windows packages bundle the runtime ([OpenJDK](https://openjdk.org/)).


#### Development & Packaging

[Gradle](https://gradle.org/) builds the application; [JUnit](https://junit.org/junit4/) and [AssertJ Swing (Hiroshi Miura's fork)](https://codeberg.org/miurahr/assertj-swing) support tests. JDK jpackage/jlink and [WiX Toolset](https://wixtoolset.org/) create the Windows installer.

#### AI Coding Assistants

Since 2026, the development of SpacePixels and its libraries has been supported by these AI coding assistants:

- <img src="src/main/resources/eu/startales/spacepixels/gui/logos/claude.png" alt="Claude logo" width="20" height="20" /> **Claude** by Anthropic ([Claude](https://www.anthropic.com/claude))
- <img src="src/main/resources/eu/startales/spacepixels/gui/logos/openai.png" alt="OpenAI logo" width="20" height="20" /> **Codex** by OpenAI ([Codex](https://openai.com/codex/))
- <img src="src/main/resources/eu/startales/spacepixels/gui/logos/gemini.png" alt="Gemini logo" width="20" height="20" /> **Gemini** by Google ([Gemini](https://gemini.google.com/))
- <img src="src/main/resources/eu/startales/spacepixels/gui/logos/grok.png" alt="Grok logo" width="72" height="20" /> **Grok** by xAI ([Grok](https://x.ai/grok))

Logos are trademarks of their respective owners.

### Scientific References & Algorithms

References for methods and standards implemented in SpacePixels and its libraries. Service and catalogue credits are listed under [Astronomical Services & Data Providers](#astronomical-services--data-providers). Each entry identifies its contribution; related literature is omitted unless it supplies an additional method used here. Implementation choices and empirical selection thresholds are described in the [JTransient algorithm documentation](https://github.com/ppissias/JTransient/blob/main/ALGORITHM.md).

#### Digital Tracking & Faint-Mover Detection

- **Heinze, Metchev & Trollo (2015)** — *Digital Tracking Observations Can Discover Asteroids 10 Times Fainter than Conventional Searches*, [AJ 150, 125](https://doi.org/10.1088/0004-6256/150/4/125) ([author preprint](https://arxiv.org/abs/1508.01599)).
  Method background for `FaintMoverSearch`: constant-velocity shift-and-stack detection, motion-grid spacing tied to image quality, and checking support in separate time subsets.

The engine's residual preparation, empirical scores and selection rules are described in its algorithm documentation. Published survey sensitivity is not a SpacePixels performance claim. The [investigation archive](https://github.com/ppissias/JTransient/blob/main/docs/research/slow-movers/README.md) keeps experimental alternatives separate from delivered methods.

#### Variable-Star Photometry

- **Honeycutt (1992)** — *CCD Ensemble Photometry on an Inhomogeneous Set of Exposures*, [PASP 104, 435–440](https://doi.org/10.1086/133015).
  JTransient's `EnsembleSolver` uses the star-magnitude plus frame-zero-point model for differential light curves, adding robust Cauchy reweighting and spatial transparency planes.
- **Stetson (1996)** — *On the Automatic Determination of Light-Curve Parameters for Cepheid Variables*, [PASP 108, 851–876](https://doi.org/10.1086/133808).
  JTransient's `VariabilityScorer` adapts the J statistic to consecutive usable residuals with a magnitude-neighbour noise estimate, alongside excess scatter, when selecting variable-star candidates.
- **Howell (1989)** — *Two-dimensional aperture photometry: Signal-to-noise ratio of point-source observations and optimal data-extraction techniques*, [PASP 101, 616–622](https://doi.org/10.1086/132477).
  The sky-background and finite-annulus terms inform `ApertureMeasurer`'s flux errors; JTransient also measures an empirical blank-sky correction. These are background-noise estimates, rather than the complete source-photon/read-noise CCD equation.

#### Astrometry & World Coordinate Systems (WCS)

- **Greisen & Calabretta (2002)** — *Representations of world coordinates in FITS*, [A&A 395, 1061–1075](https://doi.org/10.1051/0004-6361:20021326).
  `WcsCoordinateTransformer` reads reference pixels, coordinate values and CD or PC/CDELT matrices to map image pixels to intermediate coordinates.
- **Calabretta & Greisen (2002)** — *Representations of celestial coordinates in FITS*, [A&A 395, 1077–1122](https://doi.org/10.1051/0004-6361:20021327).
  `WcsCoordinateTransformer` uses the TAN (gnomonic) celestial projection for sky annotations, catalogue matching and report coordinates.
- **Shupe et al. (2005)** — *The SIP Convention for Representing Distortion in FITS Image Headers*, [ASP Conf. Ser. 347, 491–495](https://authors.library.caltech.edu/records/fqnyw-qdw79).
  `WcsCoordinateTransformer` applies supplied SIP polynomial coefficients, with inverse iteration, in pixel/sky transformations for annotations and report coordinates.
- **Meeus (1998)** — [*Astronomical Algorithms*, 2nd ed.](https://shopatsky.com/products/astronomical-algorithms-2nd-edition), Willmann-Bell.
  The report's local sidereal-time polynomial and equatorial-to-horizontal conversion follow the conventional formulae described in this reference; displayed Alt/Az uses UTC as an approximation to UT1.
