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
            new Tip("You choose what the faint-mover search looks for: <b>Slowest motion</b>, <b>Fastest motion</b> and a "
                    + "<b>Time limit</b>. As a guide, main-belt asteroids typically move about 0.2 to 0.7 arcsec/min, Jupiter "
                    + "Trojans about 0.2 to 0.3, Kuiper belt objects about 0.05, and near-Earth objects from about 1 to tens "
                    + "of arcsec/min. A higher maximum costs much more time: roughly the square of the speed.", FAINT_MOVERS),
            new Tip("Plate-solving just one frame unlocks a lot in the report: sky coordinates on every track card, and "
                    + "buttons to identify objects with SkyBoT, JPL and SatChecker and to find them in Stellarium.", IDENTIFYING),
            new Tip("If your FITS headers do not record where you observed from, enter your observing site or IAU "
                    + "observatory code on the <b>Astrometry Config</b> tab. Predicted positions depend on it, so it lets the "
                    + "report identify tracks and faint movers.", IDENTIFYING),
            new Tip("<b>Fetch Asteroids and Comets</b> asks JPL which known objects cross your field, and where each one is "
                    + "at every frame's time. Switch on <b>Known asteroids &amp; comets</b> under <b>Catalog annotations</b> in "
                    + "<b>Preview Frame</b> or <b>Blink</b> to see them on the frames.", KNOWN_OBJECTS),
            new Tip("After <b>Fetch Asteroids and Comets</b>, the next report has a <b>Known asteroids &amp; comets</b> "
                    + "section. It compares your tracks with the predictions by position and speed, and lists the known "
                    + "objects in the field.", KNOWN_OBJECTS),
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
                    "frame-quality-control"),
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
