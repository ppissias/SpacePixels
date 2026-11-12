package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import org.junit.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;

public class SkyAnnotationsTest {

    @Test
    public void viewersListenOnlyToTheirOwnSessionAndDetachIndependently() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var firstModel = new eu.startales.spacepixels.session.SkyCatalogueModel();
            var secondModel = new eu.startales.spacepixels.session.SkyCatalogueModel();
            SkyAnnotations first = new SkyAnnotations(new FrameView(), new ViewerLayerState(null));
            SkyAnnotations second = new SkyAnnotations(new FrameView(), new ViewerLayerState(null));
            var firstChanges = new java.util.concurrent.atomic.AtomicInteger();
            var secondChanges = new java.util.concurrent.atomic.AtomicInteger();
            first.setOnSolutionChanged(firstChanges::incrementAndGet);
            second.setOnSolutionChanged(secondChanges::incrementAndGet);
            first.bind(firstModel);
            second.bind(secondModel);
            first.attach(null);
            second.attach(null);
            try {
                assertEquals(2, firstModel.listenerCount());
                assertEquals(2, secondModel.listenerCount());
                firstModel.setCorrection(null);
                assertEquals(1, firstChanges.get());
                assertEquals(0, secondChanges.get());
                first.detach();
                assertEquals(0, firstModel.listenerCount());
                assertEquals(2, secondModel.listenerCount());
                secondModel.setCorrection(null);
                assertEquals(1, secondChanges.get());
            } finally {
                first.detach();
                second.detach();
            }
            assertEquals(0, secondModel.listenerCount());
        });
    }

    /** Catalog annotations switched off disables its rows, settings menu and correction, and C does nothing. */
    @Test
    public void theSectionSwitchDisablesItsControls() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var model = new eu.startales.spacepixels.session.SkyCatalogueModel();
            var correction = new eu.startales.spacepixels.platesolve.PlateSolutionCorrection();
            correction.global = new eu.startales.spacepixels.platesolve.PlateSolutionCorrection.Polynomial();
            model.setCorrection(correction);
            model.set(new SkyCatalogue());
            ViewerLayerState state = new ViewerLayerState(null);
            state.setVisible(ViewerLayer.CATALOGUE, true);
            SkyAnnotations sky = new SkyAnnotations(new FrameView(), state);
            sky.bind(model);
            sky.attach(null);
            sky.showFrame(null);
            try {
                var box = find(sky.section(), javax.swing.JCheckBox.class, "Align markers to corrected plate solution");
                var eye = find(sky.section(), javax.swing.JCheckBox.class, "Show known stars");
                var menu = find(sky.section(), javax.swing.JButton.class, "Catalog annotation settings");
                assertTrue(box.isEnabled());
                assertTrue(eye.isEnabled());
                assertTrue(menu.isEnabled());

                state.setVisible(ViewerLayer.CATALOGUE, false);
                sky.layersChanged();
                assertFalse(box.isEnabled());
                assertFalse(eye.isEnabled());
                assertFalse(menu.isEnabled());
                assertTrue(box.getToolTipText(), box.getToolTipText().contains("switch on Catalog annotations first"));
                boolean corrected = model.isCorrectionEnabled();
                sky.toggleCorrection();
                assertEquals("C does nothing while the section is off", corrected, model.isCorrectionEnabled());

                state.setVisible(ViewerLayer.CATALOGUE, true);
                sky.layersChanged();
                assertTrue(box.isEnabled());
                assertTrue(eye.isEnabled());
                assertTrue(menu.isEnabled());
            } finally {
                sky.detach();
            }
        });
    }

    /** The control of this type with this visible text or accessible name. */
    private static <C extends javax.swing.AbstractButton> C find(java.awt.Container root, Class<C> type, String name) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                C control = type.cast(child);
                if (name.equals(control.getText()) || name.equals(control.getAccessibleContext().getAccessibleName())) return control;
            }
            if (child instanceof java.awt.Container container) {
                try {
                    return find(container, type, name);
                } catch (AssertionError notInThisBranch) {
                    // The next branch can hold it.
                }
            }
        }
        throw new AssertionError("no control named " + name);
    }

    @Test
    public void wcsHeaderReplacementUpdatesTableAndViewerReadout() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var unsolved = new eu.startales.spacepixels.fits.FitsFileInformation(
                    "frame_01.fit", "frame_01.fit", true, 2000, 1600);
            var second = new eu.startales.spacepixels.fits.FitsFileInformation(
                    "frame_02.fit", "frame_02.fit", true, 2000, 1600);
            var tableModel = new FitsFileTableModel(new eu.startales.spacepixels.fits.FitsFileInformation[]{unsolved, second});
            assertEquals("No", tableModel.getValueAt(0, FitsFileTableModel.COL_SOLVED));

            SkyAnnotations sky = new SkyAnnotations(new FrameView(), new ViewerLayerState(null));
            ViewerSupport.CursorReadout cursorReadout = new ViewerSupport.CursorReadout();
            sky.setOnSolutionChanged(cursorReadout::forgetSolutions);
            var files = new eu.startales.spacepixels.fits.FitsFileInformation[]{unsolved, second};
            cursorReadout.reset(files, sky::solutionFor);
            sky.attach(files);

            var displayFrame = eu.startales.spacepixels.fits.BlinkSequence.fromMono(unsolved, new short[1600][2000]);
            var secondDisplay = eu.startales.spacepixels.fits.BlinkSequence.fromMono(second, new short[1600][2000]);
            java.awt.Point center = new java.awt.Point(1000, 800);
            String before = cursorReadout.describe(displayFrame, center);
            assertTrue(before, !before.contains("RA "));

            var solved = unsolved.withFitsHeader(header());
            assertEquals(0, tableModel.replaceFile(unsolved, solved));
            assertEquals("Yes", tableModel.getValueAt(0, FitsFileTableModel.COL_SOLVED));
            assertEquals(solved, tableModel.getFitsFileAt(0));

            sky.replaceFrame(unsolved, solved);
            String afterSolvedFrame = cursorReadout.describe(displayFrame.withInfo(solved), center);
            assertTrue(afterSolvedFrame, afterSolvedFrame.contains("RA ") && afterSolvedFrame.contains("Dec "));
            String afterAlignedSecond = cursorReadout.describe(secondDisplay, center);
            assertTrue("aligned frames in the viewer also resolve the new WCS solution",
                    afterAlignedSecond.contains("RA ") && afterAlignedSecond.contains("Dec "));
            sky.detach();
        });
    }

    @Test
    public void placesAsteroidsWhereTheyArePredictedAtTheMiddleOfEachExposure() {
        long start = Instant.parse("2026-03-10T02:00:00Z").toEpochMilli();
        long hour = 3_600_000L;
        eu.startales.spacepixels.solarsystem.SolarSystemObjects objects = new eu.startales.spacepixels.solarsystem.SolarSystemObjects();
        // Moving north at 600 px an hour (0.25° at 1.5″ per pixel), from 300 px south of the centre.
        eu.startales.spacepixels.solarsystem.SolarSystemObjects.Body asteroid = body("194 Prokne (A879 FA)", false, "JPL#172");
        for (int step = 0; step <= 2; step++) {
            asteroid.samples.add(solarSample(start + step * hour, 150.0, 20.0 - 300 * SCALE_DEG + step * 600 * SCALE_DEG, 14.1, 0.005));
        }
        // Far north of the frame all night: neither placed nor drawn.
        eu.startales.spacepixels.solarsystem.SolarSystemObjects.Body far = body("(2014 QV125)", false, null);
        far.samples.add(solarSample(start, 150.0, 21.0, 20.0, null));
        far.samples.add(solarSample(start + 2 * hour, 150.0, 21.0, 20.0, null));
        objects.bodies.add(asteroid);
        objects.bodies.add(far);

        // A 10-minute exposure starting 25 minutes in: the middle is 30 minutes in, 300 px north of the start.
        List<SkyMark> marks = SkyAnnotations.projectSolarSystem(objects, TRANSFORMER, 2000, 1600, start + 25 * 60_000L, 10 * 60_000L);
        assertEquals(1, marks.size());
        SkyMark mark = marks.get(0);
        assertEquals(SkyMark.Kind.SOLAR_SYSTEM, mark.kind);
        assertEquals(999.5, mark.x, 1e-3);
        assertEquals(799.5, mark.y, 1e-3);
        assertEquals("194 Prokne 14.1", mark.label);
        assertEquals("the exposure's start", 749.5, mark.trail[1], 1e-3);
        assertEquals("the exposure's end", 849.5, mark.trail[3], 1e-3);
        assertEquals("the path over the session", 6, mark.path.length);
        assertTrue(mark.description, mark.description.contains("V 14.1") && mark.description.contains("JPL#172")
                && mark.description.contains("within 1″"));

        // Nearly two hours later the object has left the frame, but its path still crosses it.
        long later = start + 2 * hour - 60_000L;
        SkyMark gone = SkyAnnotations.projectSolarSystem(objects, TRANSFORMER, 2000, 1600, later, 0).get(0);
        assertTrue("off the frame: the path only", !gone.isPlaced() && gone.path != null);
        assertTrue(gone.description, gone.description.contains("elsewhere"));
        SkyMark unknownTime = SkyAnnotations.projectSolarSystem(objects, TRANSFORMER, 2000, 1600, 0, 0).get(0);
        assertTrue("a frame without a time shows the path only", !unknownTime.isPlaced() && unknownTime.path != null);
    }

    /** Extended clock ephemerides do not extend the viewer's session path or its fallback magnitude epoch. */
    @Test public void clockPaddingDoesNotExtendTheDisplayedSessionPath() {
        long start = Instant.parse("2026-03-10T02:00:00Z").toEpochMilli();
        var objects = new eu.startales.spacepixels.solarsystem.SolarSystemObjects();
        objects.startMillis = start; objects.endMillis = start + 3_600_000;
        var asteroid = body("Clock coverage control", false, "synthetic");
        for (int minutes : new int[]{-840, -10, 70, 900}) asteroid.samples.add(solarSample(start + minutes * 60_000L,
                150, 20 + .5 * minutes * SCALE_DEG, minutes < -10 ? 5.0 : 14.0, .01));
        objects.bodies.add(asteroid);
        var mark = SkyAnnotations.projectSolarSystem(objects, TRANSFORMER, 2000, 1600, 0, 0).getFirst();
        assertEquals(4, mark.path.length);
        assertEquals(799.5 - 5, mark.path[1], .001);
        assertEquals(799.5 + 35, mark.path[3], .001);
        assertTrue(mark.label, mark.label.endsWith("14.0"));
    }

    private static eu.startales.spacepixels.solarsystem.SolarSystemObjects.Body body(String name, boolean comet, String solution) {
        var body = new eu.startales.spacepixels.solarsystem.SolarSystemObjects.Body();
        body.name = name;
        body.comet = comet;
        body.orbitSolution = solution;
        return body;
    }

    private static eu.startales.spacepixels.solarsystem.SolarSystemObjects.Sample solarSample(long time, double ra, double dec,
            Double magnitude, Double sigma) {
        var sample = new eu.startales.spacepixels.solarsystem.SolarSystemObjects.Sample();
        sample.timeMillis = time;
        sample.ra = ra;
        sample.dec = dec;
        sample.magnitude = magnitude;
        sample.raSigmaArcsec = sigma;
        sample.decSigmaArcsec = sigma;
        return sample;
    }

    /** 1.5″ per pixel, north up and east left, centre at pixel (999.5, 799.5) of a 2000 × 1600 frame. */
    private static final double SCALE_DEG = 1.5 / 3600;
    private static final WcsCoordinateTransformer TRANSFORMER = WcsCoordinateTransformer.fromHeader(header());

    @Test
    public void placesObjectsOnTheFrameAndLeavesOutTheRest() {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.stars.add(star(150.0, 20.0, 12.3, null, null));
        catalogue.stars.add(star(152.0, 20.0, 9.0, null, null));
        SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
        variable.name = "RR Test";
        variable.ra = 150.0;
        variable.dec = 20.0 + 100 * SCALE_DEG;
        catalogue.variables.add(variable);

        List<SkyMark> marks = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, 0, -1);
        assertEquals(2, marks.size());
        SkyMark variableMark = marks.get(0);
        assertEquals(SkyMark.Kind.VARIABLE, variableMark.kind);
        assertEquals(999.5, variableMark.x, 1e-6);
        assertEquals(799.5 + 100, variableMark.y, 1e-3);
        SkyMark starMark = marks.get(1);
        assertEquals(SkyMark.Kind.STAR, starMark.kind);
        assertEquals(999.5, starMark.x, 1e-6);
        assertEquals(799.5, starMark.y, 1e-6);
        assertEquals("12.3", starMark.label);
    }

    @Test
    public void movesGaiaStarsToTheDateOfTheFrame() {
        SkyCatalogue catalogue = new SkyCatalogue();
        // 1″ per year north and 1″ per year east (on the sky).
        catalogue.stars.add(star(150.0, 20.0, 10, 1000.0, 1000.0));
        long tenYearsLater = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli();
        SkyMark mark = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, tenYearsLater, -1).get(0);
        double years = (tenYearsLater - SkyCatalogue.GAIA_EPOCH_MILLIS) / (365.25 * 86_400_000.0);
        // East is towards smaller x, north towards larger y, 1.5″ per pixel.
        assertEquals(999.5 - years / 1.5, mark.x, 0.01);
        assertEquals(799.5 + years / 1.5, mark.y, 0.01);
        // An unknown date leaves the stars at the Gaia epoch.
        mark = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, 0, -1).get(0);
        assertEquals(999.5, mark.x, 1e-6);
    }

    @Test
    public void drawsDeepSkyObjectsWithTheirSizeAndOrientation() {
        SkyCatalogue catalogue = new SkyCatalogue();
        SkyCatalogue.DeepSkyObject galaxy = new SkyCatalogue.DeepSkyObject();
        galaxy.name = "M 81";
        galaxy.type = "Sy2";
        galaxy.ra = 150.0;
        galaxy.dec = 20.0;
        galaxy.majorArcmin = 10.0;
        galaxy.minorArcmin = 5.0;
        galaxy.angleDeg = 0.0;
        catalogue.deepSky.add(galaxy);
        SkyMark mark = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, 0, -1).get(0);
        // 5′ half axis at 1.5″ per pixel is 200 px; position angle 0 points north, which is +y here.
        assertEquals(200, mark.semiMajor, 0.5);
        assertEquals(100, mark.semiMinor, 0.5);
        assertEquals(Math.PI / 2, mark.angle, 1e-3);
        assertEquals("M 81 · Seyfert galaxy · 10 × 5′", mark.description);
    }

    @Test
    public void marksAVariableOrangeWhenItsPeriodFitsTheSession() {
        long threeHours = 3L * 3_600_000L;
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.variables.add(variableAt("Fits", 6.0 / 24.0, null));
        catalogue.variables.add(variableAt("Longer", 7.0 / 24.0, null));
        catalogue.variables.add(variableAt("No period", null, null));
        catalogue.variables.add(variableAt("Lower limit", 6.0 / 24.0, ">"));
        SkyCatalogue.VariableStar uncertain = variableAt("Uncertain", 6.0 / 24.0, null);
        uncertain.periodUncertainty = ":";
        catalogue.variables.add(uncertain);

        List<SkyMark> marks = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, 0, threeHours);
        assertTrue(markNamed(marks, "Fits").periodWithinSession);
        assertFalse(markNamed(marks, "Longer").periodWithinSession);
        assertFalse(markNamed(marks, "No period").periodWithinSession);
        assertFalse(markNamed(marks, "Lower limit").periodWithinSession);
        assertTrue(markNamed(marks, "Uncertain").periodWithinSession);
        assertFalse(SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, 0, -1).get(0).periodWithinSession);
    }

    @Test
    public void describesVariablesAndStars() {
        SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
        variable.name = "V0959 Mon";
        variable.type = "NB";
        variable.max = 9.86;
        variable.min = 18.4;
        variable.band = "V";
        variable.periodDays = 0.29575;
        assertEquals("V0959 Mon · NB · 9.86–18.40 V · period 0.296 d", SkyAnnotations.variableDescription(variable));
        variable.min = 0.03;
        variable.minIsAmplitude = true;
        variable.periodDays = null;
        assertTrue(SkyAnnotations.variableDescription(variable).endsWith("9.86 V, amplitude 0.03"));
        assertEquals("Gaia G 14.2 · BP−RP 0.90", SkyAnnotations.starDescription(star(0, 0, 14.2, null, null, 0.9)));
    }

    private static SkyMark markNamed(List<SkyMark> marks, String name) {
        return marks.stream().filter(mark -> name.equals(mark.label)).findFirst().orElseThrow();
    }

    /** A variable on the frame centre, with a period in days and an optional VSX lower-limit flag. */
    private static SkyCatalogue.VariableStar variableAt(String name, Double periodDays, String periodLimit) {
        SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
        variable.name = name;
        variable.ra = 150.0;
        variable.dec = 20.0;
        variable.periodDays = periodDays;
        variable.periodLimit = periodLimit;
        return variable;
    }

    private static SkyCatalogue.Star star(double ra, double dec, double g, Double pmra, Double pmdec) {
        return star(ra, dec, g, pmra, pmdec, null);
    }

    private static SkyCatalogue.Star star(double ra, double dec, double g, Double pmra, Double pmdec, Double bpRp) {
        SkyCatalogue.Star star = new SkyCatalogue.Star();
        star.ra = ra;
        star.dec = dec;
        star.g = g;
        star.pmra = pmra;
        star.pmdec = pmdec;
        star.bpRp = bpRp;
        return star;
    }

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "1000.5");
        header.put("CRPIX2", "800.5");
        header.put("CRVAL1", "150.0");
        header.put("CRVAL2", "20.0");
        header.put("CD1_1", Double.toString(-SCALE_DEG));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(SCALE_DEG));
        return header;
    }
}
