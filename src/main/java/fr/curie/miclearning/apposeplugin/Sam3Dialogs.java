package fr.curie.miclearning.apposeplugin;

import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.apache.commons.lang3.math.NumberUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static ij.plugin.frame.RoiManager.getRoiManager;

public class Sam3Dialogs {

    public static void addModelPathDialog(GenericDialog gd, String lastModelPrefKey) {
        String defaultDir = null;
        String lastDir = Prefs.get(lastModelPrefKey, defaultDir);
        if (lastDir != null && Files.isDirectory(Paths.get(lastDir))) {
            defaultDir = lastDir;
        } else {
            defaultDir = getDefaultSam3ModelsDir();
        }

        gd.addDirectoryField("Model_Path:", defaultDir, 60);
    }

    public static String getModelPath(GenericDialog gd, String lastModelPrefKey) {
        String modelPathString = gd.getNextString();
        Path modelPath = Paths.get(modelPathString);
        if (Files.exists(modelPath)) {
            // Save the selected directory for next time
            Prefs.set(lastModelPrefKey, modelPathString);
            Prefs.savePreferences();
            return modelPathString;
        } else {
            IJ.error("Selection Error", "The selected path is not a valid directory:\n" + modelPathString);
            return null;
        }
    }

    public static void addThresholdDialog(GenericDialog gd) {
        gd.addNumericField("Confidence threshold", 0.5, 2);
    }

    public static double getThreshold(GenericDialog gd) {
       double threshold =  gd.getNextNumber();
       if (threshold < 0 || threshold >1) {
            IJ.log("Confidence threshold must be a value between 0 and 1. Default value 0.5 will be used.");
           threshold = 0.5;
       }
       return threshold;
    }


    public static void addTextPromptDialog(GenericDialog gd) {
        gd.addStringField("Text_prompt", "", 10);
        gd.addNumericField("Roi_ID", getFirstUnusedRoiID(), 0);
    }

    public static void addMultiTextPromptDialog(GenericDialog gd) {
        gd.addMessage("Prompts: Enter one or more text prompts, separated by commas.\n");
        gd.addMessage("ROI Group IDs: (Optional) Enter a Group ID for each prompt, separated by commas.\n" +
                "   ROI Group IDs are int value, between 0 (no group) and 255.\n" +
                "   If you only provide one ID, it will be assigned to the first prompt, and the following prompts will be numbered sequentially from that starting point." );
        gd.addStringField("Text_prompt", "", 20);
        gd.addStringField("Roi_ID", String.valueOf(getFirstUnusedRoiID()), 15);
    }



    public static Map<String, Integer> getMultiTextPrompt(GenericDialog gd) {
        String prompts = gd.getNextString();
        if (prompts == null || prompts.trim().isEmpty()) {
            IJ.log("Prompt empty. Closing plug-in.\n");
            IJ.error("Please enter a valid prompt.");
            return null;
        }
        String[] promptArray = prompts.split(",");

        String roiIDs = gd.getNextString();
        if (roiIDs == null || roiIDs.trim().isEmpty()) {
            roiIDs = String.join("", Collections.nCopies(promptArray.length, "0,"));
        }
        String[] roiIDArray = roiIDs.split(",");

        return getClassIdMapFromArrays(promptArray,roiIDArray);
    }

    public static Map<String, Integer> getClassIdMapFromArrays(String[] promptArray, String[] roiIDArray) {
        int nPrompts = promptArray.length;
        int[] roiIds = new int[nPrompts];

        if (roiIDArray.length ==0) {
            Arrays.fill(roiIds, 0);
        } else if (!((nPrompts == roiIDArray.length) || roiIDArray.length == 1)) {
            IJ.log("Invalid number of ROI IDs ("+ roiIDArray.length +" ROI-ID and "+ nPrompts +" prompts). Closing plug-in.\n");
            IJ.error("Please provide either 1 Roi ID or as many as prompts.");
            return null;
        }

        if (roiIDArray.length == 1 && nPrompts != 1) {
            // 1 ROI-ID given : the following ROI groups are numbered sequentially
            int initialId = NumberUtils.toInt(roiIDArray[0], 0);
            if (initialId < 0 || initialId >255) {
                IJ.log("ROI group Id must be a value between 0 (no group) and 255. Group 0 (no group) will be used for all prompts.");
                Arrays.fill(roiIds, 0);
            } else if (initialId == 0){
                IJ.log("Initial id 0 given. Group 0 (no group) will be used for all prompts.");
                Arrays.fill(roiIds, 0);
            } else {
                for (int i = 0; i < nPrompts; i++) {
                    int id = (initialId +i)%255;
                    roiIds[i] = id;
                }
            }
        } else {
            // as many ROI-ID as prompts
            for (int i = 0; i < nPrompts; i++) {
                int id = NumberUtils.toInt(roiIDArray[i], 0);
                if (id < 0 || id >255) {
                    IJ.log("ROI group Id must be a value between 0 (no group) and 255. " +
                            "Invalid Roi ID: " + id + " "+
                            "Group 0 (no group) will be used.");
                    id = 0;
                }
                roiIds[i] = id;
            }
        }

        Map<String, Integer> classIdMap = new LinkedHashMap<>();

        for (int i = 0; i < nPrompts; i++) {
            String prompt = promptArray[i];
            if (!prompt.trim().isEmpty()) {
                classIdMap.put(prompt.trim(), roiIds[i]);
            }
        }

        return classIdMap;
    }

