package eu.startales.spacepixels.util;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;

import static org.junit.Assert.assertEquals;

/** An Astrometry.net failure is explained in words, also when the connection error carries no message. */
public class AstrometryNetFailureTest {

    private static final String UNREACHABLE = "Could not reach Astrometry.net (nova.astrometry.net). Check the internet connection.";

    @Test
    public void aLostConnectionIsNamedEvenWithoutAMessage() {
        // As Java's HttpClient reports a failed connection while offline: no message anywhere in the chain.
        ConnectException offline = new ConnectException();
        offline.initCause(new UnresolvedAddressException());
        assertEquals(UNREACHABLE, PlateSolveService.astrometryNetFailure(offline));
        assertEquals(UNREACHABLE, PlateSolveService.astrometryNetFailure(new IOException(new UnknownHostException("nova.astrometry.net"))));
        assertEquals("Astrometry.net did not answer in time. Try again later.",
                PlateSolveService.astrometryNetFailure(new HttpTimeoutException("request timed out")));
    }

    @Test
    public void otherFailuresGiveTheirFirstMessageOrTheirType() {
        assertEquals("Astrometry.net could not solve the image: Login failed: bad API key",
                PlateSolveService.astrometryNetFailure(new IOException(null, new IllegalStateException("Login failed: bad API key"))));
        assertEquals("Astrometry.net could not solve the image: IOException",
                PlateSolveService.astrometryNetFailure(new IOException()));
    }
}
