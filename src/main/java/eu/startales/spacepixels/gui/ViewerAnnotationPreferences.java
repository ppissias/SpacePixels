package eu.startales.spacepixels.gui;

import java.util.prefs.Preferences;

/** Immutable viewer-only catalogue filters; magnitudes use each catalogue's band, and 99 means no limit. */
record ViewerAnnotationPreferences(double faintestStar, double faintestVariable, boolean names) {
    static final double NO_LIMIT = 99;
    static final ViewerAnnotationPreferences DEFAULT = new ViewerAnnotationPreferences(NO_LIMIT, NO_LIMIT, true);

    /** Rejects invalid limits rather than allowing a corrupt preference to hide the catalogue. */
    ViewerAnnotationPreferences {
        if (!valid(faintestStar) || !valid(faintestVariable)) {
            throw new IllegalArgumentException("Magnitude limits must be finite and between -10 and 99.");
        }
    }

    /** Returns the shared desktop preference node; callers handle unavailable preference storage. */
    static Preferences storage() {
        return Preferences.userNodeForPackage(ViewerAnnotationPreferences.class).node("catalogueAnnotations");
    }

    /** Reads optional preferences, retaining the previous display behavior for missing or invalid values. */
    static ViewerAnnotationPreferences load(Preferences storage) {
        double stars = storage.getDouble("faintestStar", NO_LIMIT);
        double variables = storage.getDouble("faintestVariable", NO_LIMIT);
        return new ViewerAnnotationPreferences(valid(stars) ? stars : NO_LIMIT,
                valid(variables) ? variables : NO_LIMIT, storage.getBoolean("names", true));
    }

    /** Saves display choices for subsequently opened viewers without modifying detection or export settings. */
    void save(Preferences storage) {
        storage.putDouble("faintestStar", faintestStar);
        storage.putDouble("faintestVariable", faintestVariable);
        storage.putBoolean("names", names);
    }

    /** Applies independent star/variable limits; deep-sky objects and unknown magnitudes remain available. */
    boolean includes(SkyMark mark) {
        double limit = switch (mark.kind) {
            case STAR, NAMED_STAR -> faintestStar;
            case VARIABLE -> faintestVariable;
            case DEEP_SKY -> NO_LIMIT;
        };
        return limit == NO_LIMIT || !Double.isFinite(mark.magnitude) || mark.magnitude <= limit;
    }

    /** Checks the supported astronomical-magnitude range, including the no-limit sentinel. */
    private static boolean valid(double magnitude) {
        return Double.isFinite(magnitude) && magnitude >= -10 && magnitude <= NO_LIMIT;
    }
}
