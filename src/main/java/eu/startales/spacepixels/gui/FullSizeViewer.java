package eu.startales.spacepixels.gui;

/** Creates the full-size stretch viewer. The stretch panel does not keep the window that owns it. */
interface FullSizeViewer {
    /** A new viewer bound to {@code stretch}. The panel retains and reuses it. */
    StretchedSequenceFrame create(StretchPanel stretch);
}
