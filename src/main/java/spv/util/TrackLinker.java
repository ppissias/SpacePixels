package spv.util;

import java.util.ArrayList;
import java.util.List;

public class TrackLinker {

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
     * Master method to find all moving objects (both fast streaks and slow dots).
     * * @param allFrames           List of detections for each sequential FITS image.
     *
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
        // PHASE 1: SEPARATE STREAKS AND POINT SOURCES
        // =================================================================
        System.out.println("DEBUG: [PHASE 1] Separating streaks from point sources...");
        List<SourceExtractor.DetectedObject> allStreaks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> pointSourcesOnly = new ArrayList<>();

        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> currentFramePoints = new ArrayList<>();
            int streakCount = 0;

            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.isStreak) {
                    allStreaks.add(obj);
                    streakCount++;
                } else {
                    currentFramePoints.add(obj);
                }
            }
            pointSourcesOnly.add(currentFramePoints);
            System.out.println("  -> Frame " + i + ": " + streakCount + " streaks, " + currentFramePoints.size() + " point sources.");
        }

        // =================================================================
        // PHASE 2: LINK FAST-MOVING STREAKS (Angle & Trajectory Matching)
        // =================================================================
        System.out.println("DEBUG: [PHASE 2] Linking fast-moving streaks... Total candidate streaks: " + allStreaks.size());
        boolean[] streakMatched = new boolean[allStreaks.size()];
        int streakTracksFound = 0;

        for (int i = 0; i < allStreaks.size(); i++) {
            if (streakMatched[i]) continue;

            SourceExtractor.DetectedObject baseStreak = allStreaks.get(i);
            Track continuousStreakTrack = new Track();
            continuousStreakTrack.isStreakTrack = true;
            continuousStreakTrack.addPoint(baseStreak);
            streakMatched[i] = true;

            // Look for matching streaks in subsequent frames
            for (int j = i + 1; j < allStreaks.size(); j++) {
                if (streakMatched[j]) continue;

                SourceExtractor.DetectedObject candidateStreak = allStreaks.get(j);

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
        // PHASE 3: FILTER STATIONARY STARS FROM POINT SOURCES (OPTIMIZED)
        // =================================================================
        List<List<SourceExtractor.DetectedObject>> transients = new ArrayList<>();
        for (int i = 0; i < numFrames; i++) {
            transients.add(new ArrayList<>());
        }

        double starPresenceThreshold = 0.80;
        int requiredFramesForStar = (int) Math.max(2, Math.floor(numFrames * starPresenceThreshold));
        System.out.println("DEBUG: [PHASE 3] Filtering stationary stars...");
        System.out.println("  -> Threshold: Object must appear in >= " + requiredFramesForStar + " frames to be ignored as a star.");

        int totalTransients = 0;
        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);

            for (SourceExtractor.DetectedObject obj1 : currentFrame) {
                int matchCount = 1; // It exists in the current frame (frame i)

                // Check all other frames to see if this object remains stationary
                for (int j = 0; j < numFrames; j++) {
                    if (i == j) continue;

                    List<SourceExtractor.DetectedObject> otherFrame = pointSourcesOnly.get(j);
                    for (SourceExtractor.DetectedObject obj2 : otherFrame) {
                        if (distance(obj1.x, obj1.y, obj2.x, obj2.y) <= maxStarJitter) {
                            matchCount++;
                            break;
                        }
                    }

                    if (matchCount >= requiredFramesForStar) {
                        break;
                    }
                }

                // If it didn't appear often enough to be a star, it must be a transient
                if (matchCount < requiredFramesForStar) {
                    transients.get(i).add(obj1);
                }
            }
            System.out.println("  -> Frame " + i + ": Kept " + transients.get(i).size() + " transients (Filtered out " +
                    (currentFrame.size() - transients.get(i).size()) + " stars).");
            totalTransients += transients.get(i).size();
        }
        System.out.println("DEBUG: [PHASE 3] Completed. Total transients remaining across all frames: " + totalTransients);

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING (Time-Agnostic)
        // =================================================================
        int minPointsRequired = Math.max(3, (int) Math.ceil(numFrames / 2.0));

        System.out.println("DEBUG: [PHASE 4] Applying time-agnostic geometric filter...");
        System.out.println("  -> Track confirmation threshold: " + minPointsRequired + " points.");

        List<Track> pointTracks = new ArrayList<>();

        for (int f1 = 0; f1 < numFrames - 2; f1++) {
            for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {

                for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                    for (SourceExtractor.DetectedObject p2 : transients.get(f2)) {

                        double dist12 = distance(p1.x, p1.y, p2.x, p2.y);

                        // If it barely moved, skip
                        if (dist12 < maxStarJitter) continue;

                        Track currentTrack = new Track();
                        currentTrack.addPoint(p1);
                        currentTrack.addPoint(p2);

                        SourceExtractor.DetectedObject lastPoint = p2;

                        // Establish the baseline trajectory angle
                        double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

                        // 3. Hunt for points on this infinite line in later frames
                        for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                            SourceExtractor.DetectedObject bestMatch = null;
                            double bestError = Double.MAX_VALUE;

                            for (SourceExtractor.DetectedObject p3 : transients.get(f3)) {

                                // Is it on the line?
                                double lineError = distanceToLine(p1, p2, p3);

                                if (lineError <= predictionTolerance) {

                                    // Is it moving strictly FORWARD from the last known point?
                                    double actualAngle = Math.atan2(p3.y - lastPoint.y, p3.x - lastPoint.x);

                                    if (anglesMatch(expectedAngle, actualAngle, angleToleranceRad)) {

                                        // As long as the angle matches, see if it's the closest point to our line
                                        if (lineError < bestError) {
                                            bestError = lineError;
                                            bestMatch = p3;
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
                            if (!isTrackAlreadyFound(pointTracks, currentTrack)) {
                                pointTracks.add(currentTrack);
                                //System.out.println("    -> [SUCCESS] Point Track Confirmed! Length: " + currentTrack.points.size() +
                                 //       " points. Started at Frame " + f1 + ", Angle: "                                                                                                                                                                                                 + String.format("%.2f", expectedAngle));
                            }
                        }
                    }
                }
            }
        }

        confirmedTracks.addAll(pointTracks);
        System.out.println("DEBUG: [PHASE 4] Completed. Found " + pointTracks.size() + " point tracks.");
        System.out.println("DEBUG: [FINISH] findMovingObjects complete. Total confirmed moving targets returned: " + confirmedTracks.size() + "\n");

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
}