package eu.startales.spacepixels.platesolve;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;

/** An Astrometry.net failure is explained in words, also when the connection error carries no message. */
public class AstrometryNetFailureTest {

    private static final String UNREACHABLE = "Could not reach Astrometry.net (nova.astrometry.net). Check the internet connection.";
    private static final String OWN_KEY_REJECTED = "Astrometry.net rejected the API key. Check the key on the Astrometry Config tab, or choose Anonymous.";
    /** JPlateSolve 1.0.1 first refused a login with this message, without a reason. */
    private static final String REFUSED_LOGIN = "Astrometry.net login failed.";
    /** JPlateSolve passes on nova's reason since 2026-10-22, as received for an unknown key. */
    private static final String UNKNOWN_KEY_LOGIN = "Astrometry.net login failed: bad apikey";
    /** JPlateSolve 1.0.0 passed on the server's answer to an unknown key, as received on 2026-10-21. */
    private static final String BAD_KEY_LOGIN_1_0_0 = "Astrometry.net login failed: {\"status\": \"error\", \"errormessage\": \"bad apikey\"}";

    @Test
    public void aLostConnectionIsNamedEvenWithoutAMessage() {
        // As Java's HttpClient reports a failed connection while offline: no message anywhere in the chain.
        ConnectException offline = new ConnectException();
        offline.initCause(new UnresolvedAddressException());
        assertEquals(UNREACHABLE, AstrometryNetAccount.failureMessage(offline, false));
        assertEquals(UNREACHABLE, AstrometryNetAccount.failureMessage(new IOException(new UnknownHostException("nova.astrometry.net")), false));
        assertEquals("Astrometry.net did not answer in time. Try again later.",
                AstrometryNetAccount.failureMessage(new HttpTimeoutException("request timed out"), true));
    }

    @Test
    public void aRefusedLoginIsNamedForTheAccountInUse() {
        assertEquals(OWN_KEY_REJECTED, AstrometryNetAccount.failureMessage(new IOException(REFUSED_LOGIN), true));
        assertEquals("Astrometry.net did not accept the anonymous login. Enter your own API key on the Astrometry Config tab.",
                AstrometryNetAccount.failureMessage(new IOException(REFUSED_LOGIN), false));
        assertEquals(OWN_KEY_REJECTED, AstrometryNetAccount.failureMessage(new IOException(BAD_KEY_LOGIN_1_0_0), true));
        assertEquals(OWN_KEY_REJECTED, AstrometryNetAccount.failureMessage(new IOException(UNKNOWN_KEY_LOGIN), true));
        assertEquals("found further down the cause chain too",
                OWN_KEY_REJECTED, AstrometryNetAccount.failureMessage(new IOException("wrapped", new IOException(REFUSED_LOGIN)), true));
    }

    @Test
    public void aLoginRefusedForAnotherReasonSaysWhyWithoutBlamingTheKey() {
        assertEquals("Astrometry.net refused the login: service temporarily unavailable",
                AstrometryNetAccount.failureMessage(new IOException("Astrometry.net login failed: service temporarily unavailable"), true));
    }

    @Test
    public void otherFailuresGiveTheirFirstMessageOrTheirType() {
        assertEquals("Astrometry.net could not solve the image: Submit request was not successful",
                AstrometryNetAccount.failureMessage(new IOException(null, new IllegalStateException("Submit request was not successful")), false));
        assertEquals("Astrometry.net could not solve the image: Plate solve exceeded its total time budget.",
                AstrometryNetAccount.failureMessage(new TimeoutException("Plate solve exceeded its total time budget."), false));
        assertEquals("Astrometry.net could not solve the image: IllegalArgumentException",
                AstrometryNetAccount.failureMessage(new IllegalArgumentException(), true));
    }
}
