package eu.startales.spacepixels.gui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConfigurationPanelTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void observatoryCodeIsEmptyOrThreeLettersOrDigits() {
        assertNull(ConfigurationPanel.validateObservatoryCode(""));
        assertNull(ConfigurationPanel.validateObservatoryCode("J95"));
        assertNull(ConfigurationPanel.validateObservatoryCode("500"));
        assertNotNull(ConfigurationPanel.validateObservatoryCode("J9"));
        assertNotNull(ConfigurationPanel.validateObservatoryCode("J95X"));
    }

    @Test
    public void coordinatesAreAcceptedAsTheReportReadsThem() {
        assertNull(ConfigurationPanel.validateCoordinate("", 90, "latitude"));
        assertNull(ConfigurationPanel.validateCoordinate("37.9838", 90, "latitude"));
        assertNull(ConfigurationPanel.validateCoordinate("-24.6272", 90, "latitude"));
        assertEquals(-24.6272, ConfigurationPanel.parseDegrees("24.6272S"), 1e-9);
        assertEquals(-70.403, ConfigurationPanel.parseDegrees("70,403 W"), 1e-9);
        assertNotNull("out of range", ConfigurationPanel.validateCoordinate("95", 90, "latitude"));
        assertNull(ConfigurationPanel.validateCoordinate("170", 180, "longitude"));
        assertNotNull("degrees-minutes-seconds is not read by the report", ConfigurationPanel.validateCoordinate("37 59 01", 90, "latitude"));
        assertNotNull(ConfigurationPanel.validateCoordinate("north", 90, "latitude"));
    }

    @Test
    public void starDatabasesAreRecognisedByTheirFileNames() throws Exception {
        File folder = temporaryFolder.newFolder();
        new File(folder, "astap.exe").createNewFile();
        new File(folder, "d50_0101.1476").createNewFile();
        new File(folder, "d50_0102.1476").createNewFile();
        new File(folder, "w08_0101.001").createNewFile();
        new File(folder, "readme.txt").createNewFile();

        Map<String, Integer> databases = ConfigurationPanel.starDatabases(folder);

        assertEquals(2, databases.size());
        assertEquals(Integer.valueOf(2), databases.get("D50"));
        assertTrue(databases.containsKey("W08"));
        assertTrue(ConfigurationPanel.starDatabases(temporaryFolder.newFolder()).isEmpty());
    }
}
