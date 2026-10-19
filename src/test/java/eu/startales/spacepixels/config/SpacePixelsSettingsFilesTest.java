package eu.startales.spacepixels.config;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Save writes both settings files, and a failure says which file was not saved and leaves no partial files. */
public class SpacePixelsSettingsFilesTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savesBothFiles() throws Exception {
        File profile = file(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME, "old profile");
        File visualization = file(SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME, "old preferences");

        SpacePixelsSettingsFiles.save(profile, profile(6.5), visualization, new SpacePixelsVisualizationPreferences());

        assertEquals(6.5, SpacePixelsDetectionProfileIO.load(new StringReader(read(profile))).getDetectionConfig().detectionSigmaMultiplier, 0.0);
        SpacePixelsVisualizationPreferencesIO.load(new StringReader(read(visualization)));
        assertOnlyFiles(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME, SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);
    }

    @Test
    public void whenTheProfileCannotBeReplacedNothingIsSaved() throws Exception {
        // A folder in the profile's place cannot be replaced by a file.
        File profile = folder.newFolder(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME);
        Files.writeString(new File(profile, "inside.txt").toPath(), "kept");
        File visualization = file(SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME, "old preferences");

        IOException failure = assertThrows(IOException.class, () ->
                SpacePixelsSettingsFiles.save(profile, profile(6.5), visualization, new SpacePixelsVisualizationPreferences()));

        assertTrue(failure.getMessage(), failure.getMessage().startsWith("Nothing was saved"));
        assertTrue(failure.getMessage(), failure.getMessage().contains(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME));
        assertEquals("old preferences", read(visualization));
        assertOnlyFiles(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME, SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);
    }

    @Test
    public void whenThePreferencesCannotBeReplacedTheMessageSaysTheProfileWasSaved() throws Exception {
        File profile = file(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME, "old profile");
        File visualization = folder.newFolder(SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);
        Files.writeString(new File(visualization, "inside.txt").toPath(), "kept");

        IOException failure = assertThrows(IOException.class, () ->
                SpacePixelsSettingsFiles.save(profile, profile(6.5), visualization, new SpacePixelsVisualizationPreferences()));

        assertTrue(failure.getMessage(), failure.getMessage().startsWith("The detection profile was saved, but the visualization preferences were not"));
        assertEquals(6.5, SpacePixelsDetectionProfileIO.load(new StringReader(read(profile))).getDetectionConfig().detectionSigmaMultiplier, 0.0);
        assertOnlyFiles(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME, SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);
    }

    private static SpacePixelsDetectionProfile profile(double detectionSigma) {
        DetectionConfig config = new DetectionConfig();
        config.detectionSigmaMultiplier = detectionSigma;
        return new SpacePixelsDetectionProfile(config, SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES);
    }

    private File file(String name, String contents) throws IOException {
        File file = folder.newFile(name);
        Files.writeString(file.toPath(), contents, StandardCharsets.UTF_8);
        return file;
    }

    private static String read(File file) throws IOException {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private void assertOnlyFiles(String... names) {
        String[] present = folder.getRoot().list();
        Arrays.sort(present);
        String[] expected = names.clone();
        Arrays.sort(expected);
        assertArrayEquals(expected, present);
    }
}
