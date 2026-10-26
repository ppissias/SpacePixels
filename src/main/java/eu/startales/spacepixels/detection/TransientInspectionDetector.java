package eu.startales.spacepixels.detection;

import com.google.gson.Gson;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import io.github.ppissias.jtransient.engine.FrameTransients;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientReference;
import io.github.ppissias.jtransient.model.DetectedObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One inspection window's cached frame detections. Each cache miss owns an engine on the calling worker, so closing
 * the window never shuts down a running engine. The caller supplies cancellation through its CancellationScope.
 */
public final class TransientInspectionDetector implements AutoCloseable {
    private final Gson json = new Gson();
    private final Map<Key, List<DetectedObject>> detections = new HashMap<>();
    /** Guarded by this detector's monitor; null after close. Pixel buffers and detections are borrowed read-only. */
    private TransientReference reference;

    /** Seeds the cache with the opening pass, using the complete settings that produced its detections. */
    public TransientInspectionDetector(TransientReference reference, List<FrameTransients> opening,
                                       DetectionConfig settings) {
        this.reference = Objects.requireNonNull(reference, "reference");
        String configuration = json.toJson(settings);
        for (int index = 0; index < opening.size(); index++) {
            detections.put(new Key(index, configuration), opening.get(index).transients);
        }
    }

    /**
     * Returns read-only detections for one kept frame and a captured copy of all settings. Runs on the caller's
     * worker; the short cache locks never cover extraction or engine shutdown. Existing calls may finish after close.
     */
    public List<DetectedObject> detect(int index, DetectionConfig config) {
        DetectionConfig settings = DetectionConfigRules.snapshot(config, DetectionConfigRules.Operation.TRANSIENTS);
        Key key = new Key(index, json.toJson(settings));
        TransientReference kept;
        synchronized (this) {
            if (reference == null) {
                throw new IllegalStateException("The transient inspection is closed.");
            }
            List<DetectedObject> cached = detections.get(key);
            if (cached != null) {
                return cached;
            }
            kept = reference;
        }

        List<DetectedObject> objects;
        try (var engine = new JTransientEngine()) {
            objects = engine.redetectFrame(kept, index, settings).transients;
        }
        synchronized (this) {
            if (reference != null) {
                detections.put(key, objects);
            }
        }
        return objects;
    }

    /** Releases this window's cache immediately; an active call closes its own engine after it exits. */
    @Override
    public synchronized void close() {
        reference = null;
        detections.clear();
    }

    /** The kept frame and the complete configuration, including settings outside the three local trial controls. */
    private record Key(int index, String configuration) { }
}
