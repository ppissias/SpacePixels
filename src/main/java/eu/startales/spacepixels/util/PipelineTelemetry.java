package eu.startales.spacepixels.util;

import java.util.ArrayList;
import java.util.List;

public class PipelineTelemetry {

    // --- PHASE 1: Extraction ---
    public int totalFramesLoaded = 0;
    public int totalRawObjectsExtracted = 0;

    // Per-frame basic stats
    public static class FrameExtractionStat {
        public int frameIndex;
        public String filename;
        public int objectCount;
    }
    public List<FrameExtractionStat> frameExtractionStats = new ArrayList<>();

    // --- PHASE 2 & 3: Quality & Filtering ---
    public int totalFramesRejected = 0;
    public int totalFramesKept = 0;

    public static class FrameRejectionStat {
        public int frameIndex;
        public String filename;
        public String reason;
    }
    public List<FrameRejectionStat> rejectedFrames = new ArrayList<>();

    // --- PHASE 4: Tracking ---
    // You can populate this directly from your existing TrackLinker.TrackingResult
    public int totalStationaryStarsIdentified = 0;
    public int totalMovingTargetsFound = 0;

    // --- PHASE 5: Export ---
    public String exportDirectoryPath = "";
    public long processingTimeMs = 0;

    /**
     * Generates a formatted text report.
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("==================================================\n");
        sb.append("          SPACE PIXELS DETECTION REPORT           \n");
        sb.append("==================================================\n\n");

        sb.append("--- PIPELINE SUMMARY ---\n");
        sb.append(String.format("Total Processing Time : %.2f seconds\n", processingTimeMs / 1000.0));
        sb.append(String.format("Total Frames Processed: %d\n", totalFramesLoaded));
        sb.append(String.format("Frames Kept / Rejected: %d / %d\n", totalFramesKept, totalFramesRejected));
        sb.append(String.format("Total Raw Objects     : %d\n", totalRawObjectsExtracted));
        sb.append(String.format("Stationary Stars      : %d\n", totalStationaryStarsIdentified));
        sb.append(String.format("Moving Targets Found  : %d\n\n", totalMovingTargetsFound));

        if (!rejectedFrames.isEmpty()) {
            sb.append("--- QUALITY CONTROL: REJECTED FRAMES ---\n");
            for (FrameRejectionStat rej : rejectedFrames) {
                sb.append(String.format("  Frame %03d (%s) -> %s\n",
                        rej.frameIndex + 1, rej.filename, rej.reason));
            }
            sb.append("\n");
        }

        sb.append("--- EXTRACTION STATISTICS ---\n");
        for (FrameExtractionStat stat : frameExtractionStats) {
            sb.append(String.format("  Frame %03d (%s) -> %d objects extracted\n",
                    stat.frameIndex + 1, stat.filename, stat.objectCount));
        }
        sb.append("\n");

        sb.append("--- EXPORT PATH ---\n");
        sb.append(exportDirectoryPath).append("\n");
        sb.append("==================================================\n");

        return sb.toString();
    }
}