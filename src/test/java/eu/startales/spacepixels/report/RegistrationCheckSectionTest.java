package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The registration check's report data and panel show the same values, flagged zones and the reason when unmeasured. */
public class RegistrationCheckSectionTest {

    private static PipelineTelemetry measured() {
        PipelineTelemetry telemetry = new PipelineTelemetry();
        PipelineTelemetry.RegistrationCheckTelemetry r = telemetry.registrationCheck;
        r.available = true;
        r.fwhmPixels = 3.0;
        r.framesMeasured = 40;
        r.referenceStars = 500;
        r.largestKeptJumpFwhm = 0.1;
        r.overallCreepFwhm = 0.05;
        r.creepLimitFwhm = 0.3;
        r.zones = 4;
        r.width = 4000;
        r.height = 2000;
        r.zoneCreepFwhm = new double[16];
        Arrays.fill(r.zoneCreepFwhm, 0.05);
        r.zoneCreepFwhm[3] = 0.6;              // top right
        r.zoneCreepFwhm[12] = Double.NaN;      // bottom left: too few stars
        r.zoneStars = new int[16];
        Arrays.fill(r.zoneStars, 30);
        return telemetry;
    }

    private static String html(PipelineTelemetry telemetry) {
        StringWriter out = new StringWriter();
        RegistrationCheckSection.write(new PrintWriter(out, true), telemetry);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void aFlaggedZoneIsInTheDataAndThePanel() {
        PipelineTelemetry telemetry = measured();
        Map<String, Object> data = RegistrationCheckSection.data(telemetry);
        assertEquals(true, data.get("available"));
        assertEquals(false, data.get("overallFlagged"));
        List<Map<String, Object>> zones = (List<Map<String, Object>>) data.get("zoneCreep");
        assertEquals(16, zones.size());
        Map<String, Object> topRight = zones.get(3);
        assertEquals(true, topRight.get("flagged"));
        assertEquals(3000, topRight.get("x"));
        assertEquals(1000, topRight.get("width"));
        assertNull("an unmeasured zone has no creep", zones.get(12).get("creepFwhm"));
        assertTrue(RegistrationCheckSection.flagged(telemetry.registrationCheck));

        String html = html(telemetry);
        assertTrue(html.contains("id='registration-check'"));
        assertTrue(html.contains("<td class='flagged' title='1.8 px'>0.60</td>"));
        assertTrue(html.contains("registration-alert"));
        assertTrue(html.contains("x 3000&ndash;3999, y 0&ndash;499"));
        assertFalse("the whole frame is not flagged", html.contains("The whole star field moves"));
    }

    @Test
    public void aSteadySessionHasNoAlert() {
        PipelineTelemetry telemetry = measured();
        telemetry.registrationCheck.zoneCreepFwhm[3] = 0.05;
        assertFalse(RegistrationCheckSection.flagged(telemetry.registrationCheck));
        assertFalse(html(telemetry).contains("registration-alert"));
    }

    @Test
    public void anUnmeasuredCheckShowsItsReason() {
        PipelineTelemetry telemetry = new PipelineTelemetry();
        telemetry.registrationCheck.reason = "switched off";
        assertEquals(Map.of("available", false, "reason", "switched off"), RegistrationCheckSection.data(telemetry));
        assertTrue(html(telemetry).contains("Not measured: switched off."));
        assertNull("no check, no key", RegistrationCheckSection.data(new PipelineTelemetry()));
        assertEquals("", html(new PipelineTelemetry()));
    }
}
