package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BayerMosaic;
import eu.startales.spacepixels.preparation.PreparationRequest.Bayer;
import eu.startales.spacepixels.preparation.PreparationRequest.RowOrder;

/**
 * Desktop shortcuts for nominal camera mosaics and the usual file row order.
 * Row order is how the file is stored, not a display flip. RAW shortcuts leave both to the decoder.
 * The Seestar entries take the pattern from the file, which every Seestar writes, and supply only the row order
 * the files leave out. Manufacturer references and the distinction from decoded RAW metadata are recorded in
 * {@code docs/features/preparation-integrations.md}. Custom preserves the current controls.
 */
enum CameraBayerPreset {
    METADATA("From file metadata (recommended)", Bayer.METADATA),
    CUSTOM("Custom", (Bayer) null),
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
    SEESTAR_S30("ZWO Seestar S30", "Seestar S30"),
    SEESTAR_S30_PRO("ZWO Seestar S30 Pro", "Seestar S30 Pro"),
    SEESTAR_S50("ZWO Seestar S50", "Seestar S50"),
    SEESTAR_S50_PRO("ZWO Seestar S50 Pro", "Seestar S50 Pro"),
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
    SBIG_ST2000XCM("SBIG ST-2000XCM", Bayer.RGGB),
    SBIG_STF8300C("SBIG STF-8300C", Bayer.RGGB),
    SVBONY_SV605CC("SVBONY SV605CC", Bayer.GRBG);

    private final String label;
    private final Bayer pattern;
    private final RowOrder rowOrder;
    /** The model a Seestar names in its header, or null for presets that are never chosen from the header. */
    private final String headerModel;

    /** A camera preset uses top-down storage. RAW and metadata shortcuts keep the decoder's row order. */
    CameraBayerPreset(String label, Bayer pattern) {
        this.label = label;
        this.pattern = pattern;
        this.rowOrder = pattern == null || pattern == Bayer.METADATA ? null : RowOrder.TOP_DOWN;
        this.headerModel = null;
    }

    /**
     * A Seestar: the pattern comes from the file and the rows are top-down, which the files do not state. On the
     * S30 Pro the two green phases of the header's GRBG start on the first stored row; the other models are assumed
     * to store their rows the same way.
     */
    CameraBayerPreset(String label, String headerModel) {
        this.label = label;
        this.pattern = Bayer.METADATA;
        this.rowOrder = RowOrder.TOP_DOWN;
        this.headerModel = headerModel;
    }

    /** Returns the pattern to apply; Custom leaves all existing interpretation controls untouched. */
    Bayer pattern() { return pattern; }

    /** Returns the file row order to apply, or null when the preset must not replace it. */
    RowOrder rowOrder() {
        if (rowOrder != null) return rowOrder;
        return pattern == Bayer.METADATA ? RowOrder.METADATA : null;
    }

    /** The camera name without the pattern, for the readiness summary and the prepared frames' provenance. */
    String label() { return label; }

    /** Whether this entry names a camera, rather than the file-metadata default or Custom. */
    boolean camera() { return this != METADATA && this != CUSTOM; }

    /**
     * The preset of the camera that wrote a mosaic, or null when no preset is chosen from headers. A Seestar names
     * itself in {@code CREATOR} ("ZWO Seestar S30 Pro") or {@code INSTRUME} ("Seestar S50"); the whole model name
     * must match, so an S30 is never taken for an S30 Pro.
     */
    static CameraBayerPreset forCamera(BayerMosaic mosaic) {
        if (mosaic == null) return null;
        for (CameraBayerPreset preset : values()) {
            if (preset.headerModel == null) continue;
            if (preset.headerModel.equalsIgnoreCase(mosaic.instrument()) || preset.headerModel.equalsIgnoreCase(mosaic.creator())
                    || ("ZWO " + preset.headerModel).equalsIgnoreCase(mosaic.creator())) return preset;
        }
        return null;
    }

    /** Includes the pattern and row order so the observer can review the shortcut before selecting it. */
    @Override public String toString() {
        if (pattern == null || pattern == Bayer.METADATA && rowOrder == null) return label;
        String rows = rowOrder == RowOrder.BOTTOM_UP ? "bottom-up" : "top-down";
        return label + " — " + (pattern == Bayer.METADATA ? "pattern from file" : pattern) + ", " + rows;
    }
}
