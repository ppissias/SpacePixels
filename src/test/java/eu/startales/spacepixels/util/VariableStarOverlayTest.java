package eu.startales.spacepixels.util;

import com.google.gson.Gson;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.util.skycatalog.SkyField;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class VariableStarOverlayTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void keepsReportNumberingAndExcludesRejectedStars() throws Exception {
        FitsFileInformation file = file(folder.newFolder("session"));
        VariableStarOverlay overlay = VariableStarOverlay.fromCandidates(Arrays.asList(
                star("HIGH_CONFIDENCE", 1), star("POSSIBLE", 2), star("REJECTED", 3)), new FitsFileInformation[]{file}, null, null);
        assertEquals(2, overlay.getCandidates().size());
        assertEquals("V1", overlay.getCandidates().get(0).label);
        assertEquals("V2", overlay.getCandidates().get(1).label);
        assertTrue(overlay.getCandidates().get(0).highConfidence);
        assertFalse(overlay.getCandidates().get(1).highConfidence);
        assertTrue(overlay.coversAnyOf(new FitsFileInformation[]{file}));
        assertFalse(VariableStarOverlay.fromCandidates(Collections.emptyList(), new FitsFileInformation[]{file}, null, null)
                .coversAnyOf(new FitsFileInformation[]{file}));
    }

    @Test public void doesNotReuseCandidatesForAnotherSessionOrFrameSize() throws Exception {
        File firstFolder = folder.newFolder("first");
        FitsFileInformation file = file(firstFolder);
        VariableStarOverlay overlay = VariableStarOverlay.fromCandidates(Collections.singletonList(star("HIGH_CONFIDENCE", 1)),
                new FitsFileInformation[]{file}, null, null);
        assertFalse(overlay.covers(file(folder.newFolder("second"))));
        FitsFileInformation resized = new FitsFileInformation(
                new File(firstFolder, "same-name.fit").getAbsolutePath(), "same-name.fit", true, 201, 160);
        assertFalse(overlay.covers(resized));
    }

    @Test public void namesCandidatesFromTheMatchingSavedCatalogue() throws Exception {
        File session = folder.newFolder("named");
        FitsFileInformation file = file(session).withFitsHeader(java.util.Map.of(
                "CTYPE1", "RA---TAN",
                "CTYPE2", "DEC--TAN",
                "CRPIX1", "100.5",
                "CRPIX2", "80.5",
                "CRVAL1", "150",
                "CRVAL2", "20",
                "CD1_1", "-0.0002777777778",
                "CD1_2", "0",
                "CD2_1", "0",
                "CD2_2", "0.0002777777778"));
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.field = SkyField.of(WcsCoordinateTransformer.fromHeader(file.getFitsHeader()), 200, 160);
        SkyCatalogue.VariableStar match = new SkyCatalogue.VariableStar();
        match.name = "DK Per";
        match.ra = 150;
        match.dec = 20 + 3.0 / 3600;
        catalogue.variables.add(match);
        SkyCatalogueStore.save(session, catalogue);
        WcsCoordinateTransformer transformer = WcsCoordinateTransformer.fromHeader(file.getFitsHeader());
        VariableStarOverlay overlay = VariableStarOverlay.fromCandidates(Collections.singletonList(star("HIGH_CONFIDENCE", 1)),
                new FitsFileInformation[]{file}, transformer, SkyCatalogueStore.load(session));
        assertEquals("DK Per", overlay.getCandidates().get(0).name);
        assertTrue(overlay.getCandidates().get(0).description.contains("V1 · DK Per"));
        match.dec = 21;
        SkyCatalogueStore.save(session, catalogue);
        assertNull(VariableStarOverlay.fromCandidates(Collections.singletonList(star("HIGH_CONFIDENCE", 1)),
                new FitsFileInformation[]{file}, transformer, SkyCatalogueStore.load(session)).getCandidates().get(0).name);
    }

    private static FitsFileInformation file(File session) {
        return new FitsFileInformation(new File(session, "same-name.fit").getAbsolutePath(), "same-name.fit", true, 200, 160);
    }

    private static StarLightCurve star(String tier, int id) {
        return new Gson().fromJson("{\"id\":" + id + ",\"x\":99.5,\"y\":79.5,\"tier\":\"" + tier
                + "\",\"amplitude\":0.61}", StarLightCurve.class);
    }
}
