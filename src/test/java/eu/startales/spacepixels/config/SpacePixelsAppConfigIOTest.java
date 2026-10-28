package eu.startales.spacepixels.config;

import org.junit.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies round-trip persistence for the application-level SpacePixels config.
 */
public class SpacePixelsAppConfigIOTest {

    /** Preparation executable selections survive both the queued snapshot copy and JSON persistence. */
    @Test public void preparationPathsRoundTripAndOldFilesKeepThemUnset() throws Exception {
        AppConfig config = new AppConfig();
        config.sirilExecutablePath = "tools/siril-cli";
        StringWriter writer = new StringWriter();
        SpacePixelsAppConfigIO.write(writer, config.copy());
        AppConfig loaded = SpacePixelsAppConfigIO.load(new StringReader(writer.toString()));
        assertEquals(config.sirilExecutablePath, loaded.sirilExecutablePath);
        AppConfig old = SpacePixelsAppConfigIO.load(new StringReader("{}"));
        assertEquals("", old.sirilExecutablePath);
    }

    @Test
    public void writeAndLoadRoundTripPreservesAppConfigValues() throws Exception {
        AppConfig appConfig = new AppConfig();
        appConfig.astapExecutablePath = "C:\\tools\\astap.exe";
        appConfig.observatoryCode = "XYZ";
        appConfig.imageRA = "12:34:56";
        appConfig.imageDEC = "+12:34:56";
        appConfig.siteLat = "37.9838";
        appConfig.siteLong = "23.7275";
        appConfig.pixelSize = "3.76";
        appConfig.focalLength = "800";

        StringWriter writer = new StringWriter();
        SpacePixelsAppConfigIO.write(writer, appConfig);

        AppConfig loadedConfig = SpacePixelsAppConfigIO.load(new StringReader(writer.toString()));

        assertEquals(appConfig.astapExecutablePath, loadedConfig.astapExecutablePath);
        assertEquals(appConfig.observatoryCode, loadedConfig.observatoryCode);
        assertEquals(appConfig.imageRA, loadedConfig.imageRA);
        assertEquals(appConfig.imageDEC, loadedConfig.imageDEC);
        assertEquals(appConfig.siteLat, loadedConfig.siteLat);
        assertEquals(appConfig.siteLong, loadedConfig.siteLong);
        assertEquals(appConfig.pixelSize, loadedConfig.pixelSize);
        assertEquals(appConfig.focalLength, loadedConfig.focalLength);
    }

    @Test
    public void theAstrometryNetAccountRoundTrips() throws Exception {
        AppConfig appConfig = new AppConfig();
        appConfig.astrometryNetUseOwnKey = true;
        appConfig.astrometryNetApiKey = "abcdefghijklmnop";

        StringWriter writer = new StringWriter();
        SpacePixelsAppConfigIO.write(writer, appConfig);
        AppConfig loadedConfig = SpacePixelsAppConfigIO.load(new StringReader(writer.toString()));

        assertTrue(loadedConfig.astrometryNetUseOwnKey);
        assertEquals("abcdefghijklmnop", loadedConfig.astrometryNetApiKey);
    }

    @Test
    public void aCopyKeepsTheAstrometryNetAccount() {
        // ApplicationSettings saves a copy, so a field missing from copy() would be saved as its default.
        AppConfig appConfig = new AppConfig();
        appConfig.astrometryNetUseOwnKey = true;
        appConfig.astrometryNetApiKey = "abcdefghijklmnop";

        AppConfig copy = appConfig.copy();

        assertTrue(copy.astrometryNetUseOwnKey);
        assertEquals("abcdefghijklmnop", copy.astrometryNetApiKey);
    }

    @Test
    public void aFileFromBeforeTheAccountSettingSolvesAnonymously() throws Exception {
        AppConfig loadedConfig = SpacePixelsAppConfigIO.load(new StringReader(
                "{\"astapExecutablePath\": \"C:\\\\tools\\\\astap.exe\", \"observatoryCode\": \"\", \"skyCatalogueStarMagnitude\": 15.0}"));

        assertFalse(loadedConfig.astrometryNetUseOwnKey);
        assertEquals("", loadedConfig.astrometryNetApiKey);
        assertEquals("", loadedConfig.astrometryNetApiKeyInUse());
    }

    @Test
    public void onlyAChosenNonBlankKeyIsUsed() {
        AppConfig appConfig = new AppConfig();
        appConfig.astrometryNetApiKey = "abcdefghijklmnop";
        assertEquals("a stored key is kept but not used while Anonymous is chosen", "", appConfig.astrometryNetApiKeyInUse());

        appConfig.astrometryNetUseOwnKey = true;
        assertEquals("abcdefghijklmnop", appConfig.astrometryNetApiKeyInUse());

        appConfig.astrometryNetApiKey = "  abcdefghijklmnop \t";
        assertEquals("abcdefghijklmnop", appConfig.astrometryNetApiKeyInUse());

        appConfig.astrometryNetApiKey = "   ";
        assertEquals("a blank key logs in anonymously", "", appConfig.astrometryNetApiKeyInUse());

        appConfig.astrometryNetApiKey = null;
        assertEquals("", appConfig.astrometryNetApiKeyInUse());
    }
}
