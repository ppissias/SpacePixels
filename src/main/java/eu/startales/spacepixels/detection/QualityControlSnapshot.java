package eu.startales.spacepixels.detection;

import com.google.gson.GsonBuilder;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.FrameQualityResult;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Small immutable QC result with captured input identities and settings; owns no pixels or live configuration. */
public record QualityControlSnapshot(List<Input> inputs, String settingsKey, FrameQualityResult result) {
    /** Copies the input container and checks the engine's original-position mapping before displaying any verdict. */
    public QualityControlSnapshot {
        inputs = List.copyOf(inputs);
        Objects.requireNonNull(settingsKey);
        Objects.requireNonNull(result);
        if (inputs.size() != result.frames().size()) throw new IllegalArgumentException("Incomplete quality result");
        for (int index = 0; index < inputs.size(); index++) {
            if (result.frames().get(index).sequenceIndex() != index)
                throw new IllegalArgumentException("Quality result has unexpected frame identities");
        }
    }

    /** Metadata used by the quality stage; WCS-only header replacements do not invalidate it. */
    public record Input(String path, boolean monochrome, int width, int height, long timestamp, long exposureMillis) {
        /** Captures identity and quality-relevant metadata without reading the file or retaining its header. */
        static Input of(FitsFileInformation file) {
            return new Input(Path.of(file.getFilePath()).toAbsolutePath().normalize().toString(), file.isMonochrome(),
                    file.getSizeWidth(), file.getSizeHeight(), file.getObservationTimestamp(), file.getExposureDurationMillis());
        }
    }

    /** Captures the complete detection configuration conservatively; display/report preferences do not enter it. */
    public static String settingsKey(DetectionConfig config) {
        return new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(config);
    }

    /** Copies all imported input identities in original order, independent of the table selection. */
    public static List<Input> inputs(FitsFileInformation[] files) {
        return Arrays.stream(files).map(Input::of).toList();
    }

    /** Whether this result still describes the imported cohort and active detection settings. No file I/O. */
    public boolean isCurrent(FitsFileInformation[] files, String activeSettingsKey) {
        return settingsKey.equals(activeSettingsKey) && inputs.equals(inputs(files));
    }

    /** Finds a file's original measured row, tolerating replacement of unrelated header cards. */
    public FrameQualityResult.Frame frame(FitsFileInformation file) {
        int index = file == null ? -1 : inputs.indexOf(Input.of(file));
        return index < 0 ? null : result.frames().get(index);
    }
}