    public static Map<String, Integer> getTextPrompt(GenericDialog gd) {
        String prompt = gd.getNextString();
        if (prompt == null || prompt.trim().isEmpty()) {
            IJ.log("Prompt empty. Closing plug-in.\n");
            IJ.error("Please enter a valid prompt.");
            return null;
        }

        int roiID = (int) gd.getNextNumber();
        if (roiID < 0 || roiID >255) {
            IJ.log("ROI group Id must be a value between 0 (no group) and 255. Group 0 (no group) will be used.");
            roiID = 0;
        }
        Map<String, Integer> classIdMap = new LinkedHashMap<>();
        classIdMap.put(prompt, roiID);

        return classIdMap;
    }

    public static int getFirstUnusedRoiID(){
        // create id-name map list
        // get ROIs list
        RoiManager roiManager = getRoiManager();
        Roi[] roiList = roiManager.getRoisAsArray();
        // get ROI groups IDs
        Set<Integer> uniqueGroups = new TreeSet<>();
        for (Roi roi : roiList) {
            uniqueGroups.add(roi.getGroup());
        }
        if (uniqueGroups.isEmpty()) return 1; {}

        // find first unused roi ID
        for (int roiID = 1; roiID <= 255; roiID++) {
            if (!uniqueGroups.contains(roiID)) {
                return roiID;
            }
        }
        return 0;
    }

    public static void addOutputDialogDetectionImage(GenericDialog gd) {
        gd.addMessage("Select the outputs to generate:");
        gd.addCheckbox("Add_Bounding_Boxes to ROI Manager", false);
        gd.addCheckbox("Add_Shape_ROIs to ROI Manager", true);
        gd.addCheckbox("Create_Stack_Mask (one slice per instance, unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask (unique value per instance)", false);
        gd.addCheckbox("Create_Semantic_Mask (unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask_per_Class (one slice per stack)", false);
    }

    public static DetectionUtils.OutputOptions getOutputAnswerDetectionImage(GenericDialog gd) {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        options.addToRoiManagerBB = gd.getNextBoolean();
        options.addToRoiManagerShapes = gd.getNextBoolean();
        options.deletePreviousRoi = false;
        options.createStackMask = gd.getNextBoolean();
        options.createInstanceMask = gd.getNextBoolean();
        options.createSemanticMask = gd.getNextBoolean();
        options.createInstanceMaskPerClass = gd.getNextBoolean();

        return options;
    }

    private static String getDefaultSam3ModelsDir() {
        String imagejRoot = IJ.getDirectory("imagej");

        if (imagejRoot != null) {
            Path modelPath = Paths.get(imagejRoot, "models", "sam3.pt");
            // Check if the file 'sam3.pt' exist in the 'models' directory
            if (Files.exists(modelPath)) {
                return modelPath.toString();
            } else {
                // check in the MiclearningModels folder
                modelPath = Paths.get(imagejRoot, "models", "MicLearningModels", "sam3.pt");
                if (Files.exists(modelPath)) {
                    return modelPath.toString();
                } else {
                    return IJ.getDirectory("home"); // Fallback to user's home directory
                }
            }
        } else {
            //IJ.log("Warning: Could not determine ImageJ installation directory. Defaulting to user home.");
            return IJ.getDirectory("home"); // Fallback to user's home directory
        }
    }

    public static void addDownloadInstruction() {
        GenericDialog gd = new GenericDialog("instructions to download SAM3 model");
        gd.addMessage("The SAM checkpoints are available on the SAM3 HuggingFace repository (huggingface.co/facebook/sam3).\n" +
                "To download them, you need to:\n" +
                "   1/ Create a Hugging Face account\n" +
                "   2/ Request access\n" +
                "The authorization process usually takes no more than an hour.\n" +
                "Once your access request is approved, you can download the \"sam3.pt\" file and place it in the \"models\" subfolder of ImageJ.");
        gd.hideCancelButton();
        gd.showDialog();

    }

}
