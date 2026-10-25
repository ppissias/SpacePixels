package eu.startales.spacepixels.report.lookup;

/**
 * Immutable lookup target describing the sky position, time window, and field-of-view radius used for SatChecker
 * report lookups.
 */
public final class SatCheckerQueryTarget {
    public final double raDegrees;
    public final double decDegrees;
    public final double fovRadiusDegrees;
    public final long startTimestampMillis;
    public final long endTimestampMillis;
    public final long durationSeconds;
    public final String summaryLabel;

    public SatCheckerQueryTarget(double raDegrees,
                          double decDegrees,
                          double fovRadiusDegrees,
                          long startTimestampMillis,
                          long endTimestampMillis,
                          long durationSeconds,
                          String summaryLabel) {
        this.raDegrees = raDegrees;
        this.decDegrees = decDegrees;
        this.fovRadiusDegrees = fovRadiusDegrees;
        this.startTimestampMillis = startTimestampMillis;
        this.endTimestampMillis = endTimestampMillis;
        this.durationSeconds = durationSeconds;
        this.summaryLabel = summaryLabel;
    }
}
