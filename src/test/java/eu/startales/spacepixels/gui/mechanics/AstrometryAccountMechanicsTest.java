package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.SpacePixelsAppConfigIO;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.fixture.JTextComponentFixture;
import org.junit.Test;

import javax.swing.JPasswordField;
import java.io.File;

import static org.junit.Assert.assertEquals;

/**
 * Astrometry Config: the Astrometry.net account choice and key are applied and kept on the test's own home, before
 * any import. Check is not clicked, so nothing reaches Astrometry.net. Run with {@code ./gradlew guiMechanicsTest}.
 */
public class AstrometryAccountMechanicsTest extends MechanicsTestCase {

    private static final String KEY = "abcdefghijklmnop";

    @Test(timeout = 180_000)
    public void theAccountChoiceAndTheKeyAreKept() throws Exception {
        window().tabbedPane().selectTab("Astrometry Config");

        window().radioButton(VisibleText.radioButton("Anonymous")).requireSelected();
        keyField().requireDisabled();
        waitFor("the tab says solves are anonymous",
                () -> showingLabel("Online solves log in anonymously.").contains("anonymously"));

        window().radioButton(VisibleText.radioButton("My API key")).click();
        keyField().requireEnabled();
        waitFor("an empty key is pointed out", () -> showingLabel("Enter your key").contains("Enter your key"));
        keyField().enterText(KEY);
        waitFor("the key is in use", () -> showingLabel("Online solves use your key.").contains("your key"));
        waitForSavedAccount(true, KEY);

        reopenWindow();
        window().tabbedPane().selectTab("Astrometry Config");
        window().radioButton(VisibleText.radioButton("My API key")).requireSelected();
        assertEquals(KEY, new String(((JPasswordField) keyField().target()).getPassword()));

        window().radioButton(VisibleText.radioButton("Anonymous")).click();
        keyField().requireDisabled();
        waitFor("the tab says solves are anonymous again",
                () -> showingLabel("Online solves log in anonymously.").contains("anonymously"));
        waitForSavedAccount(false, KEY);
    }

    private JTextComponentFixture keyField() {
        return window().textBox(new GenericTypeMatcher<>(JPasswordField.class, true) {
            @Override
            protected boolean isMatching(JPasswordField component) {
                return true;
            }
        });
    }

    /** What the next start reads, once the background save has written it; the key is kept while Anonymous is chosen. */
    private void waitForSavedAccount(boolean ownKey, String key) {
        File file = new File(System.getProperty("user.home"), SpacePixelsAppConfigIO.DEFAULT_FILENAME);
        waitFor("spacepixels_app.json holds the account (own key " + ownKey + ")", () -> {
            try {
                AppConfig saved = SpacePixelsAppConfigIO.load(file);
                return saved.astrometryNetUseOwnKey == ownKey && key.equals(saved.astrometryNetApiKey);
            } catch (Exception notYetWritten) {
                return false;
            }
        });
    }
}
