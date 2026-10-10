package eu.startales.spacepixels.events;

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;

/** All frames of the blink are read and can be shown. */
public class BlinkSequenceLoadedEvent {
    private final BlinkSequence sequence;
    private final FitsFileInformation[] files;

    public BlinkSequenceLoadedEvent(BlinkSequence sequence, FitsFileInformation[] files) {
        this.sequence = sequence;
        this.files = files;
    }

    public BlinkSequence getSequence() { return sequence; }
    public FitsFileInformation[] getFiles() { return files; }
}
