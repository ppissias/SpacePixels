/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import java.util.List;

/** The tips of the "Did you know?" screen, each with the section of the manual that tells more. */
final class StartupTips {

    /** One tip: its text as label HTML, and the anchor of its manual section. */
    record Tip(String html, String manualSection) {
    }

    private static final String BLINK = "blink-selected";
    private static final String PREVIEW = "preview-frame";
    private static final String KNOWN_OBJECTS = "known-asteroids-and-comets";
    private static final String FAINT_MOVERS = "faint-movers-experimental";
    private static final String IDENTIFYING = "5-identifying-objects";
    private static final String STAR_MASK = "star-mask-explorer";
    private static final String ASTEROIDS_WINDOW = "asteroids--comets";
    private static final String LINKED_NIGHTS = "objects-seen-on-more-than-one-night";
    private static final String LIGHT_CURVES = "light-curves-of-moving-objects";
    private static final String ORBITS = "orbits-built-in-solver-and-optional-find_orb";
    private static final String QUALITY_CONTROL = "frame-quality-control";

    static final List<Tip> ALL = List.of(
            new Tip("After a detection, you can draw what it found on the frames themselves in <b>Preview Frame</b> and "
                    + "<b>Blink</b>. Use the eye buttons under <b>Report findings</b> in the side panel. The findings come "
                    + "back when you import the same folder again, so you can look at them at any time.", BLINK),
            new Tip("You can choose how the pictures in the report are stretched, under <b>Detection Settings → Report "
                    + "Visualization</b>. <b>Auto Stretch Black Sigma</b> and <b>White Sigma</b> have sliders, and a preview "
                    + "shows the effect on crops of your own frames.", "8-detection-settings-in-brief"),
            new Tip("You can export an animated GIF from <b>Blink</b>. Click <b>Export GIF…</b> (G) and drag a rectangle "
                    + "over the area you want. It keeps your zoom and stretch, and the tracks, variable stars and catalog "
                    + "annotations you have switched on.", BLINK),
            new Tip("Once a frame is plate-solved, <b>Fetch Sky Catalogue</b> downloads the stars, galaxies, nebulae, "
                    + "clusters and variable stars of your field. Switch on <b>Catalog annotations</b> (A) in <b>Preview "
                    + "Frame</b> or <b>Blink</b> to draw them on the frames.",
                    "annotate-stars-deep-sky-objects-and-variable-stars"),
            new Tip("SpacePixels searches for moving objects too faint to see in a single frame: <b>Search for faint "
                    + "movers</b> under <b>Detection Settings → Faint Movers (experimental)</b>. It adds up the frames along "
                    + "many trial motions, so a faint mover builds up while the noise does not. It is on by default, takes "
                    + "extra time, and its candidates are unverified; switch it off there for quicker runs.", FAINT_MOVERS),
            new Tip("You can let <b>Automatic fastest motion</b> size the faint-mover search to your exposures, or set "
                    + "<b>Slowest motion</b>, <b>Fastest motion</b> and a <b>Time limit</b> yourself. As a guide, main-belt "
                    + "asteroids typically move about 0.2 to 0.7 arcsec/min, Jupiter Trojans about 0.2 to 0.3, Kuiper belt "
                    + "objects about 0.05, and near-Earth objects from about 1 to tens of arcsec/min. A higher maximum costs "
                    + "much more time: roughly the square of the speed.", FAINT_MOVERS),
            new Tip("A compatible OpenCL graphics card can speed up the faint-mover search's first pass and raise its limit "
                    + "from 100,000 to 400,000 trial motions. Leave <b>First pass compute</b> on <b>Auto (recommended)</b> "
                    + "under <b>Astrometry Config → GPU acceleration</b>, or pick a device and run <b>Test GPU</b>.", FAINT_MOVERS),
            new Tip("Plate-solving just one frame unlocks a lot in the report: sky coordinates on every track card, and "
                    + "buttons to identify objects with SkyBoT, JPL and SatChecker and to find them in Stellarium.", IDENTIFYING),
            new Tip("If your FITS headers do not record where you observed from, enter your observing site or IAU "
                    + "observatory code on the <b>Astrometry Config</b> tab. Predicted positions depend on it, so it lets the "
                    + "report identify tracks and faint movers.", IDENTIFYING),
            new Tip("<b>Fetch Asteroids and Comets</b> asks JPL which known objects cross your field, and where each one is "
                    + "at every frame's time. Switch on <b>Known asteroids &amp; comets</b> under <b>Catalog annotations</b> in "
                    + "<b>Preview Frame</b> or <b>Blink</b> to see them on the frames.", KNOWN_OBJECTS),
            new Tip("After <b>Fetch Asteroids and Comets</b>, the next report has an <b>Asteroids &amp; comets</b> "
                    + "section. It marks each track and faint mover Known, Check or No known match against the predictions, "
                    + "shows how far the known ones are from their predicted positions, and lists the known objects in the field.", KNOWN_OBJECTS),
            new Tip("After a detection, open <b>Asteroids &amp; Comets…</b> under the last report in <b>4 Detect</b>. It lists the "
                    + "report's tracks and faint movers with their identifications, measures their positions against Gaia DR3 stars, "
                    + "and writes an ADES file you can check and submit to the Minor Planet Center yourself.", ASTEROIDS_WINDOW),
            new Tip("In <b>Asteroids &amp; Comets</b>, <b>Identify all…</b> asks JPL and SkyBoT for every known asteroid and comet "
                    + "near each of your objects, without a magnitude limit, as MPChecker would. <b>Compare again</b> re-identifies "
                    + "the objects after a new <b>Fetch Asteroids and Comets</b>, without detecting again.", ASTEROIDS_WINDOW),
            new Tip("In <b>Asteroids &amp; Comets</b>, <b>Check MPC ITF…</b> downloads and caches the Minor Planet Center's "
                    + "Isolated Tracklet File so you can scan unmatched tracks offline in a few seconds. It shows which tracks "
                    + "were already reported on the same night as unlinked tracklets and flags linear candidates on nearby nights "
                    + "(±15 days).", ASTEROIDS_WINDOW),
            new Tip("Observed the same moving object on another night? In <b>Asteroids &amp; Comets</b>, use <b>Link another "
                    + "night…</b> to connect the two reports: <b>Compute orbit</b> then fits both nights together alongside one "
                    + "night alone, and ADES export can combine an unidentified object's nights into one tracklet.", LINKED_NIGHTS),
            new Tip("The <b>Light curve</b> tab of <b>Asteroids &amp; Comets</b> measures a moving object's brightness in every "
                    + "frame, with the fixed stars subtracted and comparison stars correcting for clouds and haze. Bins of 2 or 4 "
                    + "frames help with faint objects; the report draws a curve on every track and faint-mover card too.", LIGHT_CURVES),
            new Tip("In <b>Asteroids &amp; Comets</b>, <b>Compute orbit</b> fits a preliminary heliocentric orbit directly with "
                    + "SpacePixels' built-in solver (or optional local Find_Orb) and opens an interactive 3D orbit viewer. "
                    + "Drag to rotate, zoom, animate the object and planets in time, inspect the <b>Uncertainty cloud</b>, and "
                    + "link another night to see how much the orbit narrows.", ORBITS),
            new Tip("Double-click a frame in the main window's list to show it in <b>Preview Frame</b> and see what the "
                    + "detector picks up in it.", PREVIEW),
            new Tip("In <b>Preview Frame</b> you can try <b>Detection Sigma</b> and the other per-frame settings on the frame "
                    + "itself. The frame is detected again a moment later, and <b>Save to settings</b> keeps the values you "
                    + "like.", PREVIEW),
            new Tip("In <b>Blink</b>, pause and press S to leave a cloudy or trailed frame out of the loop. Stepping back and "
                    + "forth between two frames with ← and → is the surest way to confirm a faint mover.", BLINK),
            new Tip("<b>Auto-Tune Settings</b> (Ctrl+T) measures the noise and the faintest stars in your frames and sets the "
                    + "detection settings to match, so you do not have to guess them.", "step-3-auto-tune"),
            new Tip("The star mask hides the stable part of the sky, the stars and whatever else stays put, so that what is "
                    + "left of them in each frame is not taken for a moving object. <b>Test Star Mask…</b> on the Overview of "
                    + "<b>Detection Settings</b> shows the mask a run would use over the median stack of your frames. Try the "
                    + "star-mask settings that Auto-Tune chose, see how much of the sky they hide, and <b>Save to settings</b> "
                    + "keeps the ones you like.", STAR_MASK),
            new Tip("<b>Run quality control</b>, above the frame list, shows which frames a detection would reject and why, "
                    + "before you run one. Right-click a frame to see its measurements beside the session's limits.",
                    QUALITY_CONTROL),
            new Tip("<b>Run quality control</b> also checks star alignment across a 4 × 4 grid. It rejects frames with a sudden "
                    + "registration jump and shows <b>⚠ Registration</b> when stars creep at the edges; click it to inspect the "
                    + "drift map or add drifting corners to your excluded areas with <b>Exclude these areas</b>.",
                    QUALITY_CONTROL),
            new Tip("Blurred corners, bad columns or bright artifacts causing false detections? Open <b>Excluded areas</b> in "
                    + "<b>Preview Frame</b> and click <b>Draw areas</b> (or right-click a frame and choose <b>Exclude Areas…</b>) "
                    + "to leave them out of quality control, Auto-Tune and detection. Saved areas return whenever you reopen the "
                    + "session.", "excluded-areas"),
            new Tip("When you run <b>Fetch Sky Catalogue</b> on a plate-solved session, SpacePixels also fits a Gaia DR3 plate "
                    + "correction to refine sky coordinates across the field. Keep <b>Apply</b> ticked for more accurate "
                    + "annotations and to enable MPC astrometric reduction in <b>Asteroids &amp; Comets</b>.",
                    "correct-plate-solution"),
            new Tip("Open <b>Image Stretch…</b> under <b>3 Inspect</b> to compare the linear and stretched frame side by side "
                    + "with 16-bit histograms and clipping percentages. Adjustments update <b>Blink</b>, <b>Preview Frame</b> and "
                    + "<b>Manual Transient Inspection</b> live while leaving the linear pixels used for detection untouched.",
                    "image-stretch"),
            new Tip("Every HTML report includes a <b>Night &amp; Equipment</b> section tracking seeing, transparency and sky "
                    + "brightness through the session, alongside field maps of star size, shape, coma and flat-field illumination "
                    + "to help spot focus drift, tilt or optical spacing issues.", "night--equipment"),
            new Tip("Looking for an object that did not link into a track? Open the report's <b>Unclassified Transient "
                    + "Inspector</b> to browse every leftover detection coloured by time, filter by frame or peak sigma, and click "
                    + "any marker for a five-frame animation.", "unclassified-transient-inspector"),
            new Tip("Want larger text or a brighter workspace? Open the <b>Appearance</b> tab to switch between <b>Dark</b>, "
                    + "<b>High contrast dark</b> and <b>Light</b> themes, scale the interface from 100 % to 300 %, or load a custom "
                    + "FlatLaf style file.", "appearance-and-readable-text"),
            new Tip("Reports can be dark or light, with four text sizes: see <b>Theme</b> and <b>Text</b> in the report's "
                    + "toolbar. To go further, give them your own colours with <b>Report Style File</b> under <b>Report "
                    + "Visualization</b>, an expert setting.", "overview"),
            new Tip("On a streak track in the report, <b>Compare track with satellites</b> checks the trail against the "
                    + "predicted paths of satellites during each exposure, and draws them in the orientation of your "
                    + "frames.", IDENTIFYING),
            new Tip("<b>Stellarium Local</b> in the report slews a desktop Stellarium to a finding, so you can see what else "
                    + "was in that part of the sky.", "annex-local-stellarium-integration"),
            new Tip("SpacePixels also finds variable stars: variable-star photometry is on by default, under <b>Analyses in "
                    + "This Run</b> on the Overview of Detection Settings. It needs at least 20 frames after quality control.",
                    "variable-stars"),
            new Tip("Press ? in a frame viewer to list its keyboard shortcuts: for example T shows the moving object tracks and A the "
                    + "catalog annotations.", BLINK),
            new Tip("Too many frames to fit in memory? <b>Detect Iteratively</b> runs several spaced passes across the "
                    + "session instead of one large run.", "detect-iteratively-large-datasets"),
            new Tip("<b>Prepare Frames…</b> can calibrate and register a raw observing session with Siril, then import the "
                    + "result, so you can start from your light frames and masters.", "preparing-an-observing-session"));

    private StartupTips() {
    }
}
