package spv.util;

import java.util.List;

public class DetectionDebugger {

    /**
     * Prints a formatted table of all detected objects to the console.
     * * @param objects The list of DetectedObjects from the SourceExtractor
     * @param frameName An optional label (e.g., "Frame 1") to identify the batch
     */
    public static void printDetectedObjects(List<SourceExtractor.DetectedObject> objects, String frameName) {
        if (objects == null || objects.isEmpty()) {
            System.out.println("No objects found in " + frameName + ".");
            return;
        }

        System.out.println("\n=================================================================================================");
        System.out.println(" DETECTED OBJECTS REPORT: " + frameName + " (Total: " + objects.size() + ")");
        System.out.println("=================================================================================================");

        // Print the table header with specific column widths
        System.out.printf("%-6s | %-10s | %-10s | %-12s | %-8s | %-10s | %-15s%n",
                "ID", "X (px)", "Y (px)", "Flux", "Pixels", "Elongation", "Classification");
        System.out.println("-------------------------------------------------------------------------------------------------");

        // Iterate through and print each object
        for (int i = 0; i < objects.size(); i++) {
            SourceExtractor.DetectedObject obj = objects.get(i);

            // Format the classification string based on the streak flag
            String classification;
            if (obj.isStreak) {
                double angleDegrees = Math.toDegrees(obj.angle);
                classification = String.format("Streak (%.1f°)", angleDegrees);
            } else {
                classification = "Point Source";
            }

            // Print the formatted row (decimals rounded to 2 places for readability)
            System.out.printf("%-6d | %-10.2f | %-10.2f | %-12.2f | %-8d | %-10.2f | %-15s%n",
                    (i + 1),
                    obj.x,
                    obj.y,
                    obj.totalFlux,
                    obj.pixelCount,
                    obj.elongation,
                    classification);
        }
        System.out.println("=================================================================================================\n");
    }



    /**
     * Prints a formatted table of all confirmed moving tracks to the console.
     * @param tracks The list of Track objects returned by TrackLinker
     */
    public static void printTracks(List<TrackLinker.Track> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            System.out.println("No moving objects or streaks found.");
            return;
        }

        System.out.println("\n=======================================================================================================");
        System.out.println(" CONFIRMED TRACKS REPORT (Total: " + tracks.size() + ")");
        System.out.println("=======================================================================================================");

        // Print the table header
        System.out.printf("%-8s | %-15s | %-10s | %-10s | %-8s | %-10s | %-10s | %-12s%n",
                "Track ID", "Type", "Start X", "Start Y", "Frames", "Vel X", "Vel Y", "Speed (px/f)");
        System.out.println("-------------------------------------------------------------------------------------------------------");

        // Iterate through and print each track
        for (int i = 0; i < tracks.size(); i++) {
            TrackLinker.Track track = tracks.get(i);

            // Format the classification
            String type = track.isStreakTrack ? "Streak" : "Point Source";

            // Get the starting coordinates (from the very first frame the object appeared in)
            double startX = track.points.isEmpty() ? 0.0 : track.points.get(0).x;
            double startY = track.points.isEmpty() ? 0.0 : track.points.get(0).y;

            int framesSpanned = track.points.size();

            // Calculate overall speed magnitude (Pythagorean theorem: a^2 + b^2 = c^2)
            double speed = Math.sqrt((track.velocityX * track.velocityX) + (track.velocityY * track.velocityY));

            // Print the formatted row
            System.out.printf("%-8d | %-15s | %-10.2f | %-10.2f | %-8d | %-10.2f | %-10.2f | %-12.2f%n",
                    (i + 1),
                    type,
                    startX,
                    startY,
                    framesSpanned,
                    track.velocityX,
                    track.velocityY,
                    speed);
        }
        System.out.println("=======================================================================================================\n");
    }
}
