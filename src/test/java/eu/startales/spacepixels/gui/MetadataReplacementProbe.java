package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.FitsFileInformation;

import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reaches the window's package-private WCS replacement and the inspection windows' metadata, for the GUI tests. */
public final class MetadataReplacementProbe {

    private MetadataReplacementProbe() {
    }

    /** Applies a successful WCS write of {@code file} in the current session, as the write job's outcome does. */
    public static FitsFileInformation applyWcsHeader(MainApplicationPanel panel, FitsFileInformation file,
                                                     Map<String, String> header) {
        return panel.applyWcsHeaderReplacement(file, header);
    }

    /** The inspection windows the main panel still keeps. */
    public static int registeredInspections(MainApplicationPanel panel) {
        return panel.openInspectionCount();
    }

    /** The showing Manual Transient Inspection windows. */
    public static List<Window> showingInspections() {
        List<Window> showing = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window instanceof TransientInspectionFrame && window.isShowing()) {
                showing.add(window);
            }
        }
        return showing;
    }

    /** The metadata an inspection window holds for the file at {@code path}, or null when it has none. */
    public static FitsFileInformation inspectedInfo(Window inspection, String path) {
        TransientInspectionFrame frame = (TransientInspectionFrame) inspection;
        for (int i = 0; i < frame.frameCount(); i++) {
            FitsFileInformation info = frame.infoOf(i);
            if (info != null && path.equals(info.getFilePath())) {
                return info;
            }
        }
        return null;
    }

    /** The metadata of the frame an inspection window shows. */
    public static FitsFileInformation shownInfo(Window inspection) {
        return ((TransientInspectionFrame) inspection).shownFrameInfo();
    }

    /** Offers a replacement directly to one inspection window, as the main panel would. */
    public static void replace(Window inspection, FitsFileInformation previous, FitsFileInformation updated) {
        ((TransientInspectionFrame) inspection).replaceFrameMetadata(previous, updated);
    }
}
