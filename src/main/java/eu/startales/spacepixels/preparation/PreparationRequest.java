package eu.startales.spacepixels.preparation;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Immutable observer-selected inputs and interpretation; workers never read live window controls. */
public record PreparationRequest(Path executable, Map<PreparationInputs.Role, Path> inputs,
                                 Sensor sensor, Bayer bayer, RowOrder rowOrder, int offsetX, int offsetY,
                                 Path outputParent, boolean referenceStack, boolean keepWorkingFiles) {
    /** Sensor interpretation is independent of the input file's number of image planes. */
    public enum Sensor {
        MONO("Monochrome"), CFA("Colour (Bayer CFA)"), RGB("Already debayered RGB");
        private final String label;
        /** Defines a visible sensor interpretation. */
        Sensor(String label) { this.label = label; }
        /** The user-facing choice. */
        @Override public String toString() { return label; }
    }

    /** A metadata choice never silently substitutes a default mosaic. */
    public enum Bayer { METADATA, RGGB, BGGR, GRBG, GBRG;
        /** Describes the automatic choice without implying that an unresolved pattern is known. */
        @Override public String toString() { return this == METADATA ? "Resolve from metadata" : name(); }
    }

    /** Row orientation is applied once by the external tool, using the effective FITS pixel origin. */
    public enum RowOrder {
        METADATA("From metadata"), TOP_DOWN("Top-down"), BOTTOM_UP("Bottom-up");
        private final String label;
        /** Defines a row-order choice. */
        RowOrder(String label) { this.label = label; }
        /** The row-order label shown in the form. */
        @Override public String toString() { return label; }
    }

    /** Copies all collections and normalizes paths before the request leaves the EDT. */
    public PreparationRequest {
        executable = Objects.requireNonNull(executable).toAbsolutePath().normalize();
        inputs = Map.copyOf(inputs);
        Objects.requireNonNull(sensor);
        Objects.requireNonNull(bayer);
        Objects.requireNonNull(rowOrder);
        outputParent = Objects.requireNonNull(outputParent).toAbsolutePath().normalize();
        if ((offsetX & ~1) != 0 || (offsetY & ~1) != 0) throw new IllegalArgumentException("Bayer offsets must be 0 or 1 pixel.");
    }
}
