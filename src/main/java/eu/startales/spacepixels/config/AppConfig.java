package eu.startales.spacepixels.config;

public class AppConfig {
    public AppConfig copy() {
        AppConfig copy = new AppConfig();
        copy.variableStarReportOptions = variableStarReportOptions;
        copy.astapExecutablePath = astapExecutablePath;
        copy.observatoryCode = observatoryCode;
        copy.imageRA = imageRA;
        copy.imageDEC = imageDEC;
        copy.siteLat = siteLat;
        copy.siteLong = siteLong;
        copy.pixelSize = pixelSize;
        copy.focalLength = focalLength;
        copy.skyCatalogueStarMagnitude = skyCatalogueStarMagnitude;
        return copy;
    }

    /** Supplied by the detection profile/request; excluded from the separate astrometry configuration file. */
    public transient VariableStarReportOptions variableStarReportOptions = VariableStarReportOptions.DEFAULT;
    public String astapExecutablePath = "";
    public String observatoryCode = "";
    public String imageRA = "";
    public String imageDEC = "";
    public String siteLat = "";
    public String siteLong = "";
    public String pixelSize = "";
    public String focalLength = "";
    /** Faintest Gaia G magnitude fetched by Fetch Sky Catalogue. */
    public double skyCatalogueStarMagnitude = DEFAULT_SKY_CATALOGUE_STAR_MAGNITUDE;

    public static final double DEFAULT_SKY_CATALOGUE_STAR_MAGNITUDE = 15.0;
    public static final double MIN_SKY_CATALOGUE_STAR_MAGNITUDE = 10.0;
    public static final double MAX_SKY_CATALOGUE_STAR_MAGNITUDE = 18.0;

    /** The star depth, within the allowed range (an edited or old file may hold anything). */
    public double skyCatalogueStarMagnitude() {
        if (!(skyCatalogueStarMagnitude >= MIN_SKY_CATALOGUE_STAR_MAGNITUDE)) {
            return skyCatalogueStarMagnitude == 0 ? DEFAULT_SKY_CATALOGUE_STAR_MAGNITUDE : MIN_SKY_CATALOGUE_STAR_MAGNITUDE;
        }
        return Math.min(MAX_SKY_CATALOGUE_STAR_MAGNITUDE, skyCatalogueStarMagnitude);
    }
}
