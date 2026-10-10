package eu.startales.spacepixels.events;

/** A frame of the blink sequence was read; {@code loaded} of {@code total} are ready. */
public class BlinkLoadProgressEvent {
    private final int loaded;
    private final int total;

    public BlinkLoadProgressEvent(int loaded, int total) {
        this.loaded = loaded;
        this.total = total;
    }

    public int getLoaded() { return loaded; }
    public int getTotal() { return total; }
}
