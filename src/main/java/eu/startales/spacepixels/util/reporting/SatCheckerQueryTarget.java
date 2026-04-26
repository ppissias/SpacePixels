package eu.startales.spacepixels.util.reporting;

/**
 * Immutable lookup target describing the sky position, time window, and field-of-view radius used for SatChecker
 * report lookups.
 */
final class SatCheckerQueryTarget {
    final double raDegrees;
    final double decDegrees;
    final double fovRadiusDegrees;
    final long startTimestampMillis;
    final long endTimestampMillis;
    final long durationSeconds;
    final String summaryLabel;

    SatCheckerQueryTarget(double raDegrees,
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
