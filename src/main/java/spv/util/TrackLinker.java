package spv.util;

import java.util.ArrayList;
import java.util.List;

public class TrackLinker {

    public static double maxStarJitter = 3.0;       // Stars can wobble by ~2 pixels due to seeing
    public static double predictionTolerance = 3.0; // Allow a 3-pixel radius for the predicted path
    public static double angleToleranceRad = Math.toRadians(5.0); // Streaks must point in the same direction

    // --- The Data Model for a Confirmed Target ---
    public static class Track {
        public List<SourceExtractor.DetectedObject> points = new ArrayList<>();
        public double velocityX, velocityY;
        public boolean isStreakTrack = false; // Tells the UI how to draw it

        public void addPoint(SourceExtractor.DetectedObject obj) {
            points.add(obj);
        }
    }
    /**
     * Checks if two objects are roughly the same size.
     * A maxRatio of 3.0 means one object can be up to 3 times larger than the other,
     * which safely accounts for atmospheric blurring and noise, but rejects massive mismatches.
     */
    private static boolean isSizeConsistent(SourceExtractor.DetectedObject obj1, SourceExtractor.DetectedObject obj2, double maxRatio) {
        // Prevent division by zero just in case
        double size1 = Math.max(obj1.pixelArea, 1.0);
        double size2 = Math.max(obj2.pixelArea, 1.0);

        double ratio = Math.max(size1, size2) / Math.min(size1, size2);
        return ratio <= maxRatio;
    }

