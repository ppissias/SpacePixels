package eu.startales.spacepixels.gui;

import javax.swing.*;
import java.awt.*;

/** Scrollable form content that reflows to its viewport while retaining access below the window. */
class WidthTrackingPanel extends JPanel implements Scrollable {
    private final java.beans.PropertyChangeListener focusListener = event -> revealFocus(event.getNewValue());

    /** Starts with BorderLayout; callers may install a vertical form layout. */
    WidthTrackingPanel() {
        super(new BorderLayout());
    }

    /** Observes focus only while attached, so Tab also reveals controls below the viewport. */
    @Override public void addNotify() {
        super.addNotify();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", focusListener);
    }

    /** Detaches from the focus manager when the form or its viewer is disposed. */
    @Override public void removeNotify() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", focusListener);
        super.removeNotify();
    }

    /** Scrolls an already focused descendant into view; never changes the focus owner. */
    private void revealFocus(Object owner) {
        if (owner instanceof JComponent control && SwingUtilities.isDescendingFrom(control, this)) {
            control.scrollRectToVisible(new Rectangle(0, 0, control.getWidth(), control.getHeight()));
        }
    }

    /** Uses the content's natural size before a viewport has allocated space. */
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

    /** Scrolls a small, display-scaled step. */
    @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return UiStyle.space(16);
    }

    /** Keeps a little overlap when moving a viewport at a time. */
    @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(1, (orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width) - UiStyle.space(32));
    }

    /** Reflows unless the viewport is narrower than an individual input can fit. */
    @Override public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport && getParent().getWidth() >= getMinimumSize().width;
    }

    /** Fills spare height, but never compresses content that needs vertical scrolling. */
    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
    }
}
