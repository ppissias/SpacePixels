/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Tiny localhost helper that allows static HTML reports to ask the running desktop application to
 * fetch and normalize trusted external JSON lookups. The report itself remains a plain file on
 * disk; only the live enrichment path goes through this local service.
 */
public final class ReportLookupProxyServer {

    public static final int PORT = 47831;
    public static final String BASE_URL = "http://127.0.0.1:" + PORT;
    public static final String PERSISTED_LOOKUP_CACHE_SCRIPT_ID = "spacepixels-persisted-live-results";
    public static final String PERSISTED_LOOKUP_CACHE_START_MARKER = "<!-- SPACEPIXELS_PERSISTED_LOOKUP_CACHE_START -->";
    public static final String PERSISTED_LOOKUP_CACHE_END_MARKER = "<!-- SPACEPIXELS_PERSISTED_LOOKUP_CACHE_END -->";

    private static final String FETCH_PATH = "/api/report/fetch";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ReportLookupProxyServer INSTANCE = new ReportLookupProxyServer();

    private final Logger logger = Logger.getLogger(ReportLookupProxyServer.class.getName());

    private volatile HttpServer server;
    private volatile ExecutorService executor;

    private ReportLookupProxyServer() {
    }

    public static ReportLookupProxyServer getInstance() {
        return INSTANCE;
    }

    public static String getServiceBaseUrl() {
        return BASE_URL;
    }

    public static int getPort() {
        return PORT;
    }

    public synchronized boolean start() {
        if (server != null) {
            return true;
        }

        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT),
                    0);
            ExecutorService workerPool = Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "SpacePixels-ReportLookupProxy");
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(workerPool);
            httpServer.createContext(FETCH_PATH, new FetchHandler());
            httpServer.start();

            server = httpServer;
            executor = workerPool;
            logger.info("Report lookup proxy listening on " + BASE_URL + FETCH_PATH);
            return true;
        } catch (BindException e) {
            logger.warning("Report lookup proxy could not bind to " + BASE_URL + ": " + e.getMessage());
            return false;
        } catch (IOException e) {
            logger.warning("Failed to start report lookup proxy: " + e.getMessage());
            return false;
        }
    }

    public synchronized void stop() {
        HttpServer runningServer = server;
        server = null;
        if (runningServer != null) {
            runningServer.stop(0);
        }

        ExecutorService runningExecutor = executor;
        executor = null;
        if (runningExecutor != null) {
            runningExecutor.shutdownNow();
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    private final class FetchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange.getResponseHeaders());

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, createError("Only GET is supported by the report lookup proxy."));
                return;
            }

            Map<String, String> queryParameters = parseQuery(exchange.getRequestURI().getRawQuery());
            String provider = trimToNull(queryParameters.get("provider"));
            String encodedTarget = trimToNull(queryParameters.get("target"));
            String reportUrl = trimToNull(queryParameters.get("report"));
            String sidecarFileName = trimToNull(queryParameters.get("sidecar"));

            if (provider == null || encodedTarget == null) {
                writeJson(exchange, 400, createError("Missing report lookup parameters."));
                return;
            }

            if (!"satchecker".equals(provider) && !"jpl".equals(provider)) {
                writeJson(exchange, 400, createError("Unsupported report lookup provider."));
                return;
            }

            if (encodedTarget.length() > 16_384) {
                writeJson(exchange, 400, createError("Encoded lookup target is too large."));
                return;
            }

            final String targetUrl;
            try {
                byte[] decodedBytes = Base64.getUrlDecoder().decode(encodedTarget);
                targetUrl = new String(decodedBytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, createError("Lookup target could not be decoded."));
                return;
            }

            final URI validatedUri;
            try {
                validatedUri = ReportLookupUpstreamClient.validateTarget(provider, targetUrl);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, createError(e.getMessage()));
                return;
            }

            final Path reportPath;
            final Path sidecarPath;
            try {
                reportPath = ReportLookupCacheStore.resolveReportPath(reportUrl);
                sidecarPath = ReportLookupCacheStore.resolveSidecarPath(reportUrl, sidecarFileName);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, createError(e.getMessage()));
                return;
            }

            JsonObject cachedSidecarResponse = ReportLookupCacheStore.tryLoadMatchingSidecarResponse(
                    sidecarPath,
                    provider,
                    validatedUri.toString());
            if (cachedSidecarResponse != null) {
                ReportLookupCacheStore.persistResponseInReportCache(reportPath, sidecarFileName, cachedSidecarResponse);
                writeJson(exchange, 200, cachedSidecarResponse);
                return;
            }

            JsonObject response = ReportLookupUpstreamClient.proxyLookup(provider, validatedUri.toString());
            ReportLookupCacheStore.writeSidecarResponse(sidecarPath, response);
            ReportLookupCacheStore.persistResponseInReportCache(reportPath, sidecarFileName, response);
            writeJson(exchange, 200, response);
        }
    }

    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Cache-Control", "no-store");
    }

    private static JsonObject createError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("message", message);
        return error;
    }

    private static void writeJson(HttpExchange exchange, int statusCode, JsonObject payload) throws IOException {
        byte[] jsonBytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);
        try (OutputStream responseStream = exchange.getResponseBody()) {
            responseStream.write(jsonBytes);
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isEmpty()) {
                continue;
            }
            int separatorIndex = pair.indexOf('=');
            String rawKey = separatorIndex >= 0 ? pair.substring(0, separatorIndex) : pair;
            String rawValue = separatorIndex >= 0 ? pair.substring(separatorIndex + 1) : "";
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static String readLimitedUtf8(InputStream inputStream, int maxBytes) throws IOException {
        try (InputStream stream = inputStream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            int total = 0;
            while ((read = stream.read(chunk)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Upstream response exceeded the report lookup size limit.");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
