package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.process.ImageConverter;

import java.util.List;

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

    // Prompts
    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();
    private String textPrompt = "visual";
    private boolean textPromptUsed;
    private boolean visualPositivePromptUsed;
    private boolean negativePromptUsed;
    private List<Integer> positiveGroups;
    private int negativeGroup;

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
            IJ.log("image " + imp.getTitle() + " converted to RGB");
        }

        // 1.2 get selected ROIs list
        // make any existing ROIs visible;
        RoiManager roiManager = getRoiManager();
        if (roiManager.getCount() > 0) roiManager.runCommand("Show All");


    }
}
