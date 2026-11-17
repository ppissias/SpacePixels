/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.gui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The About window shows the repository's own license and takes its copyright line from it. */
public class AboutDialogTest {
    @Test
    public void theLicenseIsTheRepositoryLicense() throws Exception {
        String repository = new String(Files.readAllBytes(Path.of("LICENSE")), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        String shown = AboutDialog.licenseText();
        assertEquals(repository, shown);
        assertTrue(shown.startsWith("BSD 2-Clause License"));
        assertTrue(AboutDialog.copyright(shown), AboutDialog.copyright(shown).matches("Copyright © 2020-[0-9]{4}, Petros Pissias"));
    }

    @Test
    public void theCopyrightLineUsesTheSign() {
        assertEquals("Copyright © 2020-2030, Someone", AboutDialog.copyright("MIT\n\n  Copyright (c) 2020-2030, Someone\nmore"));
    }
}
