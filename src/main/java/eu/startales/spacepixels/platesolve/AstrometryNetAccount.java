/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.platesolve;

import eu.startales.spacepixels.config.AppConfig;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNetConfig;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.function.Function;

/**
 * The Astrometry.net account an online solve logs in with: anonymous (JPlateSolve's guest key) or the observer's own
 * API key, as chosen on the Astrometry Config tab; and the local solver that offers the same web API, such as
 * Watney Astrometry's solver API. Independent of an imported session.
 */
public final class AstrometryNetAccount {

    /** Astrometry.net's reason for refusing an unknown key, which JPlateSolve passes on. */
    private static final String UNKNOWN_KEY = "bad apikey";
    /** How JPlateSolve reports a refused login; the server's reason follows after ": " when it gave one. */
    private static final String REFUSED_LOGIN = "Astrometry.net login failed";

    private AstrometryNetAccount() {
    }

    /** A local solver: its address, ending in "/", and the key it is sent ("" sends JPlateSolve's guest key). */
    public record LocalSolver(URI address, String apiKey) {
    }

    /** The local solver configured on the Astrometry Config tab; null when its address is empty or not usable. */
    public static LocalSolver localSolver(AppConfig config) {
        URI address = localSolverAddress(config.localSolverAddress);
        return address == null ? null : new LocalSolver(address, config.localSolverApiKey == null ? "" : config.localSolverApiKey.trim());
    }

    /**
     * The address as typed, made usable: {@code http://} when no scheme is given, a final "/", and a final
     * {@code api} removed (other programs take the address of ANSVR as {@code http://localhost:8080/api}, while
     * JPlateSolve adds {@code api/} itself). Null when empty or not an HTTP(S) address of a host.
     */
    public static URI localSolverAddress(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) return null;
        if (!text.contains("://")) text = "http://" + text;
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
                return null;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            path = path.replaceAll("/+$", "");
            if (path.endsWith("/api")) path = path.substring(0, path.length() - 4);
            return new URI(scheme, null, uri.getHost(), uri.getPort(), path + "/", null, null);
        } catch (URISyntaxException unusable) {
            return null;
        }
    }

    /** Why the typed address cannot be used; null when it can or is empty. */
    public static String localSolverAddressProblem(String typed) {
        if (typed == null || typed.isBlank() || localSolverAddress(typed) != null) return null;
        return "Enter the solver's web address, such as http://localhost:5000/";
    }

    /** A client for the local solver. */
    static AstrometryDotNet newLocalClient(LocalSolver solver) {
        return new AstrometryDotNet(AstrometryDotNetConfig.builder().withServiceBaseUri(solver.address())
                .withApiKey(solver.apiKey()).build());
    }

    /** Logs in to the configured local solver without sending a frame. Blocks, so not on the Swing thread. */
    public static void checkLocalLogin(AppConfig config) throws IOException, InterruptedException {
        checkLocalLogin(config, AstrometryNetAccount::newLocalClient);
    }

    static void checkLocalLogin(AppConfig config, Function<LocalSolver, AstrometryDotNet> clients) throws IOException, InterruptedException {
        LocalSolver solver = localSolver(config);
        if (solver == null) throw new IOException("Enter the local solver's address first.");
        try (AstrometryDotNet client = clients.apply(solver)) {
            client.login();
        } catch (IOException failure) {
            throw new IOException(localFailureMessage(failure, solver, "The local solver refused the login: "), failure);
        }
    }

    /** What to tell the user when a local solve fails: an unreachable solver, a refused key, or the solver's reason. */
    public static String localFailureMessage(Throwable failure, LocalSolver solver) {
        return localFailureMessage(failure, solver, "The local solver could not solve the image: ");
    }

    private static String localFailureMessage(Throwable failure, LocalSolver solver, String otherFailurePrefix) {
        String address = solver == null ? "its address" : solver.address().toString();
        String message = null;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException || cause instanceof UnresolvedAddressException) {
                return "Could not reach the local solver at " + address + ". Check that it is running and that the "
                        + "address on the Astrometry Config tab is right.";
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return "The local solver at " + address + " did not answer in time.";
            }
            String text = cause.getMessage();
            if (text != null && (text.contains(UNKNOWN_KEY) || text.equals(REFUSED_LOGIN + "."))) {
                return "The local solver rejected the API key. Enter the key from its own settings on the Astrometry "
                        + "Config tab (Watney keeps its keys in apikeys.yml).";
            }
            if (text != null && text.startsWith(REFUSED_LOGIN + ": ")) {
                return "The local solver refused the login: " + text.substring(REFUSED_LOGIN.length() + 2);
            }
            if (message == null && text != null && !text.isBlank()) message = text;
        }
        return otherFailurePrefix + (message != null ? message : failure.getClass().getSimpleName());
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
            String text = cause.getMessage();
            // An unknown key, or a refusal without a reason (as JPlateSolve 1.0.1 first reported it): the account.
            if (text != null && (text.contains(UNKNOWN_KEY) || text.equals(REFUSED_LOGIN + "."))) {
                return ownKey
                        ? "Astrometry.net rejected the API key. Check the key on the Astrometry Config tab, or choose Anonymous."
                        : "Astrometry.net did not accept the anonymous login. Enter your own API key on the Astrometry Config tab.";
            }
            if (text != null && text.startsWith(REFUSED_LOGIN + ": ")) {
                return "Astrometry.net refused the login: " + text.substring(REFUSED_LOGIN.length() + 2);
            }
            if (message == null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message = cause.getMessage();
            }
        }
        return otherFailurePrefix + (message != null ? message : failure.getClass().getSimpleName());
    }
}
