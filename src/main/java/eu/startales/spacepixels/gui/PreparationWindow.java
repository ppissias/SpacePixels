package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.preparation.FramePreparationService;
import eu.startales.spacepixels.session.ApplicationStorage;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One application-owned, modeless preparation window, independent of the currently imported processing session. */
final class PreparationWindow extends JDialog {
    private final PreparationPanel panel;
    private boolean preparing;

    /** Wires explicit processing/import callbacks and a close path that cancels only this window's admitted job. */
    PreparationWindow(JFrame owner, ApplicationSettings settings, ApplicationStorage storage, BooleanSupplier closing,
                      Consumer<PreparationPanel.Selection> run, Runnable cancel) {
        super(owner, "Prepare observing session", false);
        panel = new PreparationPanel(settings, storage, closing, selection -> { preparing = true; run.accept(selection); });
        setContentPane(panel);
        AppIcon.apply(this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /** Closing cancels this preparation and prevents its pending automatic follow-up. */
            @Override public void windowClosing(WindowEvent event) { if (preparing) cancel.run(); dispose(); }
        });
        WindowSizing.install(this, 860, 520);
        Rectangle bounds = WindowSizing.usableBounds(this);
        setSize(Math.min(UiStyle.space(1200), bounds.width), Math.min(UiStyle.space(900), bounds.height));
        setLocationRelativeTo(owner);
    }

    /** Synchronizes this independent form with the application's single processing slot. */
    void setBusy(boolean busy) { panel.setBusy(busy); }

    /** Retains completed products in the window before an optional import starts. */
    void completed(FramePreparationService.Result result) { preparing = false; panel.completed(result); }

    /** Unlocks the form and explains a failed, cancelled or refused operation. */
    void failed(String message) { preparing = false; panel.failed(message); }

    /** On the EDT, closes only after successful import, then notifies the observer from the application window. */
    void imported(Runnable notifyImported) { dispose(); notifyImported.run(); }

    /** Closes owned probes without changing application settings or the currently imported session. */
    @Override public void dispose() { if (panel != null) panel.close(); super.dispose(); }
}
