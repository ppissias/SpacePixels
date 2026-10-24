package eu.startales.spacepixels.config;

import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

        StringWriter writer = new StringWriter();
        SpacePixelsVisualizationPreferencesIO.write(writer, preferences);

        String json = writer.toString();
        SpacePixelsVisualizationPreferences loadedPreferences = SpacePixelsVisualizationPreferencesIO.load(new StringReader(json));

        assertEquals(7.5d, loadedPreferences.streakLineScaleFactor, 0.0d);
        assertEquals(11, loadedPreferences.streakCentroidBoxRadius);
        assertEquals(4, loadedPreferences.pointSourceMinBoxRadius);
        assertEquals(9, loadedPreferences.dynamicBoxPadding);
        assertEquals(0.8d, loadedPreferences.autoStretchBlackSigma, 0.0d);
        assertEquals(4.4d, loadedPreferences.autoStretchWhiteSigma, 0.0d);
        assertEquals(180, loadedPreferences.gifBlinkSpeedMs);
        assertEquals(160, loadedPreferences.trackCropPadding);
    }

    /** A file saved while the AI report sections existed still loads, and the next save drops their field. */
    @Test
    public void removedAiSectionsFieldIsIgnoredOnLoadAndDroppedOnSave() throws Exception {
        String saved = "{\"gifBlinkSpeedMs\": 180, \"includeAiCreativeReportSections\": true}";

        SpacePixelsVisualizationPreferences loadedPreferences = SpacePixelsVisualizationPreferencesIO.load(new StringReader(saved));
        StringWriter writer = new StringWriter();
        SpacePixelsVisualizationPreferencesIO.write(writer, loadedPreferences);

        assertEquals(180, loadedPreferences.gifBlinkSpeedMs);
        assertFalse(writer.toString().contains("includeAiCreativeReportSections"));
    }

    /** The file holds the eight values the settings page edits, under the names older files use. */
    @Test
    public void savedFormKeepsItsFieldNames() {
        StringWriter writer = new StringWriter();
        SpacePixelsVisualizationPreferencesIO.write(writer, SpacePixelsVisualizationPreferences.of(VisualizationSettings.defaults()));

        assertEquals(List.of("streakLineScaleFactor", "streakCentroidBoxRadius", "pointSourceMinBoxRadius", "dynamicBoxPadding",
                        "autoStretchBlackSigma", "autoStretchWhiteSigma", "gifBlinkSpeedMs", "trackCropPadding"),
                List.copyOf(JsonParser.parseString(writer.toString()).getAsJsonObject().keySet()));
    }

    /** Saving and loading carries the edited values; the values the file does not hold come from the base value. */
    @Test
    public void savedValuesApplyToABaseValue() throws Exception {
        VariableStarReportOptions options = new VariableStarReportOptions(60, VariableStarReportOptions.SortOrder.SIGNIFICANCE);
        VisualizationSettings base = VisualizationSettings.defaults().withMaxAnimatedMarkers(7).withVariableStarReportOptions(options);
        VisualizationSettings edited = base.withStreakLineScaleFactor(6.2).withStreakCentroidBoxRadius(8)
                .withPointSourceMinBoxRadius(5).withDynamicBoxPadding(7).withAutoStretchBlackSigma(0.6)
                .withAutoStretchWhiteSigma(3.8).withGifBlinkSpeedMs(150).withTrackCropPadding(140);
        StringWriter writer = new StringWriter();
        SpacePixelsVisualizationPreferencesIO.write(writer, SpacePixelsVisualizationPreferences.of(edited));

        SpacePixelsVisualizationPreferences loaded = SpacePixelsVisualizationPreferencesIO.load(new StringReader(writer.toString()));

        assertEquals(edited, loaded.applyTo(base));
        assertEquals(VisualizationSettings.defaults().withStreakLineScaleFactor(6.2).withStreakCentroidBoxRadius(8)
                        .withPointSourceMinBoxRadius(5).withDynamicBoxPadding(7).withAutoStretchBlackSigma(0.6)
                        .withAutoStretchWhiteSigma(3.8).withGifBlinkSpeedMs(150).withTrackCropPadding(140),
                loaded.applyTo(VisualizationSettings.defaults()));
    }

    /** A value missing from an older or edited file keeps its built-in default. */
    @Test
    public void missingValuesKeepTheBuiltInDefaults() throws Exception {
        SpacePixelsVisualizationPreferences loaded = SpacePixelsVisualizationPreferencesIO.load(new StringReader("{\"gifBlinkSpeedMs\": 180}"));

        assertEquals(VisualizationSettings.defaults().withGifBlinkSpeedMs(180), loaded.applyTo(VisualizationSettings.defaults()));
    }
}
