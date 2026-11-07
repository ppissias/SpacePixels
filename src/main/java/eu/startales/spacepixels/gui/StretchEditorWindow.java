package eu.startales.spacepixels.gui;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** Resizable session-bound stretch workspace; closing hides it while retaining the shared display settings. EDT only. */
final class StretchEditorWindow extends JFrame implements SessionViewer {
    private final StretchPanel panel;

    /** Hosts the application's one stretch panel; preview work ends whenever this window hides. */
    StretchEditorWindow(Component owner, StretchPanel panel) {
        super("Image Stretch");
        this.panel = panel;
        AppIcon.apply(this);
        setContentPane(panel);
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /** Closing the workspace releases its pictures, keeping live stretch settings for other viewers. */
            @Override public void windowClosing(WindowEvent event) { panel.closeEditorPreview(); }
        });
        ViewerSupport.bind(getRootPane(), "ESCAPE", "close-stretch-editor", () -> setVisible(false));
        Rectangle usable = WindowSizing.usableBounds(owner);
        setSize(Math.min(usable.width, UiStyle.space(1200)), Math.min(usable.height, UiStyle.space(800)));
        setLocationRelativeTo(owner);
        WindowSizing.install(this, 700, 500);
    }

    /** Raises the existing modeless workspace, preserving the caller's playback settings. */
    void showEditor() {
        setVisible(true);
        ViewerSupport.bringToFront(this);
    }

    /** Drops pending renders and frame resources, detaches the shared panel and disposes this session's window. */
    @Override public void closeForSessionChange() {
        setVisible(false);
        panel.closeEditorPreview();
        // Keep the live stretch panel available for the next session's editor.
        setContentPane(new javax.swing.JPanel());
        dispose();
    }
}
