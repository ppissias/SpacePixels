package eu.startales.spacepixels.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FitsFileInformationTest {

    @Test
    public void getLocationUsesObsGeoFallbackHeadersWhenSiteLatLonAreMissing() {
        FitsFileInformation fileInformation = new FitsFileInformation("sample.fit", "sample.fit", true, 100, 100);
        fileInformation.getFitsHeader().put("OBSGEO-B", "36.97166667");
        fileInformation.getFitsHeader().put("OBSGEO-L", "22.23138889");

        assertEquals("36.97166667 / 22.23138889", fileInformation.getLocation());
    }

    @Test
    public void getLocationFallsBackToObservatoryNameWhenCoordinatesAreUnavailable() {
        FitsFileInformation fileInformation = new FitsFileInformation("sample.fit", "sample.fit", true, 100, 100);
        fileInformation.getFitsHeader().put("OBSERVAT", "'Hellas Sky'");

        assertEquals("Hellas Sky", fileInformation.getLocation());
    }
}
