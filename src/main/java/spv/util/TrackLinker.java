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

    /** Photometric Filter: The maximum allowable ratio in total flux (brightness) between two linked objects. */
    public static double maxFluxRatio = 3.0;

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

    // --- NEW: Telemetry and Result Wrappers ---
    public static class TelemetryReport {
        public long countBaselineJitter, countBaselineJump, countBaselineSize, countBaselineFlux;
        public long countP3NotLine, countP3WrongDirection, countP3Jump, countP3Size, countP3Flux;
        public long countTrackTooShort, countTrackErraticRhythm, countTrackDuplicate;
        public int streakTracksFound, pointTracksFound;

        // --- NEW: Phase 3 Star Map Stats ---
        public List<FrameStarMapStat> frameStarMapStats = new ArrayList<>();
        public int totalStationaryStarsPurged = 0;

        public static class FrameStarMapStat {
            public int frameIndex;
            public int initialPointSources;
            public int survivingTransients;
            public int purgedStars;
        }
    }

    public static class TrackingResult {
        public List<Track> tracks;
        public TelemetryReport telemetry;

        public TrackingResult(List<Track> tracks, TelemetryReport telemetry) {
            this.tracks = tracks;
            this.telemetry = telemetry;
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
    public static TrackingResult findMovingObjects(
            List<List<SourceExtractor.DetectedObject>> allFrames,
            double maxStarJitterArg,
            double predictionToleranceArg,
            double angleToleranceRadArg) {

        int numFrames = allFrames.size();
        System.out.println("\nDEBUG: [START] findMovingObjects initialized with " + numFrames + " frames.");

        if (numFrames < 3) {
            System.out.println("DEBUG: [ABORT] Less than 3 frames provided. Cannot form point tracks.");
            // Return an empty result safely
            return new TrackingResult(new ArrayList<>(), new TelemetryReport());
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

        // --- NEW: Initialize Telemetry early to catch Phase 3 data ---
        TelemetryReport telemetry = new TelemetryReport();

        double expandedStarJitter = maxStarJitterArg * starJitterExpansionFactor;
        int totalTransientsFound = 0;

        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);
            int purgedCount = 0;

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

                    if (spatialMatchCount >= requiredDetectionsToBeStar) {
                        break;
                    }
                }

                if (spatialMatchCount < requiredDetectionsToBeStar) {
                    transients.get(i).add(candidateObj);
                } else {
                    purgedCount++;
                }
            }

            totalTransientsFound += transients.get(i).size();
            telemetry.totalStationaryStarsPurged += purgedCount;

            // --- NEW: Record Frame Stats ---
            TelemetryReport.FrameStarMapStat stat = new TelemetryReport.FrameStarMapStat();
            stat.frameIndex = i;
            stat.initialPointSources = currentFrame.size();
            stat.survivingTransients = transients.get(i).size();
            stat.purgedStars = purgedCount;
            telemetry.frameStarMapStats.add(stat);

            System.out.println("  -> Frame " + i + ": Retained " + transients.get(i).size() + " isolated transients out of " + currentFrame.size() + " point sources.");
        }
        System.out.println("DEBUG: [PHASE 3] Completed. Total pure transients across sequence: " + totalTransientsFound);

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING (Time-Agnostic)
        // =================================================================
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

                        if (dist12 < maxStarJitterArg) {
                            telemetry.countBaselineJitter++;
                            continue;
                        }

                        if (dist12 > maxJumpPixels) {
                            telemetry.countBaselineJump++;
                            continue;
                        }

                        if (!isSizeConsistent(p1, p2, maxSizeRatio)) {
                            telemetry.countBaselineSize++;
                            continue;
                        }

                        if (!isBrightnessConsistent(p1, p2, maxFluxRatio)) {
                            telemetry.countBaselineFlux++;
                            continue;
                        }

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
                                if (jumpDist > maxJumpPixels) {
                                    telemetry.countP3Jump++;
                                    continue;
                                }

                                double lineError = distanceToLine(p1, p2, p3);

                                if (lineError <= predictionToleranceArg) {
                                    double actualAngle = Math.atan2(p3.y - lastPoint.y, p3.x - lastPoint.x);

                                    double angleDiff = Math.abs(expectedAngle - actualAngle);
                                    if (angleDiff > Math.PI) {
                                        angleDiff = (2.0 * Math.PI) - angleDiff;
                                    }

                                    if (angleDiff <= angleToleranceRadArg) {
                                        if (isSizeConsistent(lastPoint, p3, maxSizeRatio)) {

                                            if (isBrightnessConsistent(lastPoint, p3, maxFluxRatio)) {
                                                if (lineError < bestError) {
                                                    bestError = lineError;
                                                    bestMatch = p3;
                                                }
                                            } else {
                                                telemetry.countP3Flux++;
                                            }

                                        } else {
                                            telemetry.countP3Size++;
                                        }
                                    } else {
                                        telemetry.countP3WrongDirection++;
                                    }
                                } else {
                                    telemetry.countP3NotLine++;
                                }
                            }

                            if (bestMatch != null) {
                                currentTrack.addPoint(bestMatch);
                                lastPoint = bestMatch;
                            }
                        }

                        if (currentTrack.points.size() >= minPointsRequired) {
                            if (hasSteadyRhythm(currentTrack, rhythmAllowedVariance)) {
                                if (!isTrackAlreadyFound(pointTracks, currentTrack)) {
                                    pointTracks.add(currentTrack);
                                    usedPoints.addAll(currentTrack.points);
                                } else {
                                    telemetry.countTrackDuplicate++;
                                }
                            } else {
                                telemetry.countTrackErraticRhythm++;
                            }
                        } else {
                            telemetry.countTrackTooShort++;
                        }
                    }
                }
            }
        }

        // --- FINALIZE METRICS ---
        telemetry.streakTracksFound = streakTracksFound;
        telemetry.pointTracksFound = pointTracks.size();

        // --- PRINT TELEMETRY REPORT ---
        System.out.println("\n--------------------------------------------------");
        System.out.println(" PHASE 4 TELEMETRY: FILTER REJECTION STATISTICS   ");
        System.out.println("--------------------------------------------------");
        System.out.println("1. Baseline Generation (p1 -> p2) Rejections:");
        System.out.println("   - Stationary / Jitter           : " + telemetry.countBaselineJitter);
        System.out.println("   - Exceeded Max Jump Velocity    : " + telemetry.countBaselineJump);
        System.out.println("   - Morphological Size Mismatch   : " + telemetry.countBaselineSize);
        System.out.println("   - Photometric Flux Mismatch     : " + telemetry.countBaselineFlux);

        System.out.println("\n2. Track Search (p3) Point Rejections:");
        System.out.println("   - Off predicted trajectory line : " + telemetry.countP3NotLine);
        System.out.println("   - Wrong direction / angle       : " + telemetry.countP3WrongDirection);
        System.out.println("   - Exceeded Max Jump Velocity    : " + telemetry.countP3Jump);
        System.out.println("   - Morphological Size Mismatch   : " + telemetry.countP3Size);
        System.out.println("   - Photometric Flux Mismatch     : " + telemetry.countP3Flux);

        System.out.println("\n3. Final Track Rejections:");
        System.out.println("   - Insufficient track length     : " + telemetry.countTrackTooShort);
        System.out.println("   - Erratic kinematic rhythm      : " + telemetry.countTrackErraticRhythm);
        System.out.println("   - Duplicate track (Ignored)     : " + telemetry.countTrackDuplicate);
        System.out.println("--------------------------------------------------\n");

        System.out.println("\n4. Valid Tracks Confirmed:");
        System.out.println("   - Fast Streak Tracks (Phase 2)  : " + telemetry.streakTracksFound);
        System.out.println("   - Slow Point Tracks (Phase 4)   : " + telemetry.pointTracksFound);
        System.out.println("   - TOTAL MOVING TARGETS FOUND    : " + (telemetry.streakTracksFound + telemetry.pointTracksFound));
        System.out.println("--------------------------------------------------\n");

        confirmedTracks.addAll(pointTracks);
        //printAllTracks(confirmedTracks);

        // Return the combined object!
        return new TrackingResult(confirmedTracks, telemetry);
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
     * Checks if two objects have roughly the same brightness (flux).
     * Rejects noise artifacts that wildly flash or spike in brightness between frames.
     */
    private static boolean isBrightnessConsistent(SourceExtractor.DetectedObject obj1, SourceExtractor.DetectedObject obj2, double maxRatio) {
        // Use Math.abs to prevent negative flux edge-cases from breaking the math, and floor it at 1.0
        double flux1 = Math.max(Math.abs(obj1.totalFlux), 1.0);
        double flux2 = Math.max(Math.abs(obj2.totalFlux), 1.0);

        double ratio = Math.max(flux1, flux2) / Math.min(flux1, flux2);
        return ratio <= maxRatio;
    }
}