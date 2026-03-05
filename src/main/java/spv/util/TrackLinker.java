package spv.util;

import java.util.ArrayList;
import java.util.List;

public class TrackLinker {

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

    // --- Provided by Method Arguments (Defaults here for reference) ---
    public static double maxStarJitter = 3.0;       // Stars can wobble by ~2-3 pixels due to seeing
    public static double predictionTolerance = 3.0; // Allow a 3-pixel radius for the predicted path
    public static double angleToleranceRad = Math.toRadians(5.0); // Streaks must point in the same direction

    // --- Phase 1: Streak Defect Purging ---
    /** Max movement (in pixels) allowed for a streak to be considered a stationary sensor defect (hot column). */
    public static double stationaryDefectThreshold = 5.0;

    // --- Phase 3: Star Cataloging ---
    /** How many frames an object must appear in the exact same spot to be classified as a stationary star. */
    public static int requiredDetectionsToBeStar = 2;
    /** Multiplier for star jitter to account for long-term atmospheric wobble over the entire session. */
    public static double starJitterExpansionFactor = 1.5;

    // --- Phase 4: Geometric & Kinematic Linking ---
    /** The denominator used to calculate minimum points required. (e.g., 20 frames / 3.0 = ~7 points required). */
    public static double trackMinFrameRatio = 3.0;
    /** Hard cap on the minimum points required so the algorithm doesn't demand impossible lengths for huge batches. */
    public static int absoluteMaxPointsRequired = 5;
    /** The cosmic speed limit! The absolute maximum distance (in pixels) an object can travel between frames. */
    public static double maxJumpPixels = 400.0;
    /** Morphological Filter: The maximum allowable ratio in pixel area between two linked objects. */
    public static double maxSizeRatio = 3.0;

    // --- Kinematic Rhythm Parameters ---
    /** The max allowed pixel deviation from the expected speed to still be considered a "steady rhythm". */
    public static double rhythmAllowedVariance = 5.0;
    /** The minimum percentage of jumps (e.g., 0.70 = 70%) that must strictly follow the expected speed. */
    public static double rhythmMinConsistencyRatio = 0.70;
    /** If the median jump is smaller than this, the object isn't actually moving (it's an artifact). */
    public static double rhythmStationaryThreshold = 0.5;


    // =================================================================
    // DATA MODELS
    // =================================================================

    // --- The Data Model for a Confirmed Target ---
    public static class Track {
        public List<SourceExtractor.DetectedObject> points = new ArrayList<>();
        public double velocityX, velocityY;
        public boolean isStreakTrack = false; // Tells the UI how to draw it

        public void addPoint(SourceExtractor.DetectedObject obj) {
            points.add(obj);
        }
    }

    // =================================================================
    // CORE TRACKING ENGINE
    // =================================================================

    /**
     * Checks if two objects are roughly the same size.
     */
    private static boolean isSizeConsistent(SourceExtractor.DetectedObject obj1, SourceExtractor.DetectedObject obj2, double maxRatio) {
        double size1 = Math.max(obj1.pixelArea, 1.0);
        double size2 = Math.max(obj2.pixelArea, 1.0);

        double ratio = Math.max(size1, size2) / Math.min(size1, size2);
        return ratio <= maxRatio;
    }

