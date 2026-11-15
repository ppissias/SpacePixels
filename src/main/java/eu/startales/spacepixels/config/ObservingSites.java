/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Named observing sites kept in {@link AppConfig#observingSites}. The selected one, {@link AppConfig#observingSite},
 * is the site in use: its values are the configuration's {@code observatoryCode}, {@code siteLat}, {@code siteLong} and
 * {@code siteAltitude}, which everything else reads. The methods keep the two in step.
 */
public final class ObservingSites {
    /** The name given to the site of a configuration written before sites had names. */
    public static final String FIRST_SITE_NAME = "My site";
    /** The name a new site starts with, numbered when taken. */
    public static final String NEW_SITE_NAME = "New site";

    /** One named site: the IAU code, latitude, longitude and altitude as typed in Astrometry Config. */
    public static final class Site {
        public String name = "";
        public String observatoryCode = "";
        public String siteLat = "";
        public String siteLong = "";
        public String siteAltitude = "";

        public Site copy() {
            Site copy = new Site();
            copy.name = name;
            copy.observatoryCode = observatoryCode;
            copy.siteLat = siteLat;
            copy.siteLong = siteLong;
            copy.siteAltitude = siteAltitude;
            return copy;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Site site && text(name).equals(text(site.name))
                    && text(observatoryCode).equals(text(site.observatoryCode)) && text(siteLat).equals(text(site.siteLat))
                    && text(siteLong).equals(text(site.siteLong)) && text(siteAltitude).equals(text(site.siteAltitude));
        }

        @Override public int hashCode() {
            return Objects.hash(text(name), text(observatoryCode), text(siteLat), text(siteLong), text(siteAltitude));
        }
    }

    private ObservingSites() { }

    /**
     * Makes the list whole: a configuration without sites gets one named {@value #FIRST_SITE_NAME} holding its current
     * values; sites without a usable or unique name are named; the selection falls on the first site when it names
     * none. The values in use stay as they are and are written into the selected site, so a hand-edited file never
     * changes the site in use.
     */
    public static void ensure(AppConfig config) {
        if (config.observingSites == null) config.observingSites = new ArrayList<>();
        config.observingSites.removeIf(Objects::isNull);
        List<String> seen = new ArrayList<>();
        for (Site site : config.observingSites) {
            String name = text(site.name);
            if (name.isEmpty() || contains(seen, name)) name = unique(seen, name.isEmpty() ? NEW_SITE_NAME : name);
            site.name = name;
            seen.add(name);
        }
        if (config.observingSites.isEmpty()) {
            Site first = new Site();
            first.name = FIRST_SITE_NAME;
            config.observingSites.add(first);
        }
        Site selected = find(config, config.observingSite);
        if (selected == null) selected = config.observingSites.getFirst();
        config.observingSite = selected.name;
        selected.observatoryCode = text(config.observatoryCode);
        selected.siteLat = text(config.siteLat);
        selected.siteLong = text(config.siteLong);
        selected.siteAltitude = text(config.siteAltitude);
    }

    /** The selected site; {@link #ensure} first when the configuration may not have one. */
    public static Site selected(AppConfig config) {
        Site site = find(config, config.observingSite);
        if (site == null) {
            ensure(config);
            site = find(config, config.observingSite);
        }
        return site;
    }

    /** The site of that name, ignoring case; null when there is none. */
    public static Site find(AppConfig config, String name) {
        if (config.observingSites == null || name == null) return null;
        for (Site site : config.observingSites) {
            if (site != null && text(site.name).equalsIgnoreCase(name.trim())) return site;
        }
        return null;
    }

    /** Selects the site of that name and puts its values in use. Returns false when there is no such site. */
    public static boolean select(AppConfig config, String name) {
        Site site = find(config, name);
        if (site == null) return false;
        config.observingSite = site.name;
        config.observatoryCode = text(site.observatoryCode);
        config.siteLat = text(site.siteLat);
        config.siteLong = text(site.siteLong);
        config.siteAltitude = text(site.siteAltitude);
        return true;
    }

    /** Adds an empty site with a free name, selects it and puts it in use; returns it. */
    public static Site add(AppConfig config) {
        ensure(config);
        Site site = new Site();
        site.name = unique(config.observingSites.stream().map(s -> s.name).toList(), NEW_SITE_NAME);
        config.observingSites.add(site);
        select(config, site.name);
        return site;
    }

    /**
     * Removes the site of that name; the last site cannot be removed. When it was selected, the site that took its
     * place in the list (or the one before it) is selected and put in use. Returns false when nothing was removed.
     */
    public static boolean remove(AppConfig config, String name) {
        ensure(config);
        Site site = find(config, name);
        if (site == null || config.observingSites.size() < 2) return false;
        int index = config.observingSites.indexOf(site);
        boolean wasSelected = site == find(config, config.observingSite);
        config.observingSites.remove(index);
        if (wasSelected) select(config, config.observingSites.get(Math.min(index, config.observingSites.size() - 1)).name);
        return true;
    }

    /** Null when the selected site may take this name, otherwise the reason. */
    public static String nameProblem(AppConfig config, String name) {
        String trimmed = text(name);
        if (trimmed.isEmpty()) return "Enter a name.";
        Site other = find(config, trimmed);
        if (other != null && other != selected(config)) return "Another site has this name.";
        return null;
    }

    /** Renames the selected site; does nothing when {@link #nameProblem} objects. */
    public static void rename(AppConfig config, String name) {
        if (nameProblem(config, name) != null) return;
        Site site = selected(config);
        site.name = text(name);
        config.observingSite = site.name;
    }

    /** Copies of the sites, for comparing and restoring. */
    public static List<Site> copyOf(List<Site> sites) {
        List<Site> copy = new ArrayList<>();
        if (sites != null) for (Site site : sites) if (site != null) copy.add(site.copy());
        return copy;
    }

    private static String unique(List<String> taken, String base) {
        if (!contains(taken, base)) return base;
        for (int n = 2; ; n++) {
            String name = base + " " + n;
            if (!contains(taken, name)) return name;
        }
    }

    private static boolean contains(List<String> names, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return names.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).equals(lower));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
