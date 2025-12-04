package fr.curie.sam;

import fr.curie.tools.DetectionUtils;
import ij.gui.GenericDialog;

public class SamDialogs {

    // --- SAM Dialog methods ---
    public static DetectionUtils.OutputOptions askUserForOutputs() {
        GenericDialog gd = new GenericDialog("SAM Segmentation Outputs");
        addOutputDialog(gd);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return null; // User canceled
        }

        return getOutputAnswer(gd);
    }

    public static void addOutputDialog(GenericDialog gd) {
        gd.addMessage("Select the outputs to generate:");

        gd.addCheckbox("Add_Shape_ROIs to ROI Manager", false);
        gd.addCheckbox("Create_Stack_Mask (one slice per instance, unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask (unique value per instance)", false);
        gd.addCheckbox("Create_Semantic_Mask (unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask_per_Class (one slice per stack)", false);
    }

    public static DetectionUtils.OutputOptions getOutputAnswer(GenericDialog gd) {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        options.addToRoiManagerBB = false; // bouding boxes are not an output of SAM
        options.addToRoiManagerShapes = gd.getNextBoolean();
        options.createStackMask = gd.getNextBoolean();
        options.createInstanceMask = gd.getNextBoolean();
        options.createSemanticMask = gd.getNextBoolean();
        options.createInstanceMaskPerClass = gd.getNextBoolean();

        return options;
    }

}
