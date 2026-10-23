package eu.startales.spacepixels.config;

import java.util.List;
import java.util.stream.Stream;

/** Application-owned visible detection definitions; unlisted engine fields retain their profile values. */
public final class DetectionSettings {
    private static final List<DetectionSetting<?>> DEFINITIONS = Stream.of(
            ResidualAnalysisSettings.all(),
            StreakDetectionSettings.all(),
            MovingObjectsSettings.all())
            .flatMap(List::stream).toList();

    /** Prevents constructing the fixed definition registry. */
    private DetectionSettings() {}

    /** Returns visible definitions for validation titles and binding-ownership checks. */
    public static List<DetectionSetting<?>> all() {
        return DEFINITIONS;
    }
}