    /**
     * Master method to find all moving objects (both fast streaks and slow dots).
     * @param allFrames           List of detections for each sequential FITS image.
     * @param maxStarJitter       Max pixel wobble for stationary stars (e.g., 2.0).
     * @param predictionTolerance Max pixels a detection can deviate from predicted path (e.g., 3.0).
     * @param angleToleranceRad   Max angle difference (in radians) to link streaks (e.g., Math.toRadians(5)).
     */
    public static List<Track> findMovingObjects(
            List<List<SourceExtractor.DetectedObject>> allFrames,
            double maxStarJitter,
            double predictionTolerance,
            double angleToleranceRad) {

        int numFrames = allFrames.size();
        System.out.println("\nDEBUG: [START] findMovingObjects initialized with " + numFrames + " frames.");

        if (numFrames < 3) {
            System.out.println("DEBUG: [ABORT] Less than 3 frames provided. Cannot form point tracks.");
            return new ArrayList<>(); // Need at least 3 frames for point tracks
        }

        List<Track> confirmedTracks = new ArrayList<>();

        // =================================================================
        // PHASE 1: Separate Streaks and Purge Stationary Defects
        // =================================================================
        List<SourceExtractor.DetectedObject> rawStreaks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> pointSourcesOnly = new ArrayList<>();

        // 1. Gather all objects
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

        // 2. The Hot Column Killer for falsely detected streaks.
        List<SourceExtractor.DetectedObject> validMovingStreaks = new ArrayList<>();
        double stationaryThreshold = 5.0; // If it moves less than 5 pixels, it's a defect

        System.out.println("DEBUG: Evaluating " + rawStreaks.size() + " total streaks for sensor defects...");

        for (SourceExtractor.DetectedObject candidate : rawStreaks) {
            boolean isStationaryDefect = false;

            // Check against every other streak we found in the entire session
            for (SourceExtractor.DetectedObject other : rawStreaks) {
                // Don't compare it to itself or to streaks in its own frame
                if (candidate == other || candidate.sourceFrameIndex == other.sourceFrameIndex) {
                    continue;
                }

                // If another streak exists in a different frame at the EXACT same location...
                if (distance(candidate.x, candidate.y, other.x, other.y) <= stationaryThreshold) {
                    isStationaryDefect = true;
                    break; // We proved it's a hot column. Stop checking.
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

            // Look for matching streaks in subsequent frames
            for (int j = i + 1; j < validMovingStreaks.size(); j++) {
                if (streakMatched[j]) continue;

                SourceExtractor.DetectedObject candidateStreak = validMovingStreaks.get(j);

                // 1. Do their internal angles match?
                if (anglesMatch(baseStreak.angle, candidateStreak.angle, angleToleranceRad)) {

                    // 2. Are they collinear? (Does the path between them match the angle?)
                    double dy = candidateStreak.y - baseStreak.y;
                    double dx = candidateStreak.x - baseStreak.x;
                    double trajectoryAngle = Math.atan2(dy, dx);

                    if (anglesMatch(baseStreak.angle, trajectoryAngle, angleToleranceRad)) {
                        continuousStreakTrack.addPoint(candidateStreak);
                        streakMatched[j] = true;
                        System.out.println("    -> Streak linked! Base angle: " + String.format("%.2f", baseStreak.angle) +
                                ", Trajectory angle: " + String.format("%.2f", trajectoryAngle));
                    }
                }
            }
            // Add to confirmed (even if it's only 1 frame long, a streak is proof of movement)
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

        // The core rule: If it appears in the same spot in at least 2 frames, it's a star.
        int requiredDetectionsToBeStar = 2;

        // We slightly increase jitter to account for atmospheric wobble across the whole session
        double expandedStarJitter = maxStarJitter * 1.5;

        int totalTransientsFound = 0;

        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);

            for (SourceExtractor.DetectedObject candidateObj : currentFrame) {
                int spatialMatchCount = 1; // It exists in its own frame

                // Check ALL other frames for a detection at this exact location
                for (int j = 0; j < numFrames; j++) {
                    if (i == j) continue;

                    List<SourceExtractor.DetectedObject> otherFrame = pointSourcesOnly.get(j);

                    for (SourceExtractor.DetectedObject otherObj : otherFrame) {
                        if (distance(candidateObj.x, candidateObj.y, otherObj.x, otherObj.y) <= expandedStarJitter) {
                            spatialMatchCount++;
                            break; // Found it in frame j, move to check the next frame
                        }
                    }

                    // Optimization: The moment it hits our threshold, we know it's a star. Stop searching.
                    if (spatialMatchCount >= requiredDetectionsToBeStar) {
                        break;
                    }
                }

                // If it never found a spatial partner in ANY other frame, it is a true transient.
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
        int minPointsRequired = Math.max(3, (int) Math.ceil(numFrames / 2.0));
        if (minPointsRequired > 5) {
            minPointsRequired = 5;
        }
        //TODO move to variable
        double maxSizeRatio = 3.0;

        System.out.println("DEBUG: [PHASE 4] Applying time-agnostic geometric filter...");
        System.out.println("  -> Track confirmation threshold: " + minPointsRequired + " points.");

        List<Track> pointTracks = new ArrayList<>();

        // --- NEW: THE GLOBAL MEMORY BANK ---
        // Keeps track of objects already assigned to a confirmed track
        // to completely eliminate duplicate sub-tracks!
        java.util.Set<SourceExtractor.DetectedObject> usedPoints = new java.util.HashSet<>();

        for (int f1 = 0; f1 < numFrames - 2; f1++) {
            for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {

                // Skip if this point is already claimed by a confirmed track
                if (usedPoints.contains(p1)) continue;

                for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                    for (SourceExtractor.DetectedObject p2 : transients.get(f2)) {

                        // Skip if this point is already claimed
                        if (usedPoints.contains(p2)) continue;

                        double dist12 = distance(p1.x, p1.y, p2.x, p2.y);
                        if (dist12 < maxStarJitter) continue;

                        if (!isSizeConsistent(p1, p2, maxSizeRatio)) continue;

                        Track currentTrack = new Track();
                        currentTrack.addPoint(p1);
                        currentTrack.addPoint(p2);

                        SourceExtractor.DetectedObject lastPoint = p2;
                        double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

                        // 3. Hunt for points on this infinite line in later frames
                        for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                            SourceExtractor.DetectedObject bestMatch = null;
                            double bestError = Double.MAX_VALUE;

                            for (SourceExtractor.DetectedObject p3 : transients.get(f3)) {

                                // Skip if p3 is already claimed by another track!
                                if (usedPoints.contains(p3)) continue;

                                double lineError = distanceToLine(p1, p2, p3);

                                if (lineError <= predictionTolerance) {
                                    double actualAngle = Math.atan2(p3.y - lastPoint.y, p3.x - lastPoint.x);

                                    double angleDiff = Math.abs(expectedAngle - actualAngle);
                                    if (angleDiff > Math.PI) {
                                        angleDiff = (2.0 * Math.PI) - angleDiff;
                                    }

                                    if (angleDiff <= angleToleranceRad) {
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

// 4. Final confirmation check
                        if (currentTrack.points.size() >= minPointsRequired) {

                            // --- NEW: STEADY RHYTHM KINEMATIC FILTER ---
                            // Only accept the track if the object moves at a consistent speed
                            //TODO move allowed variance to class variable
                            if (hasSteadyRhythm(currentTrack, 5.0)) {

                                if (!isTrackAlreadyFound(pointTracks, currentTrack)) {

                                    pointTracks.add(currentTrack);

                                    // LOCK THESE POINTS
                                    usedPoints.addAll(currentTrack.points);
                                }
                            } else {
                                // Optional: You can print a debug statement here to see how many
                                // geometrically perfect but kinematically invalid tracks get rejected!
                                // System.out.println("DEBUG: Rejected track due to erratic rhythm.");
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

    // --- Helper Methods ---

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean anglesMatch(double a1, double a2, double tolerance) {
        // Normalizes the angle comparison to handle PI / -PI wrap-arounds
        double diff = Math.abs(a1 - a2) % Math.PI;
        return diff <= tolerance || Math.PI - diff <= tolerance;
    }

    /**
     * Calculates the perpendicular distance from point p3 to the infinite line defined by p1 and p2.
     */
    private static double distanceToLine(SourceExtractor.DetectedObject p1,
                                         SourceExtractor.DetectedObject p2,
                                         SourceExtractor.DetectedObject p3) {

        // Formula: |(x2 - x1)(y1 - y0) - (x1 - x0)(y2 - y1)| / sqrt((x2 - x1)^2 + (y2 - y1)^2)
        // Where p3 is (x0, y0)
        double numerator = Math.abs((p2.x - p1.x) * (p1.y - p3.y) - (p1.x - p3.x) * (p2.y - p1.y));
        double denominator = distance(p1.x, p1.y, p2.x, p2.y);

        if (denominator == 0) return Double.MAX_VALUE; // Safety catch if p1 and p2 are identical
        return numerator / denominator;
    }

    /**
     * Prevents duplicate tracks if the algorithm finds a subset of an already confirmed line.
     */
    private static boolean isTrackAlreadyFound(List<Track> existingTracks, Track newTrack) {
        for (Track existing : existingTracks) {
            // If an existing track contains all the points of this new track, it's a duplicate subset
            if (existing.points.containsAll(newTrack.points)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates if a track maintains a consistent speed, ignoring missing frames.
     * @param track The track to evaluate.
     * @param allowedVariance Max allowed deviation from the expected jump distance (e.g., 2.0 or 3.0 px).
     * @return true if the majority of the track's jumps match the rhythm.
     */
    public static boolean hasSteadyRhythm(Track track, double allowedVariance) {
        if (track.points.size() < 3) return true; // Need at least 3 points to check rhythm

        List<Double> jumps = new ArrayList<>();
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            jumps.add(distance(p1.x, p1.y, p2.x, p2.y));
        }

        // 1. Find the median jump to establish the "base rhythm" (the typical 1-frame jump)
        List<Double> sortedJumps = new ArrayList<>(jumps);
        sortedJumps.sort(Double::compareTo);
        double medianJump = sortedJumps.get(sortedJumps.size() / 2);

        // Safety catch: If the median jump is basically zero, it's a stationary noise artifact
        if (medianJump < 0.5) return false;

        int consistentJumps = 0;

        // 2. Evaluate every jump against the base rhythm
        for (double jump : jumps) {

            // Figure out how many "missing frames" this jump represents.
            // If jump is 31 and median is 15, multiplier is 2 (1 missing frame).
            long multiplier = Math.round(jump / medianJump);

            if (multiplier == 0) continue; // Jump was anomalously small

            double expectedJump = multiplier * medianJump;

            // We scale the variance by the multiplier. A 3-frame gap has 3x the potential seeing jitter.
            if (Math.abs(jump - expectedJump) <= (allowedVariance * multiplier)) {
                consistentJumps++;
            }
        }

        // 3. Does this rhythm hold true for the majority of the track?
        // We require at least 70% of the jumps to fit the mathematical rhythm.
        double consistencyRatio = (double) consistentJumps / jumps.size();
        return consistencyRatio >= 0.70;
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
                // --- PRINT FAST STREAK TRACKS ---
                streakCount++;

                if (track.points.size() > 2 && hasSteadyRhythm(track, 5.0)) {
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
                // --- PRINT SLOW POINT TRACKS ---
                pointTrackCount++;

                if (hasSteadyRhythm(track, 5.0)) {
                    System.out.println("\n[POINT TRACK #" + pointTrackCount + " (STEADY RYTHM!)] (Linked across " + track.points.size() + " frames):");
                } else {
                    System.out.println("\n[POINT TRACK #" + pointTrackCount + "] (Linked across " + track.points.size() + " frames):");
                }
                SourceExtractor.DetectedObject prevPt = null;

                for (int p = 0; p < track.points.size(); p++) {
                    SourceExtractor.DetectedObject pt = track.points.get(p);

                    if (prevPt == null) {
                        // First point establishes the origin
                        System.out.println(String.format(
                                "  -> Point %d: [X: %7.2f, Y: %7.2f] (Origin)",
                                (p + 1), pt.x, pt.y
                        ));
                    } else {
                        // Subsequent points show the movement vector
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