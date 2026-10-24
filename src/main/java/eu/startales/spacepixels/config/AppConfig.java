package eu.startales.spacepixels.config;

public class AppConfig {
    public AppConfig copy() {
        AppConfig copy = new AppConfig();
        copy.astapExecutablePath = astapExecutablePath;
        copy.astrometryNetUseOwnKey = astrometryNetUseOwnKey;
        copy.astrometryNetApiKey = astrometryNetApiKey;
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

    public String astapExecutablePath = "";
    /** False (the default): Astrometry.net solves log in anonymously. True: with {@link #astrometryNetApiKey}. */
    public boolean astrometryNetUseOwnKey = false;
    /** The observer's own Astrometry.net API key. Kept while solving anonymously, so it can be chosen again. */
    public String astrometryNetApiKey = "";
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

    /** The key Astrometry.net solves log in with: the observer's own key, or "" for an anonymous login. */
    public String astrometryNetApiKeyInUse() {
        if (!astrometryNetUseOwnKey || astrometryNetApiKey == null) {
            return "";
        }
        return astrometryNetApiKey.trim();
    }
}