    /**
     * Master method to find all moving objects (both fast streaks and slow dots).
     */
    public static List<Track> findMovingObjects(
            List<List<SourceExtractor.DetectedObject>> allFrames,
            double maxStarJitterArg,
            double predictionToleranceArg,
            double angleToleranceRadArg) {

        int numFrames = allFrames.size();
        System.out.println("\nDEBUG: [START] findMovingObjects initialized with " + numFrames + " frames.");

        if (numFrames < 3) {
            System.out.println("DEBUG: [ABORT] Less than 3 frames provided. Cannot form point tracks.");
            return new ArrayList<>();
        }

        List<Track> confirmedTracks = new ArrayList<>();

        // =================================================================
        // PHASE 1: Separate Streaks and Purge Stationary Defects
        // =================================================================
        List<SourceExtractor.DetectedObject> rawStreaks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> pointSourcesOnly = new ArrayList<>();

        for (int i = 0; i < allFrames.size(); i++) {
            pointSourcesOnly.add(new ArrayList<>());
            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.isStreak) {
                    rawStreaks.add(obj);
                } else {
                    pointSourcesOnly.get(i).add(obj);
                }
            }
        }

        // The Hot Column Killer
        List<SourceExtractor.DetectedObject> validMovingStreaks = new ArrayList<>();

        System.out.println("DEBUG: Evaluating " + rawStreaks.size() + " total streaks for sensor defects...");

        for (SourceExtractor.DetectedObject candidate : rawStreaks) {
            boolean isStationaryDefect = false;

            for (SourceExtractor.DetectedObject other : rawStreaks) {
                if (candidate == other || candidate.sourceFrameIndex == other.sourceFrameIndex) {
                    continue;
                }

                // Parameterized threshold
                if (distance(candidate.x, candidate.y, other.x, other.y) <= stationaryDefectThreshold) {
                    isStationaryDefect = true;
                    break;
                }
            }

            if (!isStationaryDefect) {
                validMovingStreaks.add(candidate);
            }
        }

        System.out.println("DEBUG: Purged " + (rawStreaks.size() - validMovingStreaks.size()) + " stationary hot columns.");

        // =================================================================
        // PHASE 2: LINK FAST-MOVING STREAKS (Angle & Trajectory Matching)
        // =================================================================
        System.out.println("DEBUG: [PHASE 2] Linking fast-moving streaks... Total candidate streaks: " + validMovingStreaks.size());
        boolean[] streakMatched = new boolean[validMovingStreaks.size()];
        int streakTracksFound = 0;

        for (int i = 0; i < validMovingStreaks.size(); i++) {
            if (streakMatched[i]) continue;

            SourceExtractor.DetectedObject baseStreak = validMovingStreaks.get(i);
            Track continuousStreakTrack = new Track();
            continuousStreakTrack.isStreakTrack = true;
            continuousStreakTrack.addPoint(baseStreak);
            streakMatched[i] = true;

            for (int j = i + 1; j < validMovingStreaks.size(); j++) {
                if (streakMatched[j]) continue;

                SourceExtractor.DetectedObject candidateStreak = validMovingStreaks.get(j);

                if (anglesMatch(baseStreak.angle, candidateStreak.angle, angleToleranceRadArg)) {

                    double dy = candidateStreak.y - baseStreak.y;
                    double dx = candidateStreak.x - baseStreak.x;
                    double trajectoryAngle = Math.atan2(dy, dx);

                    if (anglesMatch(baseStreak.angle, trajectoryAngle, angleToleranceRadArg)) {
                        continuousStreakTrack.addPoint(candidateStreak);
                        streakMatched[j] = true;
                    }
                }
            }
            confirmedTracks.add(continuousStreakTrack);
            streakTracksFound++;
        }
        System.out.println("DEBUG: [PHASE 2] Completed. Found " + streakTracksFound + " streak track(s).");

        // =================================================================
        // PHASE 3: MASTER STAR MAP (Catalog Stacking)
        // =================================================================
        List<List<SourceExtractor.DetectedObject>> transients = new ArrayList<>();
        for (int i = 0; i < numFrames; i++) {
            transients.add(new ArrayList<>());
        }

        System.out.println("DEBUG: [PHASE 3] Building Master Star Map (Catalog Stacking)...");

        // Parameterized expansion
        double expandedStarJitter = maxStarJitterArg * starJitterExpansionFactor;

        int totalTransientsFound = 0;

        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);

            for (SourceExtractor.DetectedObject candidateObj : currentFrame) {
                int spatialMatchCount = 1;

                for (int j = 0; j < numFrames; j++) {
                    if (i == j) continue;

                    List<SourceExtractor.DetectedObject> otherFrame = pointSourcesOnly.get(j);

                    for (SourceExtractor.DetectedObject otherObj : otherFrame) {
                        if (distance(candidateObj.x, candidateObj.y, otherObj.x, otherObj.y) <= expandedStarJitter) {
                            spatialMatchCount++;
                            break;
                        }
                    }

                    // Parameterized threshold
                    if (spatialMatchCount >= requiredDetectionsToBeStar) {
                        break;
                    }
                }

                if (spatialMatchCount < requiredDetectionsToBeStar) {
                    transients.get(i).add(candidateObj);
                }
            }
            totalTransientsFound += transients.get(i).size();
            System.out.println("  -> Frame " + i + ": Retained " + transients.get(i).size() + " isolated transients out of "+currentFrame.size()+" point sources.");
        }

        System.out.println("DEBUG: [PHASE 3] Completed. Total pure transients across sequence: " + totalTransientsFound);

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING (Time-Agnostic)
        // =================================================================

        // Parameterized math rules
        int minPointsRequired = Math.max(3, (int) Math.ceil(numFrames / trackMinFrameRatio));
        if (minPointsRequired > absoluteMaxPointsRequired) {
            minPointsRequired = absoluteMaxPointsRequired;
        }

        System.out.println("DEBUG: [PHASE 4] Applying time-agnostic geometric filter...");
        System.out.println("  -> Track confirmation threshold: " + minPointsRequired + " points.");

        List<Track> pointTracks = new ArrayList<>();

        java.util.Set<SourceExtractor.DetectedObject> usedPoints = new java.util.HashSet<>();

        for (int f1 = 0; f1 < numFrames - 2; f1++) {
            for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {

                if (usedPoints.contains(p1)) continue;

                for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                    for (SourceExtractor.DetectedObject p2 : transients.get(f2)) {

                        if (usedPoints.contains(p2)) continue;

                        double dist12 = distance(p1.x, p1.y, p2.x, p2.y);

                        if (dist12 < maxStarJitterArg) continue;

                        // Parameterized jump filter
                        if (dist12 > maxJumpPixels) continue;

                        // Parameterized size ratio
                        if (!isSizeConsistent(p1, p2, maxSizeRatio)) continue;

                        Track currentTrack = new Track();
                        currentTrack.addPoint(p1);
                        currentTrack.addPoint(p2);

                        SourceExtractor.DetectedObject lastPoint = p2;
                        double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

                        for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                            SourceExtractor.DetectedObject bestMatch = null;
                            double bestError = Double.MAX_VALUE;

                            for (SourceExtractor.DetectedObject p3 : transients.get(f3)) {

                                if (usedPoints.contains(p3)) continue;

                                double jumpDist = distance(lastPoint.x, lastPoint.y, p3.x, p3.y);
                                if (jumpDist > maxJumpPixels) continue;

                                double lineError = distanceToLine(p1, p2, p3);

                                if (lineError <= predictionToleranceArg) {
                                    double actualAngle = Math.atan2(p3.y - lastPoint.y, p3.x - lastPoint.x);

                                    double angleDiff = Math.abs(expectedAngle - actualAngle);
                                    if (angleDiff > Math.PI) {
                                        angleDiff = (2.0 * Math.PI) - angleDiff;
                                    }

                                    if (angleDiff <= angleToleranceRadArg) {
                                        if (isSizeConsistent(lastPoint, p3, maxSizeRatio)) {
                                            if (lineError < bestError) {
                                                bestError = lineError;
                                                bestMatch = p3;
                                            }
                                        }
                                    }
                                }
                            }

                            if (bestMatch != null) {
                                currentTrack.addPoint(bestMatch);
                                lastPoint = bestMatch;
                            }
                        }

                        if (currentTrack.points.size() >= minPointsRequired) {

                            // Parameterized variance
                            if (hasSteadyRhythm(currentTrack, rhythmAllowedVariance)) {

                                if (!isTrackAlreadyFound(pointTracks, currentTrack)) {

                                    pointTracks.add(currentTrack);
                                    usedPoints.addAll(currentTrack.points);
                                }
                            }
                        }
                    }
                }
            }
        }

        confirmedTracks.addAll(pointTracks);
        printAllTracks(confirmedTracks);

        return confirmedTracks;
    }

    // =================================================================
    // HELPER METHODS
    // =================================================================

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean anglesMatch(double a1, double a2, double tolerance) {
        double diff = Math.abs(a1 - a2) % Math.PI;
        return diff <= tolerance || Math.PI - diff <= tolerance;
    }

    private static double distanceToLine(SourceExtractor.DetectedObject p1,
                                         SourceExtractor.DetectedObject p2,
                                         SourceExtractor.DetectedObject p3) {

        double numerator = Math.abs((p2.x - p1.x) * (p1.y - p3.y) - (p1.x - p3.x) * (p2.y - p1.y));
        double denominator = distance(p1.x, p1.y, p2.x, p2.y);

        if (denominator == 0) return Double.MAX_VALUE;
        return numerator / denominator;
    }

    private static boolean isTrackAlreadyFound(List<Track> existingTracks, Track newTrack) {
        for (Track existing : existingTracks) {
            if (existing.points.containsAll(newTrack.points)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates if a track maintains a consistent speed, ignoring missing frames.
     */
    public static boolean hasSteadyRhythm(Track track, double allowedVariance) {
        if (track.points.size() < 3) return true;

        List<Double> jumps = new ArrayList<>();
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            jumps.add(distance(p1.x, p1.y, p2.x, p2.y));
        }

        List<Double> sortedJumps = new ArrayList<>(jumps);
        sortedJumps.sort(Double::compareTo);
        double medianJump = sortedJumps.get(sortedJumps.size() / 2);

        // Parameterized threshold
        if (medianJump < rhythmStationaryThreshold) return false;

        int consistentJumps = 0;

        for (double jump : jumps) {
            long multiplier = Math.round(jump / medianJump);

            if (multiplier == 0) continue;

            double expectedJump = multiplier * medianJump;

            if (Math.abs(jump - expectedJump) <= (allowedVariance * multiplier)) {
                consistentJumps++;
            }
        }

        // Parameterized consistency math
        double consistencyRatio = (double) consistentJumps / jumps.size();
        return consistencyRatio >= rhythmMinConsistencyRatio;
    }

    /**
     * Prints detailed debug information for both Streak Tracks and Point Tracks.
     */
    public static void printAllTracks(List<TrackLinker.Track> confirmedTargets) {
        System.out.println("\n========================================");
        System.out.println("=== DEBUG: CONFIRMED MOVING TARGETS  ===");
        System.out.println("========================================");

        int streakCount = 0;
        int pointTrackCount = 0;

        for (TrackLinker.Track track : confirmedTargets) {

            if (track.isStreakTrack) {
                streakCount++;

                // Passed the parameterized variance here too!
                if (track.points.size() > 2 && hasSteadyRhythm(track, rhythmAllowedVariance)) {
                    System.out.println("\n[STREAK TRACK #" + streakCount + " (STEADY RYTHM!)] (Composed of " + track.points.size() + " sub-streaks):");
                } else {
                    System.out.println("\n[STREAK TRACK #" + streakCount + "] (Composed of " + track.points.size() + " sub-streaks):");
                }
                for (int p = 0; p < track.points.size(); p++) {
                    SourceExtractor.DetectedObject pt = track.points.get(p);
                    System.out.println(String.format(
                            "  -> Part %d: [X: %7.2f, Y: %7.2f] | Internal Angle: %5.2f rad | Elongation: %5.2f",
                            (p + 1), pt.x, pt.y, pt.angle, pt.elongation
                    ));
                }

            } else {
                pointTrackCount++;

                if (hasSteadyRhythm(track, rhythmAllowedVariance)) {
                    System.out.println("\n[POINT TRACK #" + pointTrackCount + " (STEADY RYTHM!)] (Linked across " + track.points.size() + " frames):");
                } else {
                    System.out.println("\n[POINT TRACK #" + pointTrackCount + "] (Linked across " + track.points.size() + " frames):");
                }
                SourceExtractor.DetectedObject prevPt = null;

                for (int p = 0; p < track.points.size(); p++) {
                    SourceExtractor.DetectedObject pt = track.points.get(p);

                    if (prevPt == null) {
                        System.out.println(String.format(
                                "  -> Point %d: [X: %7.2f, Y: %7.2f] (Origin)",
                                (p + 1), pt.x, pt.y
                        ));
                    } else {
                        double jumpDist = Math.hypot(pt.x - prevPt.x, pt.y - prevPt.y);
                        double trajectoryAngle = Math.atan2(pt.y - prevPt.y, pt.x - prevPt.x);

                        System.out.println(String.format(
                                "  -> Point %d: [X: %7.2f, Y: %7.2f] | Jump: %6.2f px | Vector Angle: %5.2f rad",
                                (p + 1), pt.x, pt.y, jumpDist, trajectoryAngle
                        ));
                    }
                    prevPt = pt;
                }
            }
        }

        System.out.println("\n----------------------------------------");
        System.out.println("SUMMARY:");
        System.out.println("Total Streak Tracks: " + streakCount);
        System.out.println("Total Point Tracks:  " + pointTrackCount);
        System.out.println("========================================\n");
    }
}