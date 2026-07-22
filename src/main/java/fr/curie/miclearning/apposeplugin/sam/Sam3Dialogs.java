package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.gui.YesNoCancelDialog;
import ij.plugin.frame.RoiManager;
import org.apache.commons.lang3.math.NumberUtils;

import java.awt.Font;
import java.awt.TextField;
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
    public static final String ALL_ROIS_GROUPS_TXT = "all selected ROIs (except negative group, if any)";
    public static final Font SECTION_HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final String PREF_LAST_TRACK_MODEL_KEY = "miclearning.lastmodeldir.videopcs.trackingmodel";

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

    public static void addModelPathDialog(GenericDialog gd, String lastModelPrefKey) {
        gd.addFileField("Model_Path:", resolveDefaultModelPath(lastModelPrefKey), 60);
    }

    private static String resolveDefaultModelPath(String lastModelPrefKey) {
        String lastPath = Prefs.get(lastModelPrefKey, null);
        if (lastPath != null && Files.exists(Paths.get(lastPath))) {
            return lastPath;
        }
        return getDefaultSam3ModelPath();
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


    public static void addParameterDialog(GenericDialog gd, Sam3ModelParameters params, DetectionMode mode) {
        gd.addMessage("Detection Settings");
        gd.addNumericField("Confidence_threshold:", params.getConfidenceThreshold(), 2);
        gd.addNumericField("Mask_score_threshold:", params.getMaskScoreThreshold(), 2);
        gd.addNumericField("Max_masks_side_length", params.getMaxSideLengthDetect(),0); // advanced parameter

        if (mode == DetectionMode.VIDEO) { // unused, replaced by openVideoAdvancedParametersDialog
            gd.addMessage("Tracking Settings");
            gd.addNumericField("Frames_between detections:", params.getNFrameBtwDetections(), 0);
            gd.addNumericField("Tracking_score_threshold", params.getTrackingScoreThreshold(), 2); // advanced parameter
            gd.addNumericField("Remove_after N missed frames", params.getRemoveAfterNMissed(), 0); // advanced parameter
            gd.addNumericField("Max_masks_side_length_for_tracking", params.getMaxSideLengthTrack(),0); // advanced parameter
            gd.addNumericField("Box_iou_threshold",  params.getTrackingBoxIouThreshold(), 2); // advanced parameter
        }
    }

    public static void getParameters(GenericDialog gd, Sam3ModelParameters params, DetectionMode mode) {
        double confThreshold = gd.getNextNumber();
        if (confThreshold < 0 || confThreshold > 1) {
            IJ.log("Confidence Threshold must be between 0 and 1. Using default value: " + params.getConfidenceThreshold());
            confThreshold = params.getConfidenceThreshold();
        }
        params.setConfidenceThreshold(confThreshold);

        double maskThreshold = gd.getNextNumber(); // no check ?
        params.setMaskScoreThreshold(maskThreshold);

        int maxSideLengthDetect = (int) gd.getNextNumber();
        if (maxSideLengthDetect <= 0) {
            IJ.log("Mask side length of masks must be >0. using default value: " + params.getMaxSideLengthDetect());
            maxSideLengthDetect = params.getMaxSideLengthDetect();
        }
        params.setMaxSideLengthDetect(maxSideLengthDetect);

        if (mode == DetectionMode.VIDEO) { // unused
            int nFramesBtwDetec = (int) gd.getNextNumber();
            if (nFramesBtwDetec <= 0) {
                //IJ.log("Number of frames between each detection must be >0. using default value: " + params.getNFrameBtwDetections());
                nFramesBtwDetec = params.getNFrameToProcess() +1;
            }
            params.setNFrameBtwDetections(nFramesBtwDetec);

            double trackThreshold = gd.getNextNumber();
            if (trackThreshold < 0) {
                IJ.log("Tracking score threshold must be >0. using default value: " + params.getTrackingScoreThreshold());
                trackThreshold = params.getTrackingScoreThreshold();
            }
            params.setTrackingScoreThreshold(trackThreshold);

            int removeAfterNMissed = (int) gd.getNextNumber();
            if (removeAfterNMissed <= 0) {
                IJ.log("Detected objects will never be removed from memory.");
            }
            params.setRemoveAfterNMissed(removeAfterNMissed);

            int maxSideLengthTrack = (int) gd.getNextNumber();
            if (maxSideLengthTrack <= 0) {
                IJ.log("Mask side length of masks must be >0. using default value: " + params.getMaxSideLengthTrack());
                maxSideLengthTrack = params.getMaxSideLengthTrack();
            }
            params.setMaxSideLengthTrack(maxSideLengthTrack);

            double trackingBoxIouThreshold = gd.getNextNumber();
            if (trackingBoxIouThreshold < 0) {
                IJ.log("tracking box IoU threshold must be >0. using default value: " + params.getTrackingBoxIouThreshold());
                trackingBoxIouThreshold = params.getTrackingBoxIouThreshold();
            }
            params.setTrackingBoxIouThreshold(trackingBoxIouThreshold);
        }
    }

    /**
     * Attaches {@code hint} to  the label and the input field GenericDialog just added
     */
    public static void attachFieldHint(GenericDialog gd, DialogHelpBar helpBar, TextField field, String hint) {
        helpBar.attachHelp(gd.getLabel(), hint);
        helpBar.attachHelp(field, hint);
    }

    /** fields for the basic parameters  */
    public static void addVideoBasicParameterDialog(GenericDialog gd, Sam3ModelParameters params, DialogHelpBar helpBar) {
        gd.addMessage("Detection Settings", SECTION_HEADER_FONT);
        gd.addNumericField("Confidence_threshold:", params.getConfidenceThreshold(), 2);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Minimum detection probability (0-1) for a new object to be added.");

        gd.addMessage("Tracking Settings", SECTION_HEADER_FONT);
        gd.addNumericField("Frames_between detections:", params.getNFrameBtwDetections(), 0);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Run a full detection every N frames; tracking carries objects through the frames in between.");
    }

    public static void getVideoBasicParameters(GenericDialog gd, Sam3ModelParameters params) {
        double confThreshold = gd.getNextNumber();
        if (confThreshold < 0 || confThreshold > 1) {
            IJ.log("Confidence Threshold must be between 0 and 1. Using default value: " + params.getConfidenceThreshold());
            confThreshold = params.getConfidenceThreshold();
        }
        params.setConfidenceThreshold(confThreshold);

        int nFramesBtwDetec = (int) gd.getNextNumber();
        if (nFramesBtwDetec <= 0) {
            nFramesBtwDetec = params.getNFrameToProcess() + 1;
        }
        params.setNFrameBtwDetections(nFramesBtwDetec);
    }

    /**
     * Opens a secondary dialog for the rarely-tuned detection/tracking parameters
     * plus the optional tracking model path
     */
    public static String openVideoAdvancedParametersDialog(Sam3ModelParameters params, String currentTrackingModelPath) {
        GenericDialog gd = new GenericDialog("Advanced parameters");
        DialogHelpBar helpBar = new DialogHelpBar(gd, DialogHelpBar.loadSavedMode());
        gd.addPanel(helpBar.getPanel());

        gd.addMessage("Tracking model", SECTION_HEADER_FONT);
        gd.addMessage("Optional: use a different model for tracking (SAM2 or SAM3). \n");
        addModelPathDialog(gd, PREF_LAST_TRACK_MODEL_KEY);
        attachFieldHint(gd, helpBar, (TextField) gd.getStringFields().lastElement(),
                "Path to a SAM2 or SAM3 model checkpoint used for tracking instead of the detection model.\n" +
                        "Leave empty to use the detection model for both.");

        gd.addMessage("Detection Settings", SECTION_HEADER_FONT);
        gd.addNumericField("Mask_score_threshold:", params.getMaskScoreThreshold(), 2);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Minimum per-pixel mask score (centered on 0) for a pixel to be included in the mask.");
        gd.addNumericField("Segmentation_masks_side_length", params.getMaxSideLengthDetect(), 0);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Masks are downsized to at most this side length (pixels) during detection. \n" +
                        "Lower this value for faster results but coarser segmentation.");

        gd.addMessage("Tracking Settings", SECTION_HEADER_FONT);
        gd.addNumericField("Tracking_score_threshold", params.getTrackingScoreThreshold(), 2);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Minimum presence score for an already-tracked object to be kept.");
        gd.addNumericField("Remove_after N missed frames", params.getRemoveAfterNMissed(), 0);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Remove a tracked object from memory after this many consecutive frames without detecting it. \n" +
                        "0 or less: never remove.");
        gd.addNumericField("Segmentation_masks_side_length_for_tracking", params.getMaxSideLengthTrack(), 0);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Masks are downsized to at most this side length (pixels) during tracking. \n" +
                        "Lower this value for faster results but coarser segmentation.");
        gd.addNumericField("Box_iou_threshold", params.getTrackingBoxIouThreshold(), 2);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Minimum IoU between two bounding boxes on two frames to consider them the same object. \n" +
                        "Lower this value if objects move a lot between frames.");

        gd.pack();
        helpBar.lockWidth(gd.getPreferredSize().width);
        gd.showDialog();
        if (gd.wasCanceled()) return currentTrackingModelPath;

        String trackingModelPath = getModelPath(gd, PREF_LAST_TRACK_MODEL_KEY);

        double maskThreshold = gd.getNextNumber(); // no check ?
        params.setMaskScoreThreshold(maskThreshold);

        int maxSideLengthDetect = (int) gd.getNextNumber();
        if (maxSideLengthDetect <= 0) {
            IJ.log("Mask side length of masks must be >0. using default value: " + params.getMaxSideLengthDetect());
            maxSideLengthDetect = params.getMaxSideLengthDetect();
        }
        params.setMaxSideLengthDetect(maxSideLengthDetect);

        double trackThreshold = gd.getNextNumber();
        if (trackThreshold < 0) {
            IJ.log("Tracking score threshold must be >0. using default value: " + params.getTrackingScoreThreshold());
            trackThreshold = params.getTrackingScoreThreshold();
        }
        params.setTrackingScoreThreshold(trackThreshold);

        int removeAfterNMissed = (int) gd.getNextNumber();
        if (removeAfterNMissed <= 0) {
            IJ.log("Detected objects will never be removed from memory.");
        }
        params.setRemoveAfterNMissed(removeAfterNMissed);

        int maxSideLengthTrack = (int) gd.getNextNumber();
        if (maxSideLengthTrack <= 0) {
            IJ.log("Mask side length of masks must be >0. using default value: " + params.getMaxSideLengthTrack());
            maxSideLengthTrack = params.getMaxSideLengthTrack();
        }
        params.setMaxSideLengthTrack(maxSideLengthTrack);

        double trackingBoxIouThreshold = gd.getNextNumber();
        if (trackingBoxIouThreshold < 0) {
            IJ.log("tracking box IoU threshold must be >0. using default value: " + params.getTrackingBoxIouThreshold());
            trackingBoxIouThreshold = params.getTrackingBoxIouThreshold();
        }
        params.setTrackingBoxIouThreshold(trackingBoxIouThreshold);

        return trackingModelPath != null ? trackingModelPath : currentTrackingModelPath;
    }

    public static void addOutputDialog(GenericDialog gd, DetectionMode mode) {
        gd.addMessage("Select the outputs to generate:");
        gd.addCheckbox("Add_Bounding_Boxes to ROI Manager", false);
        gd.addCheckbox("Add_Shape_ROIs to ROI Manager", true);
        gd.addCheckbox("Create_Instance_Masks (unique value per instance)", false);

        if (mode == DetectionMode.SINGLE_IMAGE || mode == DetectionMode.MULTI_IMAGE) {
            gd.addCheckbox("Create_Semantic_Masks (unique value per class)", false);

            if (mode == DetectionMode.SINGLE_IMAGE) {
                gd.addCheckbox("Create_Instance_Mask_per_Class (one slice per class)", false);
            }
        }
    }

    public static DetectionUtils.OutputOptions getOutputAnswer(GenericDialog gd, DetectionMode mode) {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        options.deletePreviousRoi = false;
        options.addToRoiManagerBB = gd.getNextBoolean();
        options.addToRoiManagerShapes = gd.getNextBoolean();
        options.createSemanticMask = false;
        options.createInstanceMask = gd.getNextBoolean();
        options.createSemanticMask = mode != DetectionMode.VIDEO && gd.getNextBoolean();
        options.createInstanceMaskPerClass = mode == DetectionMode.SINGLE_IMAGE && gd.getNextBoolean();

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
            IJ.log("Invalid number of ROI ID(s) ("+ roiIDArray.length +" ROI ID(s) and "+ nPrompts +" prompt(s)). Closing plug-in.\n");
            IJ.error("Please provide either 1 ROI group id or as many as prompt(s).");
            return null;
        }

        if (roiIDArray.length == 1 && nPrompts != 1) {
            // 1 ROI-ID given : the following ROI groups are numbered sequentially
            int initialId = NumberUtils.toInt(roiIDArray[0], 0);
            if (initialId < 0 || initialId >255) {
                IJ.log("ROI group ID must be a value between 0 (no group) and 255. Group 0 (no group) will be used for all prompts.");
                Arrays.fill(roiIds, 0);
            } else if (initialId == 0){
                IJ.log("Initial group ID 0 given. Group 0 (no group) will be used for all prompts.");
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
                    IJ.log("ROI group ID must be a value between 0 (no group) and 255. " +
                            "Invalid ROI group ID: " + id + " "+
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
        gd.addMessage("Only rectangle and point ROIs, selected in the ROI manager, will be processed.");
        gd.addMessage("   number of selected ROI(s): " + roiNumber);
        gd.addMessage("   number of group(s): " + groupNumber);
        gd.addMessage("Assign the same group ID to ROIs that belong to the same semantic category. \n" +
                "The system will launch a separate detection for each group, using all ROIs within that group as input.\n " +
                "Note: Group \"0\" will be internally remapped to \"255\" for processing.\n");
    }
}