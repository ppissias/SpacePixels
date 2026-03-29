package eu.startales.spacepixels.config;

import org.junit.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.Assert.assertEquals;

/**
 * Verifies round-trip persistence for the application-level SpacePixels config.
 */
public class SpacePixelsAppConfigIOTest {

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
}
