package fr.curie.miclearning.apposeplugin;

import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.gui.YesNoCancelDialog;
import ij.plugin.frame.RoiManager;
import org.apache.commons.lang3.math.NumberUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static ij.plugin.frame.RoiManager.getRoiManager;
import fr.curie.miclearning.tools.detection.DetectionUtils.DetectionMode;

public class Sam3Dialogs {

    public static final int MAX_GROUP_VALUE = 255; // max id for group in ImageJ
    public static final String ONLY_POSITIVE_TXT = "no negative group";
    public static final String GROUP_ZERO_TXT = "0 (ROI without group) or 255";

    public static DetectionMode askStackMode(int nFrames){
        YesNoCancelDialog gd = new YesNoCancelDialog(IJ.getInstance(),
                "SAM segmentation", "Include all "+nFrames+" images?");

        if (gd.cancelPressed()) { // User canceled
            return null;
        } else if (gd.yesPressed()) { // user clicked yes
            return DetectionMode.MULTI_IMAGE;
        } else { // user clicked no
            return DetectionMode.SINGLE_IMAGE;
        }
    }

    public static void addModelDirDialogHg(GenericDialog gd, String lastModelPrefKey) {
        String defaultDir = null;
        String lastDir = Prefs.get(lastModelPrefKey, defaultDir);
        if (lastDir != null && Files.isDirectory(Paths.get(lastDir))) {
            defaultDir = lastDir;
        } else {
            defaultDir = getDefaultSam3ModelDirHg();
        }
        gd.addDirectoryField("Model_Directory:", defaultDir, 60);
    }

    public static void addModelPathDialog(GenericDialog gd, String lastModelPrefKey) {
        String defaultPath = null;
        String lastPath = Prefs.get(lastModelPrefKey, defaultPath);
        if (lastPath != null && Files.exists(Paths.get(lastPath))) {
            defaultPath = lastPath;
        } else {
            defaultPath = getDefaultSam3ModelPath();
        }

        gd.addFileField("Model_Path:", defaultPath, 60);
    }

