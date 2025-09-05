package fr.curie.yolo;

import fr.curie.tools.SegmentationUtils;
import ij.gui.GenericDialog;

public class YoloUtils {

    // --- YOLO Dialog methods ---
    public static SegmentationUtils.OutputOptions askUserForYoloOutputs() {
        GenericDialog gd = new GenericDialog("YOLO Segmentation Outputs");
        addYoloOutputDialog(gd);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return null; // User canceled
        }

        return getYoloOutputAnswer(gd);
    }

    public static void addYoloOutputDialog(GenericDialog gd) {
        gd.addMessage("Select the outputs to generate:");
        gd.addCheckbox("Add_Bounding_Boxes to ROI Manager", true);

        gd.addMessage(" --- If the model is a segmentation model --");
        gd.addCheckbox("Add_Shape_ROIs to ROI Manager", false);
        gd.addCheckbox("Create_Stack_Mask (one slice per instance, unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask (unique value per instance)", false);
        gd.addCheckbox("Create_Semantic_Mask (unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask_per_Class (one slice per stack)", false);
    }

    public static SegmentationUtils.OutputOptions getYoloOutputAnswer(GenericDialog gd) {
        SegmentationUtils.OutputOptions options = new SegmentationUtils.OutputOptions();
        options.addToRoiManagerBB = gd.getNextBoolean();
        options.addToRoiManagerShapes = gd.getNextBoolean();
        options.createStackMask = gd.getNextBoolean();
        options.createInstanceMask = gd.getNextBoolean();
        options.createSemanticMask = gd.getNextBoolean();
        options.createInstanceMaskPerClass = gd.getNextBoolean();

        return options;
    }

}
