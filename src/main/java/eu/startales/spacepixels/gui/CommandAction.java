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

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.function.Supplier;

/**
 * A workflow command with several entry points. Its button, shortcut, table menu item and Overview button all use
 * this one action, so they are enabled together, show the same tooltip and run the same code.
 */
final class CommandAction extends AbstractAction {

    private final CommandAvailability.Command command;
    private final Supplier<CommandAvailability.Facts> facts;
    private final Runnable run;
    private String description;

    /**
     * @param description the tooltip; while the command is unavailable, the reason is added to it
     * @param facts       the facts as they are when asked, read on the Swing thread
     */
    CommandAction(String name, String description, CommandAvailability.Command command,
                  Supplier<CommandAvailability.Facts> facts, Runnable run) {
        super(name);
        this.command = command;
        this.facts = facts;
        this.run = run;
        this.description = description;
        putValue(SHORT_DESCRIPTION, description);
    }

    /** Runs the command if it is available now: a shortcut pressed late, or a control not yet refreshed, does nothing. */
    @Override
    public void actionPerformed(ActionEvent e) {
        if (CommandAvailability.of(command, facts.get()).enabled()) {
            run.run();
        }
    }

    /** Takes the enabled state and the tooltip from the facts as they are now. */
    void refresh() {
        CommandAvailability.Availability availability = CommandAvailability.of(command, facts.get());
        setEnabled(availability.enabled());
        if (availability.enabled()) {
            putValue(SHORT_DESCRIPTION, description);
        } else {
            String body = description.replaceFirst("^<html>", "").replaceFirst("</html>$", "");
            putValue(SHORT_DESCRIPTION, "<html>" + body + "<br><i>Not available: " + availability.reason() + "</i></html>");
        }
    }

    /** Replaces the tooltip, for example with the date of a saved run. */
    void setDescription(String description) {
        this.description = description;
        refresh();
    }
}
