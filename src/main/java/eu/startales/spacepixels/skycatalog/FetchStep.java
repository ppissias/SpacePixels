/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.skycatalog;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One question asked of an online service while fetching a sky catalogue or the known asteroids and comets.
 * The clients fill it, and the fetch summary reads it. Gson writes the public fields; a missing list in an older
 * file is null until the store replaces it. {@link #url} saved in the session folder never contains the observing
 * site: those parameters stay on {@link #liveUrl}, which lasts only until SpacePixels closes.
 */
public final class FetchStep {

    /** Whether the question was answered, failed, or never sent. */
    public enum Status {
        /** The service answered, including an answer that listed nothing in the field. */
        SUCCEEDED,
        /** The service was asked and did not answer, or answered only in part. */
        FAILED,
        /** The question was not sent. */
        NOT_ASKED
    }

    /** The service, for example "SIMBAD" or "JPL Horizons". */
    public String service;
    /** What was asked, for example "Variable stars" or one object's name. */
    public String question;
    public Status status;
    /** A count, or what was kept, such as a straight-line path. */
    public String outcome;
    /** Why it failed, or why it was not asked. */
    public String detail;
    /**
     * Address saved with the fetch. For a TAP query this is the endpoint, not a page that runs the query.
     * Observing-site parameters are left out.
     */
    public String url;
    /** The request address including the observing site. Not saved; null after the file is loaded again. */
    public transient String liveUrl;
    /** ADQL of a TAP query. Null for a GET that is already a complete address. */
    public String query;

    /**
     * A finished or skipped step. The caller owns the returned object. A JPL address that contains the observing
     * site keeps that full address on {@link #liveUrl} and stores {@link #url} without it.
     */
    public static FetchStep of(String service, String question, Status status, String outcome, String detail,
            String url, String query) {
        FetchStep step = new FetchStep();
        step.service = service;
        step.question = question;
        step.status = status;
        step.outcome = outcome;
        step.liveUrl = url;
        step.url = withoutSite(url);
        step.query = query;
        step.detail = detail;
        if (url != null && step.url != null && !url.equals(step.url)) {
            String note = "The saved address leaves out the observing site.";
            step.detail = detail == null || detail.isBlank() ? note : detail + " " + note;
        }
        return step;
    }

    /** The word shown in the summary. */
    public String resultWord() {
        if (status == Status.FAILED) {
            return "Failed";
        }
        if (status == Status.NOT_ASKED) {
            return "Not asked";
        }
        return "Succeeded";
    }

    /** The address to open now: the request that was sent while this visit still has it, otherwise the saved one. */
    public String openUrl() {
        return liveUrl != null && !liveUrl.isBlank() ? liveUrl : url;
    }

    /**
     * Rows to show for a saved result. Steps written with the fetch are kept. An older file has only problem
     * sentences: each sentence is a failed row and has no link.
     */
    public static List<FetchStep> rowsFor(List<FetchStep> steps, List<String> problems) {
        if (steps != null && !steps.isEmpty()) {
            return steps;
        }
        List<FetchStep> rows = new ArrayList<>();
        if (problems == null) {
            return rows;
        }
        for (String problem : problems) {
            if (problem != null && !problem.isBlank()) {
                rows.add(of(problem, "", Status.FAILED, "", "", null, null));
            }
        }
        return rows;
    }

    /** The same address with the observing-site parameters removed. Other parameters stay. */
    static String withoutSite(String url) {
        if (url == null || !url.contains("?")) {
            return url;
        }
        int split = url.indexOf('?');
        StringBuilder kept = new StringBuilder(url.substring(0, split));
        boolean any = false;
        boolean removed = false;
        for (String part : url.substring(split + 1).split("&")) {
            String name = part;
            int equals = part.indexOf('=');
            if (equals >= 0) {
                name = part.substring(0, equals);
            }
            if (isSiteParameter(name)) {
                removed = true;
                continue;
            }
            kept.append(any ? '&' : '?').append(part);
            any = true;
        }
        return removed ? kept.toString() : url;
    }

    /** Whether this query parameter is the observer's latitude, longitude, altitude or Horizons site coordinate. */
    private static boolean isSiteParameter(String name) {
        String decoded;
        try {
            decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            decoded = name;
        }
        decoded = decoded.toLowerCase(Locale.US);
        return decoded.equals("lat") || decoded.equals("lon") || decoded.equals("alt") || decoded.equals("site_coord");
    }
}
