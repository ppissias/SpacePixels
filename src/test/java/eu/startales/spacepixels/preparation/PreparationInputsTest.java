package eu.startales.spacepixels.preparation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import static eu.startales.spacepixels.preparation.PreparationInputs.Role.*;
import static org.junit.Assert.*;

/** Conservative discovery must never merge distinct targets or promote unknown RAW metadata into known facts. */
public class PreparationInputsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** A shallow session wrapper is useful, while previous tool outputs and ambiguous targets are left alone. */
    @Test public void suggestsNestedConventionsButRefusesAmbiguousLightGroups() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path light = images(root, "session/lights", 3);
        Path flat = images(root, "session/flats", 2);
        images(root, "siril/lights", 3);
        assertEquals(Map.of(LIGHTS, light, FLATS, flat), PreparationInputs.suggest(root));
        images(root, "light other-target", 3);
        assertFalse(PreparationInputs.suggest(root).containsKey(LIGHTS));
        assertEquals(flat, PreparationInputs.suggest(root).get(FLATS));
    }

    /** Prefixes, suffixes and compact filter labels expose independent groups rather than silently combining channels. */
    @Test public void recognizesPrefixedRolesAndKeepsFilterGroupsSeparate() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path lights = images(root, "andromeda_lights", 3);
        Path flats = images(root, "andromeda_flats", 2);
        assertEquals(Map.of(LIGHTS, lights, FLATS, flats), PreparationInputs.suggest(root));
        Path blue = images(root, "lightB", 3);
        Path blueFlats = images(root, "flatB", 2);
        var scan = PreparationInputs.scan(root);
        assertEquals(List.of(lights, blue), scan.candidates().get(LIGHTS).stream().map(PreparationInputs.Candidate::path).toList());
        assertEquals(List.of(flats, blueFlats), scan.candidates().get(FLATS).stream().map(PreparationInputs.Candidate::path).toList());
        assertTrue(scan.suggestions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> scan.candidates().get(LIGHTS).clear());
    }

    /** Empty containers are replaced by their populated exposure/filter subgroups; processed copies are excluded. */
    @Test public void findsNestedExposureGroupsAndSkipsRolePrefixedOutputs() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path blue = images(root, "camera/lights/Blue", 3);
        Path green = images(root, "camera/lights/Green", 4);
        Path shortFlats = images(root, "camera/flats/0.10", 2);
        Path longFlats = images(root, "camera/flats/0.15", 2);
        images(root, "camera/lights/registered", 20);
        images(root, "light-aligned", 20);
        images(root, "light-calibrated-pproc", 20);
        images(root, "processed/lights", 20);
        var scan = PreparationInputs.scan(root);
        assertEquals(List.of(blue, green), scan.candidates().get(LIGHTS).stream().map(PreparationInputs.Candidate::path).toList());
        assertEquals(List.of(shortFlats, longFlats), scan.candidates().get(FLATS).stream().map(PreparationInputs.Candidate::path).toList());
        assertTrue(scan.suggestions().isEmpty());
        assertEquals(3, scan.candidates().get(LIGHTS).getFirst().images());
    }

    /** More descriptive dark-flat names stay distinct from darks; scanning is bounded and ignores empty role folders. */
    @Test public void distinguishesDarkFlatsAndBoundsDiscovery() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path darkFlats = images(root, "dark-flat 0.1s ISO100", 2);
        Path darks = images(root, "dark 180s ISO800", 2);
        Path biases = images(root, "session_bias", 2);
        images(root, "one/two/three/lights", 3);
        images(root, "one/two/three/four/lights", 3);
        Files.createDirectories(root.resolve("flat"));
        assertEquals(Map.of(DARK_FLATS, darkFlats, DARKS, darks, BIASES, biases, LIGHTS, root.resolve("one/two/three/lights")), PreparationInputs.suggest(root));
    }

    /** Calibration masters beside their exposures are explicit file choices and cannot enter another master stack as a folder. */
    @Test public void offersMastersExplicitlyAndRejectsRecombiningThemWithExposures() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path lights = images(root, "lights", 3);
        Path biases = images(root, "bias", 2);
        Path master = Files.createFile(biases.resolve("master_bias.fit"));
        var scan = PreparationInputs.scan(root);
        assertEquals(Map.of(LIGHTS, lights), scan.suggestions());
        assertEquals(List.of(new PreparationInputs.Candidate(master, 1, true)), scan.candidates().get(BIASES));
        var failure = assertThrows(java.io.IOException.class, () -> FramePreparationService.inspect(Map.of(LIGHTS, lights, BIASES, biases)));
        assertTrue(failure.getMessage().contains("Select the master FITS file directly"));
        var inputs = FramePreparationService.inspect(Map.of(LIGHTS, lights, BIASES, master));
        assertTrue(inputs.masters().contains(BIASES));
        assertEquals(List.of(master), inputs.files().get(BIASES));
    }

    /** A TIFF named like a master is not a FITS master, so the calibration exposures in that folder remain usable. */
    @Test public void tiffNamedLikeAMasterDoesNotReplaceTheExposureFolder() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path lights = images(root, "lights", 3);
        Path flats = Files.createDirectories(root.resolve("flat"));
        Files.createFile(flats.resolve("F_0043.CR2"));
        Files.createFile(flats.resolve("F_0044.CR2"));
        Path tiff = Files.createFile(flats.resolve("MasterFlat_ISO200.tif"));
        var scan = PreparationInputs.scan(root);
        assertEquals(List.of(new PreparationInputs.Candidate(flats, 3, false)), scan.candidates().get(FLATS));
        var inputs = FramePreparationService.inspect(Map.of(LIGHTS, lights, FLATS, flats));
        assertFalse(inputs.masters().contains(FLATS));
        var question = PreparationCohort.questions(inputs).get(0);
        assertEquals(FLATS, question.role());
        assertTrue(question.excludeMinority());
        assertEquals(List.of(tiff), question.omittedByKeeping(0));
        assertThrows(java.io.IOException.class, () -> FramePreparationService.inspect(Map.of(LIGHTS, lights, FLATS, tiff)));
    }

    /** Siril names its bias folder offsets; that folder is a bias group. */
    @Test public void recognizesSirilOffsetFoldersAsBias() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path offsets = images(root, "offsets", 2);
        assertEquals(Map.of(BIASES, offsets), PreparationInputs.suggest(root));
    }

    /** Extensions are candidates only; image files in child output folders are never mixed with the selected inputs. */
    @Test public void inventoriesCandidatesWithoutReadingRawOrFollowingSubdirectories() throws Exception {
        Path folder = temporary.getRoot().toPath();
        Files.writeString(folder.resolve("a.CR2"), "not actually RAW");
        Files.writeString(folder.resolve("b.fit.fz"), "not actually FITS");
        Files.writeString(folder.resolve("notes.txt"), "notes");
        Files.createDirectories(folder.resolve("registered"));
        Files.writeString(folder.resolve("registered/extra.fit"), "ignored");
        var result = PreparationInputs.inspect(folder);
        assertEquals(2, result.files().size());
        assertEquals(1, result.rawCandidates());
        assertEquals(1, result.ignored());
        assertThrows(UnsupportedOperationException.class, () -> result.files().clear());
    }

    /** Reusing one exposure as both a light and a calibration frame is rejected before any external run. */
    @Test public void rejectsDuplicateRoleInputsAndMissingLightCohort() throws Exception {
        Path folder = temporary.newFolder().toPath();
        for (int i = 0; i < 3; i++) Files.createFile(folder.resolve(i + ".fit"));
        assertThrows(java.io.IOException.class, () -> FramePreparationService.inspect(Map.of(LIGHTS, folder, DARKS, folder)));
        assertThrows(java.io.IOException.class, () -> FramePreparationService.inspect(Map.of(DARKS, folder)));
    }

    /** Creates candidate filenames only; these discovery tests do not claim image decoding or calibration compatibility. */
    private static Path images(Path root, String relative, int count) throws Exception {
        Path folder = Files.createDirectories(root.resolve(relative));
        for (int i = 0; i < count; i++) Files.createFile(folder.resolve(i + ".fit"));
        return folder;
    }
}
