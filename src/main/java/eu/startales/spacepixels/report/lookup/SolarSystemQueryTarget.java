package eu.startales.spacepixels.report.lookup;

/**
 * Immutable lookup target describing the pixel-space position, epoch, and search radius used for solar-system
 * report lookups.
 */
public final class SolarSystemQueryTarget {
    public final double pixelX;
    public final double pixelY;
    public final long timestampMillis;
    public final double searchRadiusDegrees;
    public final String summaryLabel;

    public SolarSystemQueryTarget(double pixelX,
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
