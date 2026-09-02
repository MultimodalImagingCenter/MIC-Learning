package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.gui.YesNoCancelDialog;
import ij.plugin.frame.RoiManager;
import org.apache.commons.lang3.math.NumberUtils;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static ij.plugin.frame.RoiManager.getRoiManager;
import fr.curie.miclearning.tools.detection.DetectionUtils.DetectionMode;

public class Sam3Dialogs {

    public static final String ALL_ROIS_GROUPS_TXT = "all ROIs (except negative group, if any)";
    public static final Font SECTION_HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final String PREF_LAST_TRACK_MODEL_KEY = "miclearning.lastmodeldir.videopcs.trackingmodel";

    public static void addModelPathDialog(GenericDialog gd, String lastModelPrefKey, DialogHelpBar helpBar) {
        addModelPathDialog(gd, lastModelPrefKey);
        attachFieldHint(gd, helpBar, (TextField) gd.getStringFields().lastElement(),"Path to the SAM3 model checkpoint (.pt file)");
    }
    public static void addModelPathDialog(GenericDialog gd, String lastModelPrefKey) {
        gd.addFileField("Model_Path:", resolveDefaultModelPath(lastModelPrefKey), 60);
    }

    static String resolveDefaultModelPath(String lastModelPrefKey) {
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

    public static void addImageBasicParameterDialog(GenericDialog gd, Sam3ModelParameters params,  DialogHelpBar helpBar) {
        gd.addMessage("Detection Settings", Sam3Dialogs.SECTION_HEADER_FONT);
        gd.addNumericField("Confidence_threshold:", params.getConfidenceThreshold(), 2);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Minimum detection probability (0-1) for a object to be added.");
    }

    public static void getImageBasicParameters(GenericDialog gd, Sam3ModelParameters params) {
        double confThreshold = gd.getNextNumber();
        if (confThreshold < 0 || confThreshold > 1) {
            IJ.log("Confidence Threshold must be between 0 and 1. Using default value: " + params.getConfidenceThreshold());
            confThreshold = params.getConfidenceThreshold();
        }
        params.setConfidenceThreshold(confThreshold);
    }

    /**
     * Opens a secondary dialog for the rarely-tuned detection parameters.
     */
    public static void openImageAdvancedParametersDialog(Sam3ModelParameters params, int minWidth) {
        GenericDialog gd = new GenericDialog("Advanced parameters");
        DialogHelpBar helpBar = new DialogHelpBar(gd, DialogHelpBar.loadSavedMode());
        gd.addPanel(helpBar.getPanel());

        gd.addMessage("Detection Settings", SECTION_HEADER_FONT);
        gd.addNumericField("Mask_score_threshold:", params.getMaskScoreThreshold(), 2);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Minimum per-pixel mask score (centered on 0) for a pixel to be included in the mask.");
        gd.addNumericField("Segmentation_masks_side_length", params.getMaxSideLengthDetect(), 0);
        attachFieldHint(gd, helpBar, (TextField) gd.getNumericFields().lastElement(),
                "Masks are downsized to at most this side length (pixels) during detection. \n" +
                        "Lower this value for faster results but coarser segmentation.");
        gd.addCheckbox("Include_coordinate_encoding", params.isIncludeCoordinateEncoding());
        // TODO attach hint
        gd.pack();
        helpBar.lockWidth(Math.max(gd.getPreferredSize().width, minWidth));
        gd.pack();
        gd.showDialog();
        if (gd.wasCanceled()) return ;

        double maskThreshold = gd.getNextNumber();
        params.setMaskScoreThreshold(maskThreshold);

        int maxSideLengthDetect = (int) gd.getNextNumber();
        if (maxSideLengthDetect <= 0) {
            IJ.log("Mask side length of masks must be >0. using default value: " + params.getMaxSideLengthDetect());
            maxSideLengthDetect = params.getMaxSideLengthDetect();
        }
        params.setMaxSideLengthDetect(maxSideLengthDetect);

        boolean includeCoordinateEncoding = gd.getNextBoolean();
        params.setIncludeCoordinateEncoding(includeCoordinateEncoding);
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
        gd.addCheckbox("Include_coordinate_encoding", params.isIncludeCoordinateEncoding());
        // TODO attach hint

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

        boolean includeCoordinateEncoding = gd.getNextBoolean();
        params.setIncludeCoordinateEncoding(includeCoordinateEncoding);

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

    /**
     * Attaches {@code hint} to  the label and the choice list GenericDialog just added
     */
    public static void attachChoicedHint(GenericDialog gd, DialogHelpBar helpBar, Choice choice, String hint) {
        helpBar.attachHelp(gd.getLabel(), hint);
        helpBar.attachHelp(choice, hint);
    }

    /**
     * Attaches {@code hint} to  the label and the input field GenericDialog just added
     */
    public static void attachFieldHint(GenericDialog gd, DialogHelpBar helpBar, TextField field, String hint) {
        helpBar.attachHelp(gd.getLabel(), hint);
        helpBar.attachHelp(field, hint);
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

    public static void addDownloadInstruction() {
        GenericDialog gd = new GenericDialog("instructions to download SAM3 model");
        gd.addMessage("The SAM checkpoints are available on the SAM3 HuggingFace repository (huggingface.co/facebook/sam3).\n" +
                "To download them, you need to:\n" +
                "   1/ Create a Hugging Face account\n" +
                "   2/ Request access\n" +
                "     The authorization process usually takes no more than one hour.\n" +
                "   3/ Once your access request is approved, you can download the \"sam3.pt\" file.\n" +
                "   4/ Create a \"sam3\" folder inside the \"models\" subfolder of ImageJ, and copy the model file inside it.");
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