/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;

import java.util.Locale;

/**
 * "Show full size" on the Image Stretch tab: the frames of the session at full resolution with the tab's stretch,
 * which applies at once while the window is open. Frames are read as Blink reads them, so colour frames show in
 * colour.
 */
public class StretchedSequenceFrame extends SequenceViewerFrame {

    private final StretchPanel stretchPanel;
    private FitsFileInformation[] files;

    public StretchedSequenceFrame(ApplicationWindow mainAppWindow, StretchPanel stretchPanel) {
        super("Full Size", "reading…", false, false);
        this.stretchPanel = stretchPanel;
        if (mainAppWindow.getSession() != null) setSkyCatalogueModel(mainAppWindow.getSession().getSkyCatalogue());
        setLocationRelativeTo(mainAppWindow.getFrame());
    }

    /** Shows the frames of the session from {@code startIndex}. */
    public void openSequence(FitsFileInformation[] files, int startIndex) {
        this.files = files == null ? null : files.clone();
        openAt(startIndex, stretchPanel, this.files);
    }

    @Override
    protected void onFrameMetadataReplaced(FitsFileInformation previous, FitsFileInformation updated) {
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            if (files[i] == previous || (files[i] != null && files[i].getFilePath() != null
                    && files[i].getFilePath().equals(previous.getFilePath()))) {
                files[i] = updated;
            }
        }
    }

    @Override
    protected int frameCount() {
        return files == null ? 0 : files.length;
    }

    @Override
    protected FitsFileInformation infoOf(int index) {
        return files[index];
    }

    @Override
    protected Result prepare(int index) throws Exception {
        BlinkSequence.Frame frame = BlinkSequence.load(files[index]);
        String size = String.format(Locale.US, "%d × %d", frame.getWidth(), frame.getHeight());
        return Result.of(frame, null, frame.getChannelCount() == 3 ? size + " · colour" : size);
    }

    @Override
    protected void released() {
        files = null;
    }
}
