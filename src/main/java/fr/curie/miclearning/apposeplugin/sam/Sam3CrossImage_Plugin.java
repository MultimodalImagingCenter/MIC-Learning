package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.process.ImageConverter;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ plugin: SAM3 promptable-concept-segmentation on image(s).
 * Cross-image : prompt encoded on one reference image - prediction done on one or multiple other image(s)
 * only one class/concept using text and/or visual prompts
 */
public class Sam3CrossImage_Plugin implements PlugIn  {
    private static final String PREF_LAST_DETECT_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-pcs-cross-image.py";

    protected static ImagePlus imp;
    int axis; // 3 : Z axis - 4 : Time axis ; hyperstacks not supported
    int frame; // position of the reference image

    // Prompts
    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();
    private String textPrompt = "visual";
    private boolean textPromptUsed;
    private boolean visualPositivePromptUsed;
    private boolean negativePromptUsed;
    private List<Integer> positiveGroups;
    private int negativeGroup;
    private List<Roi> roisForRun;

    private String modelPath;
    private Sam3ModelParameters detectionParams;
    private DetectionUtils.OutputOptions outputOptions;

    @Override
    public void run(String s) {

        // --- 1. get image and ROIs ---
        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        if (!imp.isRGB()){
            ImageConverter impConverter = new ImageConverter(imp);
            impConverter.convertToRGB();
            IJ.log("\nimage " + imp.getTitle() + " converted to RGB");
        }

        // define main axis
        final int[] dims = imp.getDimensions();
        int numberOfFrame = dims[4];
        int numberOfSlices = dims[3];
        if (numberOfSlices > 1 && numberOfFrame > 1) { // does not work on hyperstack for now
            IJ.error("Hyperstacks are not supported");
            return;
        }
        axis = numberOfSlices >= numberOfFrame ? 3 : 4;  // just using the axis with multiple images as time axis
        frame = numberOfSlices >= numberOfFrame ? imp.getCurrentSlice() : imp.getFrame();
        IJ.log("axis=" + axis + ", frame=" + frame);

        // 1.2 get selected ROIs list
        // make any existing ROIs visible;
        RoiManager roiManager = getRoiManager();
        if (roiManager.getCount() > 0) roiManager.runCommand("Show All");

        Roi[] selectedRoi = roiManager.getRoisAsArray(); // all ROI in the roiManager
        List<Roi> roiOnFrame = roiPromptExtractor.getRoisAtFrame(selectedRoi, axis, frame);
        IJ.log("all roi length: " + selectedRoi.length + ", roiOnFrame length = " + roiOnFrame.size());
        if (roiOnFrame.isEmpty()) {
            IJ.log("\nno Roi on the reference image - visual prompts turned off");
            visualPositivePromptUsed = false;
            negativePromptUsed = false;
        }

        // --- 2. retrieve parameters (model path, prompts, output options) ---
        if (Macro.getOptions() != null) {
            parseMacro();
        } else {
            askUser();
        }

        if (outputOptions == null || modelPath == null || detectionParams == null) {
            return;
        }

        long startTime = System.nanoTime();

        // --- 3. Prepare prompts, define config ---
        RoiPromptExtractor.RoiGroupIdsScanResult groupIdScan = roiPromptExtractor.scanGroupsIds(roisForRun);
        int firstUnusedRoiId = groupIdScan.hasUsableRois() ? groupIdScan.getFirstUnusedGroupId() : 1;

        // text prompt (even if just "visual")
        Map<String, Integer> classIdMap = new HashMap<>();
        classIdMap.put(textPrompt, firstUnusedRoiId);

        // extract visual prompts, positive and negative
        RoiPromptExtractor.PromptRois promptRois = roiPromptExtractor.buildPromptRois(
                roisForRun,
                visualPositivePromptUsed ? positiveGroups : Collections.emptyList(),
                negativePromptUsed ? negativeGroup : null);

        // check
        if (visualPositivePromptUsed && promptRois.getPositive().isEmpty()) {
            IJ.log("Error : No positive ROIs found to process.");
            visualPositivePromptUsed = false;
        }
        if (negativePromptUsed && promptRois.getNegative().isEmpty()) {
            IJ.log("Error : No negative ROIs found to process.");
            negativePromptUsed = false;
        }

        if (!textPromptUsed && !visualPositivePromptUsed) {
            IJ.error("Prompt missing", "ERROR : no valid positive prompt (neither text nor visual) found.\n" +
                    "Please enter a prompt, either text or ROI.");
            IJ.log("ERROR : no positive prompt (neither text nor visual) found.\n" +
                    "Please enter a prompt, either text or ROI.");
            return;
        }



    }

    private void askUser() {
    }

    private void parseMacro() {
    }
}
