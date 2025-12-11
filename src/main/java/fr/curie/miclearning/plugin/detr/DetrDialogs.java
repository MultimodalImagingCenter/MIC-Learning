package fr.curie.miclearning.plugin.detr;

import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.gui.GenericDialog;

import java.time.Duration;

public class DetrDialogs {
    
    public static void addOutputDialog(GenericDialog gd) {
        gd.addMessage("Configure inputs/outputs of the run :");
        gd.addCheckbox("Add_Bounding_Boxes to ROI Manager", true);
        //gd.addCheckbox("Show detection result tables", true);
    }
    
    public static DetectionUtils.OutputOptions getOutputAnswer(GenericDialog gd) {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        // options.addToRoiManagerBB = gd.getNextBoolean();
        // IJ.log("Add Bounding Boxes to ROI Manager : "+options.addToRoiManagerBB);
        options.addToRoiManagerBB = gd.getNextBoolean();
        //options.showDetectionResultTables = gd.getNextBoolean();

        return options;
    }

    // Helper method to format seconds as "Xmin Ys" or just "Ys"
    public static String formatTime(double seconds) {
        Duration duration = Duration.ofSeconds((long) seconds);
        long minutes = duration.toMinutes();
        long secs = duration.minusMinutes(minutes).getSeconds();
        //noinspection SpellCheckingInspection
        return (minutes > 0) ? String.format("%dmin %ds", minutes, secs) : String.format("%.1fs", seconds);
    }

}
