package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.preparation.PreparationRequest.Bayer;
import eu.startales.spacepixels.preparation.PreparationRequest.RowOrder;

/**
 * Desktop shortcuts for nominal camera mosaics and the usual file row order.
 * Row order is how the file is stored, not a display flip. RAW shortcuts leave both to the decoder.
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
    ZWO_ASI071MC("ZWO ASI071MC Pro", Bayer.RGGB),
    ZWO_ASI2400MC("ZWO ASI2400MC Pro", Bayer.RGGB),
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
    PLAYER_ONE_URANUS("Player One Uranus-C Pro", Bayer.RGGB),
    SBIG_ST2000XCM("SBIG ST-2000XCM", Bayer.BGGR, RowOrder.BOTTOM_UP),
    SBIG_STF8300C("SBIG STF-8300C", Bayer.BGGR, RowOrder.BOTTOM_UP),
    SVBONY_SV605CC("SVBONY SV605CC", Bayer.GRBG);

    private final String label;
    private final Bayer pattern;
    private final RowOrder rowOrder;

    /** A CMOS preset uses top-down storage. RAW and metadata shortcuts keep the decoder's row order. */
    CameraBayerPreset(String label, Bayer pattern) {
        this(label, pattern, pattern == null || pattern == Bayer.METADATA ? null : RowOrder.TOP_DOWN);
    }

    /** A camera whose usual files are stored bottom-up, such as classic SBIG FITS. */
    CameraBayerPreset(String label, Bayer pattern, RowOrder rowOrder) {
        this.label = label;
        this.pattern = pattern;
        this.rowOrder = rowOrder;
    }

    /** Returns the pattern to apply; Custom leaves all existing interpretation controls untouched. */
    Bayer pattern() { return pattern; }

    /** Returns the file row order to apply, or null when the preset must not replace it. */
    RowOrder rowOrder() { return pattern == Bayer.METADATA ? RowOrder.METADATA : rowOrder; }

    /** Includes the pattern and row order so the observer can review the shortcut before selecting it. */
    @Override public String toString() {
        if (pattern == null || pattern == Bayer.METADATA) return label;
        return label + " — " + pattern + ", " + (rowOrder == RowOrder.BOTTOM_UP ? "bottom-up" : "top-down");
    }
}