    public static String getModelPath(GenericDialog gd, String lastModelPrefKey) {
        String modelPathString = gd.getNextString();
        Path modelPath = Paths.get(modelPathString);
        if (Files.exists(modelPath)) {
            // Save the selected path for next time
            Prefs.set(lastModelPrefKey, modelPathString);
            Prefs.savePreferences();
            return modelPathString;
        } else {
            IJ.error("Selection Error", "The selected path is not valid:\n" + modelPathString);
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
    }

    public static Map<String, Integer> getTextPrompt(GenericDialog gd) {
        String prompt = gd.getNextString();
        if (prompt == null || prompt.trim().isEmpty()) {
            IJ.log("Prompt empty. Closing plug-in.\n");
            IJ.error("Please enter a valid prompt.");
            return null;
        }

        int roiID = getFirstUnusedRoiID();
        Map<String, Integer> classIdMap = new LinkedHashMap<>();
        classIdMap.put(prompt.trim(), roiID);

        return classIdMap;
    }

    public static void addTextPromptAndGroupDialog(GenericDialog gd) {
        gd.addStringField("Text_prompt", "", 10);
        gd.addNumericField("Roi_ID", getFirstUnusedRoiID(), 0);
    }

    public static Map<String, Integer> getTextPromptAndGroup(GenericDialog gd) {
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

    public static void addMultiTextPromptDialog(GenericDialog gd) {
        gd.addMessage("Prompts: Enter one or more text prompts, separated by commas.\n");
        gd.addMessage("ROI Group IDs: (Optional) Enter a Group ID for each prompt, separated by commas.\n" +
                "   ROI Group IDs are int value, between 0 (no group) and 255.\n" +
                "   If you only provide one ID, it will be assigned to the first prompt, and the following prompts will be numbered sequentially from that starting point." );
        gd.addStringField("Text_prompt", "", 20);
        gd.addStringField("Roi_Group_ID", String.valueOf(getFirstUnusedRoiID()), 15);
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

    public static void addMaxFrameDialog(GenericDialog gd) {
        gd.addNumericField("max number of frame to segment", -1.0);
        gd.addMessage("To segment all frames, enter \"-1\"");
    }

    public static int getMaxFrame(GenericDialog gd, int nFrames) {
        int maxFrameUser = (int) gd.getNextNumber();
        if (maxFrameUser < 0) maxFrameUser = nFrames;
        return Math.min(maxFrameUser, nFrames);
    }

    public static void addParameterDialog(GenericDialog gd, Sam3Parameters params, DetectionMode mode) {
        gd.addMessage("Detection Settings");
        gd.addNumericField("Confidence Threshold:", params.getConfidenceThreshold(), 2);
        gd.addNumericField("Mask Threshold:", params.getMaskScoreThreshold(), 2);

        if (mode == DetectionMode.VIDEO) {
            gd.addMessage("Tracking Settings");
            gd.addNumericField("Frames Between Detections:", params.getNFrameBtwDetections(), 0);
            // ajouter ici d'autres paramètres si besoin
        }
    }

    public static void getParameters(GenericDialog gd, Sam3Parameters params, DetectionMode mode) {
        double confThreshold = gd.getNextNumber();
        if (confThreshold < 0 || confThreshold > 1) {
            IJ.log("Confidence Threshold must be between 0 and 1. Using default value: " + params.getConfidenceThreshold());
            confThreshold = params.getConfidenceThreshold();
        }
        params.setConfidenceThreshold(confThreshold);

        double maskThreshold = gd.getNextNumber(); // no check ?
        params.setMaskScoreThreshold(maskThreshold);

        if (mode == DetectionMode.VIDEO) {
            int nFramesBtwDetec = (int) gd.getNextNumber();
            if (nFramesBtwDetec < 0) {
                IJ.log("Number of frames between each detection must be >0. using default value: " + params.getNFrameBtwDetections());
                nFramesBtwDetec = params.getNFrameBtwDetections();
            }
            params.setNFrameBtwDetections(nFramesBtwDetec);

            // récupérer d'autres paramètres si besoin
        }
    }

    public static void addOutputDialog(GenericDialog gd, DetectionMode mode) {
        gd.addMessage("Select the outputs to generate:");
        gd.addCheckbox("Add_Bounding_Boxes to ROI Manager", false);
        gd.addCheckbox("Add_Shape_ROIs to ROI Manager", true);
        gd.addCheckbox("Create_Instance_Masks (unique value per instance)", false);

        if (mode != DetectionMode.VIDEO) {
            gd.addCheckbox("Create_Semantic_Masks (unique value per class)", false);

            if (mode == DetectionMode.SINGLE_IMAGE) {
                gd.addCheckbox("Create_Stack_Mask (one slice per instance, unique value per class)", false);
                gd.addCheckbox("Create_Instance_Mask_per_Class (one slice per class)", false);
            }
        }
    }

    public static DetectionUtils.OutputOptions getOutputAnswer(GenericDialog gd, DetectionMode mode) {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        options.deletePreviousRoi = false;
        options.addToRoiManagerBB = gd.getNextBoolean();
        options.addToRoiManagerShapes = gd.getNextBoolean();
        options.createInstanceMask = gd.getNextBoolean();
        options.createSemanticMask = mode != DetectionMode.VIDEO && gd.getNextBoolean();
        options.createStackMask = mode == DetectionMode.SINGLE_IMAGE && gd.getNextBoolean();
        options.createInstanceMaskPerClass = mode == DetectionMode.SINGLE_IMAGE && gd.getNextBoolean();

        return options;
    }

    public static void addOutputDialogImage(GenericDialog gd) {
        gd.addMessage("Select the outputs to generate:");
        gd.addCheckbox("Add_Bounding_Boxes to ROI Manager", false);
        gd.addCheckbox("Add_Shape_ROIs to ROI Manager", true);
        gd.addCheckbox("Create_Stack_Mask (one slice per instance, unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask (unique value per instance)", false);
        gd.addCheckbox("Create_Semantic_Mask (unique value per class)", false);
        gd.addCheckbox("Create_Instance_Mask_per_Class (one slice per stack)", false);
    }

    public static DetectionUtils.OutputOptions getOutputAnswerImage(GenericDialog gd) {
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


    public static void addOutputDialogVideo(GenericDialog gd) {
        gd.addMessage("Select the outputs to generate:");
        gd.addCheckbox("Add_Bounding_Boxes to ROI Manager", false);
        gd.addCheckbox("Add_Shape_ROIs to ROI Manager", true);
        gd.addCheckbox("Create_Instance_Masks (unique value per instance)", false);
    }

    public static DetectionUtils.OutputOptions getOutputAnswerVideo(GenericDialog gd) {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        options.addToRoiManagerBB = gd.getNextBoolean();
        options.addToRoiManagerShapes = gd.getNextBoolean();
        options.deletePreviousRoi = false;
        options.createStackMask = false;
        options.createInstanceMask = gd.getNextBoolean();
        options.createSemanticMask = false;
        options.createInstanceMaskPerClass = false;

        return options;
    }

    public static void addNegativeGroupDialog(GenericDialog gd, int groupNumber, String[] negativeGroupSelection) {
        //if multiple ROI groups, ask if one of them corresponds to negative prompts
        if (groupNumber > 1) {
            gd.addMessage("One group can be labeled as \"negative\" to exclude specific areas.");
            gd.addMessage("All ROIs in this group will act as negative prompts across all detection, ensuring they are ignored by the model.");
            gd.addChoice("negative group ID: ", negativeGroupSelection, negativeGroupSelection[0]);
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
        return -1; // no negative group
    }

    private static String getDefaultSam3ModelDirHg() {
        String imagejRoot = IJ.getDirectory("imagej");

        if (imagejRoot != null) {
            Path modelPath = Paths.get(imagejRoot, "models", "sam3","model.safetensors");
            // Check if the file 'sam3/model.safetensors' exist in the 'models' folder
            if (Files.exists(modelPath)) {
                return modelPath.getParent().toString();
            } else {
                // check in the MiclearningModels folder
                modelPath = Paths.get(imagejRoot, "models", "MicLearningModels", "sam3","model.safetensors");
                if (Files.exists(modelPath)) {
                    return modelPath.getParent().toString();
                } else {
                    return IJ.getDirectory("home"); // Fallback to user's home directory
                }
            }
        } else {
            //IJ.log("Warning: Could not determine ImageJ installation directory. Defaulting to user home.");
            return IJ.getDirectory("home"); // Fallback to user's home directory
        }
    }

    private static String getDefaultSam3ModelPath() {
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

    public static Map<String, Integer> getClassIdMapFromArrays(String[] promptArray, String[] roiIDArray) {
        int nPrompts = promptArray.length;
        int[] roiIds = new int[nPrompts];

        if (roiIDArray.length == 0) {
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
        return 0; // if no value available, no group
    }

    public static void addDownloadInstructionHg() {
        GenericDialog gd = new GenericDialog("instructions to download SAM3 model");
        gd.addMessage("The SAM checkpoints are available on the SAM3 HuggingFace repository (huggingface.co/facebook/sam3).\n" +
                "To download them, you need to:\n" +
                "   1/ Create a Hugging Face account\n" +
                "   2/ Request access\n" +
                "     The authorization process usually takes no more than an hour.\n" +
                "   3/ Once your access request is approved, you can download the \"model.safetensors\" file.\n" +
                "   4/ Create a \"sam3\" folder inside the \"models\" subfolder of ImageJ, and place the model file in it.");
        gd.hideCancelButton();
        gd.showDialog();
    }
    public static void addDownloadInstruction() {
        GenericDialog gd = new GenericDialog("instructions to download SAM3 model");
        gd.addMessage("The SAM checkpoints are available on the SAM3 HuggingFace repository (huggingface.co/facebook/sam3).\n" +
                "To download them, you need to:\n" +
                "   1/ Create a Hugging Face account\n" +
                "   2/ Request access\n" +
                "     The authorization process usually takes no more than an hour.\n" +
                "   3/ Once your access request is approved, you can download the \"sam3.pt\" file.\n" +
                "   4/ Create a \"sam3\" folder inside the \"models\" subfolder of ImageJ, and place the model file inside it.");
        gd.hideCancelButton();
        gd.showDialog();
    }

    public static void addBoxGroupInstructions(GenericDialog gd ,int roiNumber, int groupNumber){
        gd.addMessage("Only rectangle ROIs, selected in the ROI manager, will be processed.");
        gd.addMessage("   number of selected ROI(s): " + roiNumber);
        gd.addMessage("   number of group(s): " + groupNumber);
        gd.addMessage("Assign the same group ID to ROIs that belong to the same semantic category. \n" +
                "The system will launch a separate detection for each group, using all ROIs within that group as input.\n " +
                "Note: Group \"0\" will be internally remapped to \"255\" for processing.\n");
    }


}