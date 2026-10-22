/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.function.Function;

/**
 * The Astrometry.net account an online solve logs in with: anonymous (JPlateSolve's guest key) or the observer's own
 * API key, as chosen on the Astrometry Config tab. Independent of an imported session.
 */
public final class AstrometryNetAccount {

    /** JPlateSolve 1.0.1 refuses a login with this message; 1.0.0 passed on the server's {@code "bad apikey"}. */
    private static final String[] REJECTED_LOGIN = {"Astrometry.net login failed", "bad apikey"};

    private AstrometryNetAccount() {
    }

    /** A client for this key. "" is an anonymous login, so a blank key is never sent as the observer's own. */
    static AstrometryDotNet newClient(String apiKey) {
        return apiKey.isEmpty() ? new AstrometryDotNet() : new AstrometryDotNet(apiKey);
    }

    /**
     * Logs in with the configured account, without uploading anything. Blocks for the network call, so it must not
     * run on the Swing thread.
     *
     * @throws IOException with a message for the user when the login fails
     */
    public static void checkLogin(AppConfig config) throws IOException, InterruptedException {
        checkLogin(config, AstrometryNetAccount::newClient);
    }

    static void checkLogin(AppConfig config, Function<String, AstrometryDotNet> clients) throws IOException, InterruptedException {
        String apiKey = config.astrometryNetApiKeyInUse();
        try (AstrometryDotNet client = clients.apply(apiKey)) {
            client.login();
        } catch (IOException failure) {
            throw new IOException(failureMessage(failure, !apiKey.isEmpty(), "Astrometry.net login failed: "), failure);
        }
    }

    /**
     * What to tell the user when an online solve fails. A lost connection often comes without any message, so the
     * network failures are named by their type; a refused login is named for the account in use; otherwise the first
     * message in the cause chain is used.
     */
    public static String failureMessage(Throwable failure, boolean ownKey) {
        return failureMessage(failure, ownKey, "Astrometry.net could not solve the image: ");
    }

    private static String failureMessage(Throwable failure, boolean ownKey, String otherFailurePrefix) {
        String message = null;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException || cause instanceof UnresolvedAddressException) {
                return "Could not reach Astrometry.net (nova.astrometry.net). Check the internet connection.";
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return "Astrometry.net did not answer in time. Try again later.";
            }
            if (isRejectedLogin(cause.getMessage())) {
                return ownKey
                        ? "Astrometry.net rejected the API key. Check the key on the Astrometry Config tab, or choose Anonymous."
                        : "Astrometry.net did not accept the anonymous login. Enter your own API key on the Astrometry Config tab.";
            }
            if (message == null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message = cause.getMessage();
            }
        }
        return otherFailurePrefix + (message != null ? message : failure.getClass().getSimpleName());
    }

    private static boolean isRejectedLogin(String message) {
        if (message == null) {
            return false;
        }
        for (String rejected : REJECTED_LOGIN) {
            if (message.contains(rejected)) {
                return true;
            }
        }
        return false;
    }
}
