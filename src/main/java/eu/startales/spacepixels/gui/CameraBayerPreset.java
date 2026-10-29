package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.preparation.PreparationRequest.Bayer;

/**
 * Desktop shortcuts for nominal camera mosaics, never guesses about file row order or cropping.
 * Manufacturer references and the distinction from decoded RAW metadata are recorded in
 * {@code docs/features/preparation-integrations.md}. Custom preserves the current controls.
 */
enum CameraBayerPreset {
    METADATA("From file metadata (recommended)", Bayer.METADATA),
    CUSTOM("Custom", null),
    CANON_RAW("Canon EOS RAW (CR2 / CR3) — file metadata", Bayer.METADATA),
    NIKON_RAW("Nikon RAW (NEF) — file metadata", Bayer.METADATA),
    SONY_RAW("Sony Alpha RAW (ARW) — file metadata", Bayer.METADATA),
    ZWO_ASI120MC("ZWO ASI120MC / MC-S", Bayer.GRBG),
    ZWO_ASI1600MC("ZWO ASI1600MC / Pro", Bayer.GRBG),
    ZWO_ASI178MC("ZWO ASI178MC", Bayer.RGGB),
    ZWO_ASI183MC("ZWO ASI183MC / Pro", Bayer.RGGB),
    ZWO_ASI294MC("ZWO ASI294MC / Pro", Bayer.RGGB),
    ZWO_ASI533MC("ZWO ASI533MC Pro", Bayer.RGGB),
    ZWO_ASI2600MC("ZWO ASI2600MC Pro", Bayer.RGGB),
    QHY8L("QHY8L-C", Bayer.GBRG),
    QHY10("QHY10-C", Bayer.RGGB),
    QHY12("QHY12-C", Bayer.BGGR),
    QHY163C("QHY163C", Bayer.GRBG),
    QHY168C("QHY168C", Bayer.RGGB),
    QHY174C("QHY174C", Bayer.RGGB),
    QHY178C("QHY178C", Bayer.GBRG),
    QHY183C("QHY183C", Bayer.RGGB),
    QHY224C("QHY224C", Bayer.GBRG),
    QHY247C("QHY247C", Bayer.RGGB),
    QHY268C("QHY268C", Bayer.RGGB),
    QHY290C("QHY290C", Bayer.GBRG),
    QHY294C("QHY294C", Bayer.RGGB),
    QHY410C("QHY410C", Bayer.RGGB),
    QHY533C("QHY533C", Bayer.GBRG),
    QHY600C("QHY600C", Bayer.RGGB),
    PLAYER_ONE_ARES("Player One Ares-C Pro", Bayer.RGGB),
    PLAYER_ONE_ARTEMIS("Player One Artemis-C Pro", Bayer.RGGB),
    PLAYER_ONE_POSEIDON("Player One Poseidon-C Pro", Bayer.RGGB),
    PLAYER_ONE_URANUS("Player One Uranus-C Pro", Bayer.RGGB);

    private final String label;
    private final Bayer pattern;

    /** Captures only the published nominal pattern, or null for the editable custom choice. */
    CameraBayerPreset(String label, Bayer pattern) { this.label = label; this.pattern = pattern; }

    /** Returns the pattern to apply; Custom leaves all existing interpretation controls untouched. */
    Bayer pattern() { return pattern; }

    /** Includes the pattern so the observer can review the shortcut before selecting it. */
    @Override public String toString() {
        return pattern == null || pattern == Bayer.METADATA ? label : label + " — " + pattern;
    }
}
