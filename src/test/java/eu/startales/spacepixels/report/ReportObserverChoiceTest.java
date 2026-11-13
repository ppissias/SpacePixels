package eu.startales.spacepixels.report;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.report.lookup.AstrometryIdentificationHtmlBuilder;
import eu.startales.spacepixels.report.lookup.SolarSystemQueryTarget;
import eu.startales.spacepixels.report.lookup.SolarSystemQueryTargetFactory;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The report's lookups use the frames' own site before a configured observatory code wherever coordinates are
 * accepted (JPL), and the configured code for SkyBoT, which accepts only codes.
 */
public class ReportObserverChoiceTest {
    private static final long START = java.time.Instant.parse("2024-01-08T02:49:37.926Z").toEpochMilli();

    @Test
    public void jplUsesTheHeaderSiteAndSkybotTheConfiguredCode() {
        AppConfig config = new AppConfig();
        config.observatoryCode = "J95";
        DetectionReportAstrometry.Context context = AstrometryContextFactory.buildContext(
                new FitsFileInformation[]{frame(Map.of("OBSLAT", "33.3573", "OBSLON", "-116.8598", "OBSALT", "1668.0"))},
                config, null);
        assertTrue(context.getObserverSite().isFromFitsHeader());
        assertNull(context.getJplObserverCode());
        assertEquals("J95", context.getSkybotObserverCode());

        SolarSystemQueryTarget target = SolarSystemQueryTargetFactory.buildFaintMoverQueryTarget(context, 80, 60, START);
        String jpl = DetectionReportAstrometry.buildJplSbIdentUrl(context, target, 0.1);
        assertTrue(jpl, jpl.contains("lat=33.3573") && jpl.contains("lon=-116.8598") && jpl.contains("alt=1.668"));
        assertFalse(jpl, jpl.contains("mpc-code"));
        assertTrue(DetectionReportAstrometry.buildSkybotUrl(context, target, 0.1, false).contains("-observer=J95"));
        String html = AstrometryIdentificationHtmlBuilder.buildSolarSystemIdentificationHtml(context, target,
                "Epoch", "Search SkyBoT", "Radius", null, null, false);
        assertTrue(html, html.contains("JPL Small-Body Identification uses topocentric site coordinates from FITS header"));
        assertTrue(html, html.contains("Observer used for lookup: IAU code J95"));
    }

    @Test
    public void withoutAHeaderSiteJplUsesTheConfiguredCode() {
        AppConfig config = new AppConfig();
        config.observatoryCode = "J95";
        config.siteLat = "37.9";
        config.siteLong = "23.7";
        DetectionReportAstrometry.Context context = AstrometryContextFactory.buildContext(
                new FitsFileInformation[]{frame(Map.of())}, config, null);
        assertFalse(context.getObserverSite().isFromFitsHeader());
        assertEquals("J95", context.getJplObserverCode());
        SolarSystemQueryTarget target = SolarSystemQueryTargetFactory.buildFaintMoverQueryTarget(context, 80, 60, START);
        String jpl = DetectionReportAstrometry.buildJplSbIdentUrl(context, target, 0.1);
        assertTrue(jpl, jpl.contains("mpc-code=J95"));
        assertFalse(jpl, jpl.contains("lat="));
    }

    /** One plate-solved 160x120 frame with the given site cards. */
    private static FitsFileInformation frame(Map<String, String> site) {
        Map<String, String> header = new HashMap<>(site);
        header.put("SHUTOPEN", "2024-01-08T02:49:37.926");
        header.put("EXPTIME", "30");
        header.put("CTYPE1", "RA---TAN"); header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "80.5"); header.put("CRPIX2", "60.5"); header.put("CRVAL1", "95.6"); header.put("CRVAL2", "18.9");
        header.put("CD1_1", "-0.000281"); header.put("CD1_2", "0"); header.put("CD2_1", "0"); header.put("CD2_2", "0.000281");
        return new FitsFileInformation("frame.fit", "frame.fit", true, 160, 120, header);
    }
}
