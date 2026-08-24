package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.apposeplugin.MultiImagePcsResultsConsumer;
import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.tools.detection.Detection3dUtils;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;
import ij.process.ImageConverter;
import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static fr.curie.miclearning.apposeplugin.DialogHelpBar.DEFAULT_INSTRUCTION_COLOR;
import static fr.curie.miclearning.apposeplugin.DialogHelpBar.DEFAULT_WARNING_COLOR;
import static fr.curie.miclearning.apposeplugin.sam.Sam3Dialogs.ALL_ROIS_GROUPS_TXT;
import static fr.curie.miclearning.tools.detection.DetectionUtils.DetectionMode;
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

    // owner keys for DialogHelpBar sticky messages
    private static final String NO_OPTION_OWNER = "noOptionSelected";

    private static final String TARGET_SAME_AS_PROMPT_TXT = "same as prompt image";

    protected static ImagePlus refImp; // image used to encode the prompt
    private int refFrame;
    private int refSlice;
    protected static ImagePlus targetImp;

    // Prompts
    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();
    private String textPrompt = "visual";
    private boolean textPromptUsed;

    private List<Roi> roiOnFrame;
    private boolean visualPositivePromptUsed;
    private boolean negativePromptUsed;
    private List<Integer> positiveGroups;
    private int negativeGroup;

    private String modelPath;
    private Sam3ModelParameters detectionParams;
    private DetectionUtils.OutputOptions outputOptions;

    private final Map<Integer, List<ProcessedDetection>> detectionsByFrame = new HashMap<>();

    @Override
    public void run(String s) {

        // --- 1. get image and ROIs ---
        // 1.1 get selected image
        refImp = IJ.getImage(); // select active image
        if (refImp == null) {
            IJ.error("no image opened");
            return;
        }

        if (!refImp.isRGB()){
            ImageConverter impConverter = new ImageConverter(refImp);
            impConverter.convertToRGB();
            IJ.log("\nimage " + refImp.getTitle() + " converted to RGB");
        }

        // 1.2 get selected ROIs list
        // make any existing ROIs visible;
        RoiManager roiManager = getRoiManager();
        if (roiManager.getCount() > 0) roiManager.runCommand("Show All");

        // find refImage position along time and slice axis (if original imp is a stack, 0 otherwise)
        refFrame = refImp.getFrame();
        refSlice = refImp.getSlice();

        // find Rois on the refImage
        Roi[] selectedRoi = roiManager.getRoisAsArray(); // all ROI in the roiManager
        roiOnFrame = roiPromptExtractor.getRoisAtFrame(selectedRoi, 0, refSlice, refFrame); //only Roi on the active frame/slice
        if (roiOnFrame.isEmpty()) {
            visualPositivePromptUsed = false;
            negativePromptUsed = false;
        }

        // --- 2. retrieve parameters (model path, prompts, target image, output options) ---
        if (Macro.getOptions() != null) {
            parseMacro();
        } else {
            askUser();
        }

        if (outputOptions == null || modelPath == null || detectionParams == null || targetImp == null) {
            return;
        }

        if (!targetImp.isRGB()) {
            ImageConverter targetConverter = new ImageConverter(targetImp);
            targetConverter.convertToRGB();
            IJ.log("\nimage " + targetImp.getTitle() + " converted to RGB");
        }
        // target image processed in full if it's a stack, on a single image otherwise
        int targetNFrames = Math.max(targetImp.getNSlices(), targetImp.getNFrames());
        DetectionMode stackMode = targetNFrames > 1 ? DetectionMode.MULTI_IMAGE : DetectionMode.SINGLE_IMAGE;

        long startTime = System.nanoTime();

        // --- 3. Prepare prompts, define config ---
        // group ids in use anywhere in the RoiManager, so the prompt's own group id doesn't collide with an existing one
        RoiPromptExtractor.RoiGroupIdsScanResult groupIdScan = roiPromptExtractor.scanGroupsIds(roiOnFrame);
        int firstUnusedRoiId = groupIdScan.hasUsableRois() ? groupIdScan.getFirstUnusedGroupId() : 1;

        // text prompt (even if just "visual")
        Map<String, Integer> classIdMap = new HashMap<>();
        classIdMap.put(textPrompt, firstUnusedRoiId);

        // extract visual prompts, positive and negative - taken from the reference image only
        RoiPromptExtractor.PromptRois promptRois = roiPromptExtractor.buildPromptRois(
                roiOnFrame,
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

        Sam3CrossImageRunConfig config;
        try {
            config= new Sam3CrossImageRunConfig.Builder()
                    .modelPath(modelPath)
                    .textPrompt(textPrompt, textPromptUsed)
                    .visualPrompts(promptRois.getPositive(), promptRois.getNegative(),
                            visualPositivePromptUsed, negativePromptUsed)
                    .detectionParams(detectionParams)
                    .outputOptions(outputOptions)
                    .classIdMap(classIdMap)
                    .stackMode(stackMode)
                    .build();
        } catch (IllegalStateException e) {
            IJ.error("Invalid configuration", e.getMessage());
            return;
        }

        recordInMacro();
        IJ.log("\n   --- Starting SAM3 Promptable Concept Segmentation - cross image --- ");
        printParameters(config);

        // --- 4. Run ---
        // only the current frame of the reference image is used to encode the prompt
        ImagePlus refFrameImp = new ImagePlus(refImp.getTitle(), refImp.getProcessor());
        runSam3(config, refFrameImp, startTime);

    }

    private void runSam3(Sam3CrossImageRunConfig config, ImagePlus refFrameImp, long startTime) {
        BlockingQueue<Map<String, Object>> resultsQueue = new LinkedBlockingQueue<>();
        ThreadFactory consumerThreadFactory = r -> {
            Thread t = new Thread(r, "sam3-crossimage-consumer");
            t.setDaemon(true);
            return t;
        };
        
         ExecutorService executor = Executors.newSingleThreadExecutor(consumerThreadFactory);

        try (Sam3CrossImagePythonRunner runner = new Sam3CrossImagePythonRunner(SCRIPT_PATH, ENV_FILE_PATH)) {
            runner.initialize();

            MultiImagePcsResultsConsumer consumer = new MultiImagePcsResultsConsumer(
                    resultsQueue, detectionsByFrame, config.getClassIdMap(), config.getTextPrompt(), targetImp);
            Future<Void> consumerResult = executor.submit(consumer);

            IJ.log("Executing python script...");
            runner.runBlocking(config, refFrameImp, targetImp, resultsQueue);

            consumerResult.get();

            IJ.log(" --- Generating output... ");
            generateOutputs(config);

        }catch (IOException | BuildException e) {
            IJ.error("Unable to prepare Python environment", String.valueOf(e.getMessage()));
            IJ.log("ERROR while preparing python environment: " + e);
        } catch (TaskException e) {
            IJ.error("Python task error", String.valueOf(e.getMessage()));
            IJ.log("ERROR while running python script: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IJ.log("Processing was interrupted.");
        } catch (ExecutionException e) {
            IJ.error("Error while processing results", String.valueOf(e.getCause()));
            IJ.log("ERROR in result consumer: " + e.getCause());
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            long endTime = System.nanoTime();
            double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
            IJ.log(" --- SAM3 segmentation complete. " + detectionsByFrame.size()
                    + " Frame processed. Total time= " + totalTimeInSeconds + " sec ---");
        }
    }

    private void generateOutputs(Sam3CrossImageRunConfig config) {
        DetectionUtils.OutputOptions options = config.getOutputOptions();
        if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
            IJ.selectWindow(targetImp.getTitle());
            RoiManager roiManager = getRoiManager();
            Detection3dUtils.addTrackedRoisToManager(roiManager, detectionsByFrame,
                    options.addToRoiManagerBB, options.addToRoiManagerShapes, Detection3dUtils.GroupingMethod.BY_OBJECT);
            roiManager.setVisible(true);
            roiManager.runCommand("Show All");
        }

        if (options.createInstanceMask) {
            ImagePlus instanceMaskStack = DetectionUtils.createInstanceMaskStack(targetImp, detectionsByFrame);
            if (instanceMaskStack != null) instanceMaskStack.show();
        }

        if (options.createSemanticMask) {
            ImagePlus semanticMaskStack = DetectionUtils.createSemanticMaskStack(targetImp, detectionsByFrame);
            if (semanticMaskStack != null) semanticMaskStack.show();
        }

    }

    private void recordInMacro() {
        Recorder.setCommand("SAM3 PCS Cross-Image");
        Recorder.recordOption("model_path", modelPath);

        if (textPromptUsed) Recorder.recordOption("text_prompt", textPrompt);

        if (visualPositivePromptUsed) {
            String positiveGroupIds = positiveGroups.stream().map(String::valueOf).collect(Collectors.joining(","));
            Recorder.recordOption("positive_group_ids", positiveGroupIds);
        }
        if (negativePromptUsed) Recorder.recordOption("negative_group_id", String.valueOf(negativeGroup));

        Recorder.recordOption("target_image", targetImp.getTitle());

        Recorder.recordOption("confidence", String.valueOf(detectionParams.getConfidenceThreshold()));
        Recorder.recordOption("mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold()));
        Recorder.recordOption("max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect()));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_box_rois");
        if (outputOptions.addToRoiManagerShapes) Recorder.recordOption("add_shape_rois");
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask");
        if (outputOptions.createSemanticMask) Recorder.recordOption("create_semantic_mask");
    }

    private void parseMacro() {
        IJ.log("\nSAM3 cross image pcs on macro");
        String options = Macro.getOptions();

        // model path
        modelPath = Macro.getValue(options, "model_path", null);
        if (modelPath == null || modelPath.trim().isEmpty()) {
            IJ.log("No model path. Closing plug-in.\n");
            IJ.error("No model path specified.");
            return;
        }

        // text prompt
        textPrompt = Macro.getValue(options, "text_prompt", "");
        textPromptUsed = !textPrompt.trim().isEmpty();
        if (!textPromptUsed) textPrompt = "visual";

        // visual prompt - positive group(s), comma-separated RoiManager group IDs
        String positiveGroupIds = Macro.getValue(options, "positive_group_ids", "");
        positiveGroups = new ArrayList<>();
        if (!positiveGroupIds.trim().isEmpty()) {
            for (String id : positiveGroupIds.split(",")) {
                positiveGroups.add(Integer.parseInt(id.trim()));
            }
        }
        visualPositivePromptUsed = !positiveGroups.isEmpty();

        // visual prompt - negative group
        negativeGroup = Integer.parseInt(Macro.getValue(options, "negative_group_id", "-1"));
        negativePromptUsed = negativeGroup != -1;

        // target image - defaults to the reference (active) image if not specified
        String targetTitle = Macro.getValue(options, "target_image", "");
        targetImp = targetTitle.trim().isEmpty() ? refImp : WindowManager.getImage(targetTitle);
        if (targetImp == null) {
            IJ.log("Target image \"" + targetTitle + "\" not found. Closing plug-in.\n");
            IJ.error("Target image not found: " + targetTitle);
            return;
        }

        // detection parameters
        detectionParams = new Sam3ModelParameters();
        double confidenceThreshold = Double.parseDouble(Macro.getValue(options, "confidence", String.valueOf(detectionParams.getConfidenceThreshold())));
        if (confidenceThreshold < 0 || confidenceThreshold > 1) confidenceThreshold = detectionParams.getConfidenceThreshold();
        detectionParams.setConfidenceThreshold(confidenceThreshold);

        double maskThreshold = Double.parseDouble(Macro.getValue(options, "mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold())));
        detectionParams.setMaskScoreThreshold(maskThreshold);

        int maxSideLength = Integer.parseInt(Macro.getValue(options, "max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect())));
        if (maxSideLength <= 0) maxSideLength = detectionParams.getMaxSideLengthDetect();
        detectionParams.setMaxSideLengthDetect(maxSideLength);

        // outputs
        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false")) || options.contains("add_box_rois ");
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false")) || options.contains("add_shape_rois ");
        outputOptions.deletePreviousRoi = false;
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false")) || options.contains("create_instance_mask ");
        outputOptions.createSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "create_semantic_mask", "false")) || options.contains("create_semantic_mask ");
        outputOptions.createInstanceMaskPerClass = false;
    }

    private void askUser() {
        GenericDialog gd =  new GenericDialog("SAM3 Promptable Concept Segmentation - cross image");
        // 1- HELP SECTION
        DialogHelpBar helpBar = new DialogHelpBar(gd, DialogHelpBar.loadSavedMode());
        gd.addButton("Settings", e -> helpBar.openSettingsDialog()); // only helpbar setting for nox, other settings could be added in the future
        gd.addPanel(helpBar.getPanel());
        final Button okButton = gd.getButtons()[0]; // ok button is the first one

        // 2- MODEL PATH
        gd.addMessage("Model", Sam3Dialogs.SECTION_HEADER_FONT);
        gd.addButton("Instructions to download SAM3 model", e ->Sam3Dialogs.addDownloadInstruction());
        Sam3Dialogs.addModelPathDialog(gd, PREF_LAST_DETECT_MODEL_KEY);
        Sam3Dialogs.attachFieldHint(gd, helpBar, (TextField) gd.getStringFields().lastElement(),
                "Path to the SAM3 model checkpoint (.pt file)");

        // 3- PROMPT(S)
        // 3.1 create checkboxes and panels
        gd.addMessage("Prompt", Sam3Dialogs.SECTION_HEADER_FONT);

        // 3.1.1 text prompt
        gd.addCheckbox("Text prompt", true);
        Checkbox textCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel textPromptPanel = new Panel(new GridLayout(2, 1));
        textPromptPanel.add(new Label("Please enter a prompt"));
        TextField textPromptField = new TextField(10);
        textPromptPanel.add(textPromptField);

        // card panel for 2 options : checkbox checked or not
        Panel textEmptyCard = new Panel();
        Panel textCardHolder = new Panel(new CardLayout());
        textCardHolder.add(textEmptyCard, "EMPTY");
        textCardHolder.add(textPromptPanel, "CONTENT");
        CardLayout textCardLayout = (CardLayout) textCardHolder.getLayout();


        // hint to inform no text prompt has been entered
        helpBar.attachHelp(okButton, () ->
                (textCB.getState() && textPromptField.getText().trim().isEmpty())
                        ? new DialogHelpBar.Hint("Please enter a text prompt to enable prediction with text prompt.", DEFAULT_WARNING_COLOR)
                        : null);

        // 3.1.2 positive visual prompt(s)
        gd.addCheckbox("Positive visual prompt(s)", false);
        Checkbox posVisualPromptCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel posVisualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice posChoiceList = new Choice();
        posVisualPromptPanel.add(new Label("Select positive group(s):"));
        posVisualPromptPanel.add(posChoiceList);

        // card panel for 2 options : checkbox checked or not
        Panel posEmptyCard = new Panel();
        final Panel posCardHolder = new Panel(new CardLayout());
        posCardHolder.add(posEmptyCard, "EMPTY");
        posCardHolder.add(posVisualPromptPanel, "CONTENT");
        final CardLayout posCardLayout = (CardLayout) posCardHolder.getLayout();


        // 3.1.3 negative visual prompt(s)
        gd.addCheckbox("Negative visual prompt(s)", false);
        Checkbox negVisualPromptCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel negVisualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice negChoiceList = new Choice();
        negVisualPromptPanel.add(new Label("Select negative group(s):"));
        negVisualPromptPanel.add(negChoiceList);

        // card panel for 2 options : checkbox checked or not
        Panel negEmptyCard = new Panel();
        final Panel negCardHolder = new Panel(new CardLayout());
        negCardHolder.add(negEmptyCard, "EMPTY");
        negCardHolder.add(negVisualPromptPanel, "CONTENT");
        final CardLayout negCardLayout = (CardLayout) negCardHolder.getLayout();
        negCardLayout.show(negCardHolder, "EMPTY");

        // 3.1.4 add all prompts panel in column
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(5, 10, 5, 10);

        Panel column1 = addColumn(gbc, textCB, textCardHolder);
        Panel column2 = addColumn(gbc, posVisualPromptCB, posCardHolder);
        Panel column3 = addColumn(gbc, negVisualPromptCB, negCardHolder);

        Panel promptsColumnsPanel = new Panel(new GridLayout(1, 3));
        promptsColumnsPanel.add(column1);
        promptsColumnsPanel.add(column2);
        promptsColumnsPanel.add(column3);

        gd.addPanel(promptsColumnsPanel);

        // 3.2 Display groupID lists and able checkbox depending on whether the refImage has usable ROI
        boolean noRoiOnFrame = roiOnFrame.isEmpty();
        // adapt help text depending on whether ROI(s) are present on the current prompt frame
        final String requireRoiHint = "No usable ROI on the prompt image. \n" +
                "To enable visual prompts, add point/box ROI(s) to the ROI manager " +
                "and resart the plugin.";
        helpBar.attachHelp(posVisualPromptCB, () ->
                noRoiOnFrame ? new DialogHelpBar.Hint( requireRoiHint, DEFAULT_INSTRUCTION_COLOR)
                        : new DialogHelpBar.Hint("Select which ROI group(s) on the current prompt frame to use as positive prompt(s).", Color.BLUE));
        helpBar.attachHelp(negVisualPromptCB, () ->
                noRoiOnFrame ? new DialogHelpBar.Hint(requireRoiHint, DEFAULT_INSTRUCTION_COLOR)
                        : new DialogHelpBar.Hint("Select which ROI group on the current prompt frame to use as a negative prompt.", Color.BLUE));

        if (!noRoiOnFrame){
            // choice list depending on Roi on the refImage
            TreeSet<Integer> uniqueGroups = roiPromptExtractor.scanGroupsIds(roiOnFrame).getUniqueGroups();
            if (uniqueGroups.size() > 1) posChoiceList.add(ALL_ROIS_GROUPS_TXT);
            for (int groupId : uniqueGroups) {
                posChoiceList.add(RoiPromptExtractor.formatGroupLabel(groupId));
                negChoiceList.add(RoiPromptExtractor.formatGroupLabel(groupId));
            }

            // if ROI on frame, default prompt = visual prompt
            textCB.setState(false);
            textCardLayout.show(textCardHolder, "EMPTY");
            posVisualPromptCB.setState(true);
            posCardLayout.show(posCardHolder, "CONTENT");

        } else {
            textCB.setState(true);
            textCardLayout.show(textCardHolder, "CONTENT");
            // grey checkboxes
            posVisualPromptCB.setForeground(Color.GRAY);
            posCardLayout.show(posCardHolder, "EMPTY");
            negVisualPromptCB.setForeground(Color.GRAY);
        }

        // 3.3 update panels visibility
        gd.addDialogListener((dialog, e) -> {
            boolean textChecked = textCB.getState();
            boolean visualChecked = posVisualPromptCB.getState();
            boolean negativeChecked = negVisualPromptCB.getState();

            if (noRoiOnFrame){
                visualChecked = false;
                posVisualPromptCB.setState(visualChecked);
                negativeChecked = false;
                negVisualPromptCB.setState(negativeChecked);
            }

            textCardLayout.show(textCardHolder, textChecked ? "CONTENT" : "EMPTY");
            posCardLayout.show(posCardHolder, visualChecked ? "CONTENT" : "EMPTY");
            negCardLayout.show(negCardHolder, negativeChecked ? "CONTENT" : "EMPTY");

            // if no option selected (no box checked) : warning
            if (!textChecked && !visualChecked) {
                helpBar.warn(NO_OPTION_OWNER, "Please select at least one positive prompt option (either text or visual) to perform prediction.");
            } else {
                helpBar.clearWarning(NO_OPTION_OWNER);
            }

            dialog.pack();
            return true;
        });

        // 4- TARGET IMAGE
        gd.addMessage("Prompt and target image", Sam3Dialogs.SECTION_HEADER_FONT);
        int[] dims = refImp.getDimensions();
        String refPosition = (dims[4] == 1 && dims[3] == 1) ? "" : " (" +(dims[3]==1?"":" slice "+refSlice) + (dims[4]==1?"":" frame "+refFrame) + " )";
        gd.addMessage("Prompt image: " + refImp.getTitle() + refPosition);
        helpBar.attachHelp(gd.getMessage(), "Image to encode the prompt on.");
        gd.addImageChoice("Target_image:", refImp.getTitle());
        Choice targetImageChoice = (Choice) gd.getChoices().lastElement();
        for (int i = 0; i < targetImageChoice.getItemCount(); i++) {
            if (targetImageChoice.getItem(i).equals(refImp.getTitle())) {
                targetImageChoice.remove(i);
                targetImageChoice.insert(TARGET_SAME_AS_PROMPT_TXT, i);
                targetImageChoice.select(i);
                break;
            }
        }
        String targetImageHint = "Image (or image stack) to run the promptable concept segmentation on.\n" +
                        "If it is a stack, segmentation is run independently on every image of the stack.";
        Sam3Dialogs.attachChoicedHint(gd, helpBar, targetImageChoice, targetImageHint);

        // 5- OUTPUTS
        gd.addMessage("Outputs", Sam3Dialogs.SECTION_HEADER_FONT);
        Sam3Dialogs.addOutputDialog(gd, DetectionMode.MULTI_IMAGE);

        // 6- PARAMETERS
        detectionParams = new Sam3ModelParameters();
        detectionParams.setIncludeCoordinateEncoding(false);
        gd.addMessage("");
        Sam3Dialogs.addImageBasicParameterDialog(gd, detectionParams, helpBar);
        gd.addButton("Advanced parameters...", e ->
                Sam3Dialogs.openImageAdvancedParametersDialog(detectionParams, gd.getWidth()));

        // 7- SHOW DIALOG
        gd.pack();
        helpBar.lockWidth(gd.getPreferredSize().width);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // 8- get results
        // model path
        modelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_DETECT_MODEL_KEY);

        // prompt
        // text prompt
        textPromptUsed = textCB.getState();
        gd.getNextBoolean();
        textPrompt = textPromptField.getText();
        if (textPrompt == null || textPrompt.trim().isEmpty()) textPromptUsed = false;
        if (!textPromptUsed) textPrompt = "visual";

        // visual positive prompt
        visualPositivePromptUsed = posVisualPromptCB.getState();
        gd.getNextBoolean();
        String selectedPositiveGroup = posChoiceList.getSelectedItem();

        // visual negative prompt
        negativePromptUsed = negVisualPromptCB.getState();
        gd.getNextBoolean();
        if (negativePromptUsed) {
            String selectedNegativeGroup = negChoiceList.getSelectedItem();
            negativeGroup = selectedNegativeGroup != null ? Integer.parseInt(selectedNegativeGroup.split(" ")[0]) : -1;
            if (negativeGroup == -1) negativePromptUsed = false;
        }

        if (visualPositivePromptUsed) {
            TreeSet<Integer> uniqueGroups = roiPromptExtractor.scanGroupsIds(roiOnFrame).getUniqueGroups();
            positiveGroups = new ArrayList<>();
            if (Objects.equals(selectedPositiveGroup, ALL_ROIS_GROUPS_TXT)) { // if all ROI group are prompts
                for (int id : uniqueGroups) {
                    if (!negativePromptUsed || id != negativeGroup) {
                        positiveGroups.add(id);
                    } else {
                        IJ.log("group " + id + " used as negative prompt");
                    }
                }
            } else if (selectedPositiveGroup != null) { // if only one group is prompt
                int id = Integer.parseInt(selectedPositiveGroup.split(" ")[0]);
                if (negativePromptUsed && id == negativeGroup) {
                    IJ.log("WARNING : same group can't be used for negative and positive visual prompt. " +
                            "No visual prompts (neither positive nor negative) will be used.");
                    negativePromptUsed = false;
                    visualPositivePromptUsed = false;
                } else {
                    positiveGroups.add(id);
                }
            }
        }
        if (positiveGroups == null || positiveGroups.isEmpty()) visualPositivePromptUsed = false;

        // target image
        targetImp = gd.getNextImage();
        if (targetImp == null) targetImp = refImp;

        // outputs
        outputOptions = Sam3Dialogs.getOutputAnswer(gd, DetectionMode.MULTI_IMAGE);

        // detection parameters
        Sam3Dialogs.getImageBasicParameters(gd, detectionParams);
    }

    private Panel addColumn(GridBagConstraints gbc, Checkbox cb, Panel cardHolder){
        Panel column = new Panel(new GridBagLayout());
        GridBagConstraints gc = (GridBagConstraints) gbc.clone();
        gc.gridy = 0; gc.weighty = 1; gc.fill = GridBagConstraints.VERTICAL;
        column.add(new Panel(), gc); // top filler
        gc = (GridBagConstraints) gbc.clone();
        gc.gridy = 1;
        column.add(cb, gc);
        gc = (GridBagConstraints) gbc.clone();
        gc.gridy = 2;
        column.add(cardHolder, gc);
        gc = (GridBagConstraints) gbc.clone();
        gc.gridy = 3; gc.weighty = 1; gc.fill = GridBagConstraints.VERTICAL;
        column.add(new Panel(), gc); // bottom filer

        return column;
    }

    private void printParameters(Sam3CrossImageRunConfig config) {
        Sam3ModelParameters params = config.getDetectionParams();
        int targetNFrames = Math.max(targetImp.getNSlices(), targetImp.getNFrames());
        IJ.log("----------------------");
        int[] dims = refImp.getDimensions();
        String refPosition = (dims[4] == 1 && dims[3] == 1) ? "" : " (" +(dims[3]==1?"":" slice "+refSlice) + (dims[4]==1?"":" frame "+refFrame) + " )";
        IJ.log("reference image: " + refImp.getTitle() + refPosition);
        IJ.log("target image: " + targetImp.getTitle()
                + (config.getStackMode() == DetectionMode.MULTI_IMAGE ? " (" + targetNFrames + " images)" : ""));
        IJ.log("model: " + config.getModelPath());
        if (config.isTextPromptUsed()) IJ.log("text prompt: " + config.getTextPrompt().toUpperCase());
        if (config.isVisualPositivePromptUsed()) IJ.log("visual prompts: " + config.getPositiveRois().size() + " ROI(s) as positive prompt(s)");
        if (config.isNegativePromptUsed()) IJ.log("visual prompts: " + config.getNegativeRois().size() + " ROI(s) as negative prompt(s)");
        IJ.log("confidence threshold: " + params.getConfidenceThreshold());
        IJ.log("----------------------");
    }
}
