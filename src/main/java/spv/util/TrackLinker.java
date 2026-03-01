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
     * @param maxAsteroidSpeed    Max pixels an object can jump between frames (e.g., 50.0).
     * @param predictionTolerance Max pixels a detection can deviate from predicted path (e.g., 3.0).
     * @param angleToleranceRad   Max angle difference (in radians) to link streaks (e.g., Math.toRadians(5)).
     * @param totalGoodFrames
     */
    public static List<Track> findMovingObjects(
            List<List<SourceExtractor.DetectedObject>> allFrames,
            double maxStarJitter,
            double maxAsteroidSpeed,
            double predictionTolerance,
            double angleToleranceRad, int totalGoodFrames) {

        int numFrames = allFrames.size();
        if (numFrames < 3) return new ArrayList<>(); // Need at least 3 frames for point tracks

        List<Track> confirmedTracks = new ArrayList<>();

        // =================================================================
        // PHASE 1: SEPARATE STREAKS AND POINT SOURCES
        // =================================================================
        List<SourceExtractor.DetectedObject> allStreaks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> pointSourcesOnly = new ArrayList<>();

        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> currentFramePoints = new ArrayList<>();
            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.isStreak) {
                    allStreaks.add(obj);
                } else {
                    currentFramePoints.add(obj);
                }
            }
            pointSourcesOnly.add(currentFramePoints);
        }

        // =================================================================
        // PHASE 2: LINK FAST-MOVING STREAKS (Angle & Trajectory Matching)
        // =================================================================
        boolean[] streakMatched = new boolean[allStreaks.size()];

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
                    }
                }
            }
            // Add to confirmed (even if it's only 1 frame long, a streak is proof of movement)
            confirmedTracks.add(continuousStreakTrack);
        }

        // =================================================================
        // PHASE 3: FILTER STATIONARY STARS FROM POINT SOURCES
        // =================================================================
        List<List<SourceExtractor.DetectedObject>> transients = new ArrayList<>();
        for (int i = 0; i < numFrames; i++) {
            transients.add(new ArrayList<>());
        }

        for (int i = 0; i < numFrames - 1; i++) {
            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);
            List<SourceExtractor.DetectedObject> nextFrame = pointSourcesOnly.get(i + 1);

            for (SourceExtractor.DetectedObject obj1 : currentFrame) {
                boolean isStationary = false;
                for (SourceExtractor.DetectedObject obj2 : nextFrame) {
                    if (distance(obj1.x, obj1.y, obj2.x, obj2.y) <= maxStarJitter) {
                        isStationary = true;
                        break;
                    }
                }
                if (!isStationary) {
                    transients.get(i).add(obj1);
                }
            }
        }
        // Retain all transients from the final frame for the prediction loop
        transients.set(numFrames - 1, pointSourcesOnly.get(numFrames - 1));

// =================================================================
        // NEW PHASE 4: GEOMETRIC COLLINEAR LINKING (Time-Agnostic)
        // =================================================================

        // We calculate the 50% threshold.
        // We use Math.max(3, ...) because even in a 4-frame session,
        // we still need at least 3 points to confirm a line.
        int minPointsRequired = Math.max(3, (int) Math.ceil(totalGoodFrames / 2.0));

        System.out.println("DEBUG: Applying persistence filter. Object must appear in at least "
                + minPointsRequired + " frames.");

        List<Track> pointTracks = new ArrayList<>();

        // 1. Pick a starting point from any frame (except the last two)
        for (int f1 = 0; f1 < numFrames - 2; f1++) {
            for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {

                // 2. Pick a second point from a subsequent frame to define our Line
                for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                    for (SourceExtractor.DetectedObject p2 : transients.get(f2)) {

                        double dist12 = distance(p1.x, p1.y, p2.x, p2.y);

                        // Sanity check: ensure it actually moved, but didn't jump across the whole image
                        // We multiply maxSpeed by (f2 - f1) just to give it a generous absolute bound
                        if (dist12 < maxStarJitter || dist12 > (maxAsteroidSpeed * (f2 - f1))) continue;

                        Track currentTrack = new Track();
                        currentTrack.addPoint(p1);
                        currentTrack.addPoint(p2);

                        SourceExtractor.DetectedObject lastPoint = p2;

                        // 3. Hunt for points in later frames that fall exactly on this line
                        for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                            SourceExtractor.DetectedObject bestMatch = null;
                            double bestError = Double.MAX_VALUE;

                            for (SourceExtractor.DetectedObject p3 : transients.get(f3)) {

                                // Calculate the perpendicular distance from p3 to the line (p1->p2)
                                double lineError = distanceToLine(p1, p2, p3);

                                if (lineError <= predictionTolerance) {

                                    // Ensure it is moving FORWARD along the line, not jumping backwards
                                    double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);
                                    double actualAngle = Math.atan2(p3.y - lastPoint.y, p3.x - lastPoint.x);

                                    // Calculate absolute difference between angles
                                    double angleDiff = Math.abs(expectedAngle - actualAngle);
                                    if (angleDiff > Math.PI) angleDiff = 2 * Math.PI - angleDiff;

                                    if (angleDiff <= angleToleranceRad) {
                                        // If multiple noise pixels are near the line, pick the closest one
                                        if (lineError < bestError) {
                                            bestError = lineError;
                                            bestMatch = p3;
                                        }
                                    }
                                }
                            }

                            if (bestMatch != null) {
                                currentTrack.addPoint(bestMatch);
                                lastPoint = bestMatch; // Update reference so we always check forward motion
                            }
                        }

                        // 4. Final confirmation check
                        if (currentTrack.points.size() >= minPointsRequired) {
                            if (!isTrackAlreadyFound(pointTracks, currentTrack)) {
                                pointTracks.add(currentTrack);
                            }
                        }
                    }
                }
            }
        }

        confirmedTracks.addAll(pointTracks);
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