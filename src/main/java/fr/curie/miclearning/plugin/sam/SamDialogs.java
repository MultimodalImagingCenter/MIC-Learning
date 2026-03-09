package fr.curie.miclearning.plugin.sam;

import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.gui.GenericDialog;

import java.util.Objects;

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

        gd.addCheckbox("Add_Shape_ROIs to ROI Manager", true);
        gd.addCheckbox("    Replace input ROIs by results ROIs", false);
        gd.addCheckbox("Create_Stack_Mask (one slice per instance, unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask (unique value per instance)", false);
        gd.addCheckbox("Create_Semantic_Mask (unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask_per_Class (one slice per stack)", false);

        // TODO : add this message only if one ROI group is 0
        gd.addMessage("Warning : class is deducted from ROI group. If ROI has no group, group will be assigned 255.");
    }

    public static DetectionUtils.OutputOptions getOutputAnswer(GenericDialog gd) {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        options.addToRoiManagerBB = false; // bounding boxes are not an output of SAM
        options.addToRoiManagerShapes = gd.getNextBoolean();
        options.deletePreviousRoi = gd.getNextBoolean();
        options.createStackMask = gd.getNextBoolean();
        options.createInstanceMask = gd.getNextBoolean();
        options.createSemanticMask = gd.getNextBoolean();
        options.createInstanceMaskPerClass = gd.getNextBoolean();

        return options;
    }

    public static void addNegativeGroupDialog(GenericDialog gd, int groupNumber, String[] negativeGroupSelection) {
        //if multiple ROI groups, ask if one of them corresponds to negative prompts
        if (groupNumber > 1) {
            gd.addMessage("__________");
            gd.addMessage("If one of the ROI groups corresponds to negative inputs, indicate the group ID below :");
            gd.addChoice("negative group ID", negativeGroupSelection, negativeGroupSelection[0]);
        }
    }

    public static int getNegativeGroup(GenericDialog gd, int groupNumber, String ONLY_POSITIVE_TXT, String GROUP_ZERO_TXT){
        if (groupNumber > 1) {
            String negativeGroupName = gd.getNextChoice();
            if (!Objects.equals(negativeGroupName, ONLY_POSITIVE_TXT)) {
                if (Objects.equals(negativeGroupName, GROUP_ZERO_TXT)){
                    return  0;
                } else {
                    return Integer.parseInt(negativeGroupName);
                }
            }
        }
        return -1; // no negativ group
    }


}
