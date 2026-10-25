package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.skycatalog.SkyField;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A synthetic session the viewers can annotate: solved frames, a sky catalogue for that field, and a plate
 * correction so import does not start a catalogue fetch or a plate-solution check.
 */
final class SolvedSession {

    private SolvedSession() {
    }

    static Path create(Path root) throws Exception {
        Path session = SyntheticDatasetFactory.createSolvedMono16FitsSequence(
                root, "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT);
        try (Fits fits = new Fits(session.resolve("frame_01.fit").toFile())) {
            Header header = fits.getHDU(0).getHeader();
            WcsCoordinateTransformer transformer = WcsCoordinateTransformer.fromHeader(headerValues(header));
            if (transformer == null) {
                throw new IllegalStateException("The solved frame has no plate solution");
            }
            SkyField field = SkyField.of(transformer, SyntheticDatasetFactory.DEFAULT_WIDTH, SyntheticDatasetFactory.DEFAULT_HEIGHT);
            SkyCatalogueStore.save(session.toFile(), catalogue(field));
            PlateSolutionCorrection.save(session.toFile(), correction(transformer.solutionKey()));
        }
        return session;
    }

    private static SkyCatalogue catalogue(SkyField field) {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.field = field;
        catalogue.starMagnitudeLimit = 15;
        catalogue.starSource = "test";
        catalogue.fetchedAtUtc = "2026-10-21T00:00:00Z";

        SkyCatalogue.Star star = new SkyCatalogue.Star();
        star.ra = field.centerRa;
        star.dec = field.centerDec;
        star.g = 12;
        catalogue.stars.add(star);

        SkyCatalogue.DeepSkyObject object = new SkyCatalogue.DeepSkyObject();
        object.name = "NGC 1";
        object.type = "G";
        object.ra = field.centerRa;
        object.dec = field.centerDec;
        object.majorArcmin = 1.0;
        object.minorArcmin = 1.0;
        object.angleDeg = 0.0;
        catalogue.deepSky.add(object);
        return catalogue;
    }

    /** A zero correction, so the check is already done and Correct plate solution can be switched on. */
    private static PlateSolutionCorrection correction(String solutionKey) {
        PlateSolutionCorrection.Polynomial global = new PlateSolutionCorrection.Polynomial();
        global.order = 0;
        global.cx = SyntheticDatasetFactory.DEFAULT_WIDTH / 2.0;
        global.cy = SyntheticDatasetFactory.DEFAULT_HEIGHT / 2.0;
        global.scale = SyntheticDatasetFactory.DEFAULT_WIDTH / 2.0;
        global.ax = new double[]{0};
        global.ay = new double[]{0};

        PlateSolutionCorrection correction = new PlateSolutionCorrection();
        correction.enabled = false;
        correction.solutionKey = solutionKey;
        correction.width = SyntheticDatasetFactory.DEFAULT_WIDTH;
        correction.height = SyntheticDatasetFactory.DEFAULT_HEIGHT;
        correction.global = global;
        correction.beforeMedianPx = 1.2;
        correction.afterMedianPx = 0.1;
        return correction;
    }

    private static Map<String, String> headerValues(Header header) {
        Map<String, String> values = new HashMap<>();
        Cursor<String, HeaderCard> cards = header.iterator();
        while (cards.hasNext()) {
            HeaderCard card = cards.next();
            if (card.getKey() != null && card.getValue() != null) {
                values.put(card.getKey(), card.getValue());
            }
        }
        return values;
    }
}
