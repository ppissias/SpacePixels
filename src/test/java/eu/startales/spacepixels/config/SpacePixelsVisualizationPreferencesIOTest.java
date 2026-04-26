package eu.startales.spacepixels.config;

import eu.startales.spacepixels.util.DisplayImageRenderer;
import eu.startales.spacepixels.util.RawImageAnnotator;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import org.junit.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies round-trip persistence for rendering and export preferences that are now stored outside
 * the detection profile.
 */
public class SpacePixelsVisualizationPreferencesIOTest {

    @Test
    public void writeAndLoadRoundTripPreservesVisualizationPreferences() throws Exception {
        SpacePixelsVisualizationPreferences preferences = new SpacePixelsVisualizationPreferences();
        preferences.streakLineScaleFactor = 7.5d;
        preferences.streakCentroidBoxRadius = 11;
        preferences.pointSourceMinBoxRadius = 4;
        preferences.dynamicBoxPadding = 9;
        preferences.autoStretchBlackSigma = 0.8d;
        preferences.autoStretchWhiteSigma = 4.4d;
        preferences.gifBlinkSpeedMs = 180;
        preferences.trackCropPadding = 160;
        preferences.includeAiCreativeReportSections = true;

        StringWriter writer = new StringWriter();
        SpacePixelsVisualizationPreferencesIO.write(writer, preferences);

        String json = writer.toString();
        SpacePixelsVisualizationPreferences loadedPreferences = SpacePixelsVisualizationPreferencesIO.load(new StringReader(json));

        assertTrue(json.contains("\"includeAiCreativeReportSections\": true"));
        assertEquals(7.5d, loadedPreferences.streakLineScaleFactor, 0.0d);
        assertEquals(11, loadedPreferences.streakCentroidBoxRadius);
        assertEquals(4, loadedPreferences.pointSourceMinBoxRadius);
        assertEquals(9, loadedPreferences.dynamicBoxPadding);
        assertEquals(0.8d, loadedPreferences.autoStretchBlackSigma, 0.0d);
        assertEquals(4.4d, loadedPreferences.autoStretchWhiteSigma, 0.0d);
        assertEquals(180, loadedPreferences.gifBlinkSpeedMs);
        assertEquals(160, loadedPreferences.trackCropPadding);
        assertTrue(loadedPreferences.includeAiCreativeReportSections);
    }

    @Test
    public void applyToRuntimeUpdatesVisualizationStatics() {
        double originalStreakLineScaleFactor = RawImageAnnotator.streakLineScaleFactor;
        int originalStreakCentroidBoxRadius = RawImageAnnotator.streakCentroidBoxRadius;
        int originalPointSourceMinBoxRadius = RawImageAnnotator.pointSourceMinBoxRadius;
        int originalDynamicBoxPadding = RawImageAnnotator.dynamicBoxPadding;
        double originalAutoStretchBlackSigma = DisplayImageRenderer.autoStretchBlackSigma;
        double originalAutoStretchWhiteSigma = DisplayImageRenderer.autoStretchWhiteSigma;
        int originalGifBlinkSpeedMs = DetectionReportGenerator.gifBlinkSpeedMs;
        int originalTrackCropPadding = DetectionReportGenerator.trackCropPadding;
        boolean originalIncludeAiCreativeReportSections = DetectionReportGenerator.includeAiCreativeReportSections;

        SpacePixelsVisualizationPreferences preferences = new SpacePixelsVisualizationPreferences();
        preferences.streakLineScaleFactor = 6.2d;
        preferences.streakCentroidBoxRadius = 8;
        preferences.pointSourceMinBoxRadius = 5;
        preferences.dynamicBoxPadding = 7;
        preferences.autoStretchBlackSigma = 0.6d;
        preferences.autoStretchWhiteSigma = 3.8d;
        preferences.gifBlinkSpeedMs = 150;
        preferences.trackCropPadding = 140;
        preferences.includeAiCreativeReportSections = true;

        try {
            preferences.applyToRuntime();

            assertEquals(6.2d, RawImageAnnotator.streakLineScaleFactor, 0.0d);
            assertEquals(8, RawImageAnnotator.streakCentroidBoxRadius);
            assertEquals(5, RawImageAnnotator.pointSourceMinBoxRadius);
            assertEquals(7, RawImageAnnotator.dynamicBoxPadding);
            assertEquals(0.6d, DisplayImageRenderer.autoStretchBlackSigma, 0.0d);
            assertEquals(3.8d, DisplayImageRenderer.autoStretchWhiteSigma, 0.0d);
            assertEquals(150, DetectionReportGenerator.gifBlinkSpeedMs);
            assertEquals(140, DetectionReportGenerator.trackCropPadding);
            assertTrue(DetectionReportGenerator.includeAiCreativeReportSections);
        } finally {
            RawImageAnnotator.streakLineScaleFactor = originalStreakLineScaleFactor;
            RawImageAnnotator.streakCentroidBoxRadius = originalStreakCentroidBoxRadius;
            RawImageAnnotator.pointSourceMinBoxRadius = originalPointSourceMinBoxRadius;
            RawImageAnnotator.dynamicBoxPadding = originalDynamicBoxPadding;
            DisplayImageRenderer.autoStretchBlackSigma = originalAutoStretchBlackSigma;
            DisplayImageRenderer.autoStretchWhiteSigma = originalAutoStretchWhiteSigma;
            DetectionReportGenerator.gifBlinkSpeedMs = originalGifBlinkSpeedMs;
            DetectionReportGenerator.trackCropPadding = originalTrackCropPadding;
            DetectionReportGenerator.includeAiCreativeReportSections = originalIncludeAiCreativeReportSections;
        }
    }
}
