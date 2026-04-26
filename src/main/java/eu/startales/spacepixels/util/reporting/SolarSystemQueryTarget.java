package eu.startales.spacepixels.util.reporting;

/**
 * Immutable lookup target describing the pixel-space position, epoch, and search radius used for solar-system
 * report lookups.
 */
final class SolarSystemQueryTarget {
    final double pixelX;
    final double pixelY;
    final long timestampMillis;
    final double searchRadiusDegrees;
    final String summaryLabel;

    SolarSystemQueryTarget(double pixelX,
                           double pixelY,
                           long timestampMillis,
                           double searchRadiusDegrees,
                           String summaryLabel) {
        this.pixelX = pixelX;
        this.pixelY = pixelY;
        this.timestampMillis = timestampMillis;
        this.searchRadiusDegrees = searchRadiusDegrees;
        this.summaryLabel = summaryLabel;
    }
}
