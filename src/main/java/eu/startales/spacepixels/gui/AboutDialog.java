/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.BuildInfo;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** The program's name, logo, version, copyright and license, opened from the Help menu. */
final class AboutDialog extends JDialog {

    /** The project's home, where its source, releases and issues are. */
    static final String PROJECT_URL = "https://github.com/ppissias/SpacePixels";
    /** The license as shipped in the jar, copied there from the repository's LICENSE file by the build. */
    private static final String LICENSE_RESOURCE = "LICENSE.txt";

    /** Builds the window on the EDT; {@code browser} opens links and {@code acknowledgements} opens the credits. */
    AboutDialog(Window owner, Consumer<String> browser, Runnable acknowledgements) {
        super(owner, "About SpacePixels", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        AppIcon.apply(this);

        String license = licenseText();
        JPanel heading = new JPanel(new BorderLayout(UiStyle.space(18), 0));
        heading.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(16), UiStyle.space(18), UiStyle.space(10), UiStyle.space(18)));
        JLabel logo = new JLabel(logo(UiStyle.space(96)));
        logo.setVerticalAlignment(SwingConstants.TOP);
        heading.add(logo, BorderLayout.WEST);

        JPanel lines = new JPanel();
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        JLabel name = new JLabel("SpacePixels");
        name.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD, UiStyle.bodyFont().getSize2D() * 1.6f));
        lines.add(name);
        lines.add(Box.createVerticalStrut(UiStyle.space(4)));
        lines.add(new JLabel("Version " + BuildInfo.version("spacepixels") + "  ·  JTransient " + BuildInfo.version("jtransient")));
        lines.add(Box.createVerticalStrut(UiStyle.space(10)));
        lines.add(new JLabel(copyright(license)));
        lines.add(new JLabel("Open source under the BSD 2-Clause License, shown below."));
        lines.add(Box.createVerticalStrut(UiStyle.space(8)));
        JButton home = new JButton(PROJECT_URL.replace("https://", ""));
        home.putClientProperty("JButton.buttonType", "borderless");
        home.setForeground(UiStyle.linkColor());
        home.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        home.setBorder(BorderFactory.createEmptyBorder());
        home.setToolTipText("Source code, releases and issues");
        home.addActionListener(e -> browser.accept(PROJECT_URL));
        lines.add(home);
        heading.add(lines, BorderLayout.CENTER);

        JTextArea text = new JTextArea(license);
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiStyle.bodyFont().getSize() - 1));
        text.setCaretPosition(0);
        text.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(8), UiStyle.space(8), UiStyle.space(8), UiStyle.space(8)));
        JScrollPane scroll = new JScrollPane(text);
        JLabel notices = new JLabel("The bundled libraries' licenses are in NOTICE.txt, beside LICENSE in the SpacePixels folder.");
        notices.setForeground(UiStyle.helpColor());
        JPanel body = new JPanel(new BorderLayout(0, UiStyle.space(6)));
        body.setBorder(BorderFactory.createEmptyBorder(0, UiStyle.space(18), 0, UiStyle.space(18)));
        body.add(scroll, BorderLayout.CENTER);
        body.add(notices, BorderLayout.SOUTH);

        JButton credits = new JButton("Acknowledgements");
        credits.setToolTipText("Contributors, external services, catalogues and software used by SpacePixels.");
        credits.addActionListener(e -> acknowledgements.run());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(close);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiStyle.space(8), UiStyle.space(10)));
        buttons.add(credits);
        buttons.add(close);

        JPanel content = new JPanel(new BorderLayout());
        content.add(heading, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        WindowSizing.install(this, 560, 480);
        Rectangle bounds = WindowSizing.usableBounds(this);
        setSize(Math.min(UiStyle.space(640), bounds.width), Math.min(UiStyle.space(560), bounds.height));
        setLocationRelativeTo(owner);
    }

    /** The license text shipped in the jar, or a pointer to the project when the jar has none (a development run). */
    static String licenseText() {
        try (InputStream in = AboutDialog.class.getResourceAsStream(LICENSE_RESOURCE)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        } catch (IOException ignored) {
            // Falls through to the pointer below.
        }
        return "BSD 2-Clause License\n\nThe full text is in the LICENSE file beside the program and at " + PROJECT_URL + ".";
    }

    /** The license's copyright line, written with the © sign, so the years and holder are kept in one place. */
    static String copyright(String license) {
        for (String line : license.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("Copyright")) return trimmed.replace("(c)", "©").replace("(C)", "©");
        }
        return "Copyright © Petros Pissias";
    }

    /** The application logo at {@code size} pixels, scaled down from the largest icon. */
    private static Icon logo(int size) {
        try (InputStream in = AboutDialog.class.getResourceAsStream("icons/spacepixels-512.png")) {
            if (in != null) {
                BufferedImage image = ImageIO.read(in);
                return new ImageIcon(image.getScaledInstance(size, size, Image.SCALE_SMOOTH));
            }
        } catch (IOException ignored) {
            // The window is still useful without the logo.
        }
        return null;
    }
}
