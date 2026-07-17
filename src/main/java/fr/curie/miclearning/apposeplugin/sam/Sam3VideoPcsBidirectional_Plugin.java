package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.apposeplugin.FrameRangeSelector;
import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.apposeplugin.VideoPcsResultsConsumer;
import fr.curie.miclearning.tools.detection.Detection3dUtils;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.MultiFrameDataManager;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import static fr.curie.miclearning.apposeplugin.sam.Sam3Dialogs.ALL_ROIS_GROUPS_TXT;
import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ plugin: SAM3 promptable-concept-segmentation on video.
 */
public class Sam3VideoPcsBidirectional_Plugin implements PlugIn {

    // sentinels for the "remembered" positive/negative group selection
    private static final int NONE_SELECTION = -1; // user hasn't picked anything yet
    private static final int ALL_SELECTION = -2;  // user picked "all ROI groups" (for positive prompts only)

    // owner keys for DialogHelpBar sticky messages
    private static final String ROI_AVAILABILITY_OWNER = "roiAvailability";
    private static final String NO_OPTION_OWNER = "noOptionSelected";

    private static final String PREF_LAST_DETECT_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final String PREF_LAST_TRACK_MODEL_KEY = "miclearning.lastmodeldir.videopcs.trackingmodel";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-pcs-video-bidirectional.py";

    private static final Color COLOR_INSTRUCTION = Color.MAGENTA;

    protected ImagePlus imp;
    int nFrames; // number of frames in the video
    int axis; // 3 : Z axis - 4 : Time axis

    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();
    private String detectionModelPath;
    private String trackingModelPath;
    private String textPrompt = "visual";
    private boolean textPromptUsed;
    private boolean visualPositivePromptUsed;
    private boolean negativePromptUsed;
    private List<Integer> positiveGroups;
    private int negativeGroup;
    private Sam3ModelParameters detectionParams;
    private DetectionUtils.OutputOptions outputOptions;

    // frame range chosen by the user in the FrameRangeSelector widget (1-indexed)
    private int chosenFirstFrame;
    private int chosenPromptFrame;
    private int chosenLastFrame;

    @Override
    public void run(String s) {
        // --- 1. get image and ROIs ---
        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        if (!imp.isRGB()) {
            IJ.error("Only RGB images are supported.");
            return;
        }

        // define time axis
        final int[] dims = imp.getDimensions();
        int numberOfFrame = dims[4];
        int numberOfSlices = dims[3];
        if (numberOfSlices > 1 && numberOfFrame > 1) { // does not work on hyperstack for now
            IJ.error("Hyperstacks are not supported");
            return;
        }
        axis = numberOfSlices >= numberOfFrame ? 3 : 4;  // just using the axis with multiple images as time axis
        nFrames = dims[axis];

        // 1.2 get selected ROIs list and extract the potential prompts ROIs
        RoiManager roiManager = getRoiManager();
        Roi[] selectedRoiList = roiManager.getSelectedRoisAsArray();

        RoiPromptExtractor.RoiSelectionResult roiSelection = roiPromptExtractor.extract(selectedRoiList, axis, nFrames);
        int firstUnusedRoiId;

        if (!roiSelection.hasUsableRois()) {
            firstUnusedRoiId = 1;
            //imp.setPosition(1);
        } else {
            //imp.setPosition(roiSelection.getFirstFramePosition());
            roiManager.runCommand("Show All");
            firstUnusedRoiId = roiSelection.getFirstUnusedGroupId();
        }

        // --- 2. retrieve parameters (model path, prompts, output options) ---
        if (Macro.getOptions() != null) {
            parseMacro();
        } else {
            askUser(roiSelection, selectedRoiList);
        }

        if (outputOptions == null || detectionModelPath == null || detectionParams == null) {
            return;
        }

        long startTime = System.nanoTime();

        // --- 3. Prepare prompts, define config ---
        // text prompt (even if just "visual"
        Map<String, Integer> classIdMap = new HashMap<>();
        classIdMap.put(textPrompt, firstUnusedRoiId);

        // visual prompts, positive and negative - taken from the frame the user chose as prompt frame
        List<Roi> roisAtPromptFrame = roiPromptExtractor.getRoisAtFrame(selectedRoiList, axis, chosenPromptFrame);
        RoiPromptExtractor.PromptRois promptRois = roiPromptExtractor.buildPromptRois(
                roisAtPromptFrame,
                visualPositivePromptUsed ? positiveGroups : Collections.emptyList(),
                negativePromptUsed ? negativeGroup : null);

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

        // frame range, 0-indexed, as chosen by the user
        int posFirstFrame = chosenFirstFrame - 1;
        int posPromptFrame = chosenPromptFrame - 1;
        int posEndFrame = chosenLastFrame - 1;
        detectionParams.setNFrameToProcess(posEndFrame - posFirstFrame + 1);

        // prepare model and run configuration
        Sam3VideoRunConfig config;
        try {
            config = new Sam3VideoRunConfig.Builder()
                    .modelPath(detectionModelPath, trackingModelPath)
                    .textPrompt(textPrompt, textPromptUsed)
                    .visualPrompts(promptRois.getPositive(), promptRois.getNegative(),
                            visualPositivePromptUsed, negativePromptUsed)
                    .frameRange(posFirstFrame,posPromptFrame, posEndFrame)
                    .detectionParams(detectionParams)
                    .outputOptions(outputOptions)
                    .classIdMap(classIdMap)
                    .build();
        } catch (IllegalStateException e) {
            IJ.error("Invalid configuration", e.getMessage());
            return;
        }

        //prepare output
        MultiFrameDataManager mfdManager = new MultiFrameDataManager(posFirstFrame, posEndFrame);

        recordInMacro();
        IJ.log("\n   --- Starting SAM3 Promptable Concept Segmentation on video --- ");
        printParameters(config);

        // --- 4. Run ---
        runSam3(config, mfdManager, startTime);
    }

    private void runSam3(Sam3VideoRunConfig config, MultiFrameDataManager mfdManager, long startTime) {
        // create queue and thread to process results
        BlockingQueue<Map<String, Object>> resultsQueue = new LinkedBlockingQueue<>();
        ThreadFactory consumerThreadFactory = r -> {
            Thread t = new Thread(r, "sam3-result-consumer");
            t.setDaemon(true);
            return t;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(consumerThreadFactory);

        try (Sam3VideoPythonRunner runner = new Sam3VideoPythonRunner(SCRIPT_PATH, ENV_FILE_PATH)) {
            runner.initialize();

            VideoPcsResultsConsumer consumer = new VideoPcsResultsConsumer(
                    resultsQueue, mfdManager, config.getClassIdMap(), config.getTextPrompt(), imp);
            Future<Void> consumerResult = executor.submit(consumer);

            IJ.log("Executing python script...");
            runner.runBlocking(config, imp, resultsQueue);

            // Wait for the consumer to finish draining the queue before generating outputs
            consumerResult.get();

            IJ.log(" --- Generating outputs... ");
            Detection3dUtils.generate3dOutputs(imp, mfdManager, config.getOutputOptions());

        } catch (IOException | BuildException e) {
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
            IJ.log(" --- SAM3 segmentation and detection complete. Total time= " + totalTimeInSeconds + " sec ---");
        }
    }

    private void askUser(RoiPromptExtractor.RoiSelectionResult roiSelection, Roi[] selectedRoiList) {
        final Map<Integer, List<Roi>> roisByFrame = roiPromptExtractor.groupRoisByFrame(selectedRoiList, axis);
        // NonBlockingGenericDialog (rather than plain GenericDialog): the image window and the RoiManager stay interactive while this dialog is open
        NonBlockingGenericDialog gd = new NonBlockingGenericDialog("SAM3 Promptable Concept Segmentation on video");

        // 1- dynamic HELP SECTION (at the top ? or put at bottom ?)
        DialogHelpBar helpBar = new DialogHelpBar(gd);
        gd.addPanel(helpBar.getPanel());

        final Button okButton = gd.getButtons()[0]; // ok button is the first one

        // 2- INSTRUCTIONS to download model
        ActionListener modelInstructionAction = e -> {
            Sam3Dialogs.addDownloadInstruction();
        };
        gd.addButton("Instructions to download SAM3 model", modelInstructionAction);
        gd.addMessage("");

        // 3- MODEL PATH
        Sam3Dialogs.addModelPathDialog(gd, PREF_LAST_DETECT_MODEL_KEY);
        gd.addMessage("Optional: you can use a different model for tracking (sam 2 or sam 3)");
        Sam3Dialogs.addModelPathDialog(gd, PREF_LAST_TRACK_MODEL_KEY);

        // 4- PROMPT(S)
        // first define FRAME RANGE SELECTOR (first / prompt / last)
        FrameRangeSelector frameSelector = new FrameRangeSelector(imp, nFrames, helpBar);
        if (roiSelection.hasUsableRois()) {
            // default: prompt frame starts on the earliest frame that has usable ROIs
            frameSelector.setPromptFrame(roiSelection.getFirstFramePosition());
        } else {
            frameSelector.setPromptFrame(imp.getCurrentSlice());
        }

        // 4.1 create checkboxes and panels
        // 4.1.1 text prompt
        gd.addMessage("__________");
        gd.addCheckbox("Text prompt", true);
        Checkbox textCB = (Checkbox) gd.getCheckboxes().lastElement();

        Panel textPromptPanel = new Panel(new GridLayout(2, 1));
        textPromptPanel.add(new Label("Please enter a prompt"));
        TextField textPromptField = new TextField(10);
        textPromptPanel.add(textPromptField);
        gd.addPanel(textPromptPanel);

        helpBar.attachDynamic(okButton, () ->
                (textCB.getState() && textPromptField.getText().trim().isEmpty())
                        ? new DialogHelpBar.Hint("Please enter a text prompt to enable prediction with text prompt.", Color.RED)
                        : null);

        // 4.1.2 positive visual prompt(s)
        gd.addCheckbox("Positive visual prompt(s)", false);
        Checkbox posVisualPromptCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel posVisualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice posChoiceList = new Choice();
        posVisualPromptPanel.add(new Label("Select positive group(s):"));
        posVisualPromptPanel.add(posChoiceList);
        gd.addPanel(posVisualPromptPanel);
        posVisualPromptPanel.setVisible(false);

        // 4.1.3 negative visual prompt(s)
        gd.addCheckbox("Negative visual prompt(s)", false);
        Checkbox negVisualPromptCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel negVisualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice negChoiceList = new Choice();
        negVisualPromptPanel.add(new Label("Select negative group(s):"));
        negVisualPromptPanel.add(negChoiceList);
        gd.addPanel(negVisualPromptPanel);
        negVisualPromptPanel.setVisible(false);


        // 4.2 gate the visual-prompt checkboxes and choice list on whether the current prompt frame (as chosen in frameSelector)
        // has usable ROIs. if no usable ROI (so only prompt allowed = text prompt), block the checkboxes + print help message at top
        //
        // "remembered" selections (updated only by the user directly interacting with a choice
        final int[] rememberedPositiveSelection = {NONE_SELECTION};
        final int[] rememberedNegativeSelection = {NONE_SELECTION};

        // guards the choice lists' own item listeners while the selection is programmatically changed during a refresh
        final boolean[] updatingChoices = {false};

        // listeners to remember last selected frame
        posChoiceList.addItemListener(e -> {
            IJ.log("posChoicelist triggered");
            if (updatingChoices[0]) return;
            String sel = posChoiceList.getSelectedItem();
            if (Objects.equals(sel, ALL_ROIS_GROUPS_TXT)) {
                rememberedPositiveSelection[0] = ALL_SELECTION;
            } else if (sel != null) {
                rememberedPositiveSelection[0] = Integer.parseInt(sel.split(" ")[0]);
            }
        });
        negChoiceList.addItemListener(e -> {
            if (updatingChoices[0]) return;
            String sel = negChoiceList.getSelectedItem();
            if (sel != null) {
                rememberedNegativeSelection[0] = Integer.parseInt(sel.split(" ")[0]);
            }
        });

        // adapt help text depending on if Roi choice list is empty
        final String requireRoiHint = "No usable ROI on the current prompt frame - add point/box ROI(s) and restart the plugin, \r"
                + "\n"
                + "or pick a different prompt frame, to enable visual prompts.";
        helpBar.attachDynamic(posVisualPromptCB, () ->
                roisByFrame.getOrDefault(frameSelector.getPromptFrame(), Collections.emptyList()).isEmpty()
                        ? new DialogHelpBar.Hint( "No usable ROI on the current prompt frame - add point/box ROI(s) and restart the plugin, \r"
                        + "\n"
                        + "or pick a different prompt frame, to enable visual prompts.", COLOR_INSTRUCTION)
                        : new DialogHelpBar.Hint("Select which ROI group(s) on the current prompt frame to use as positive prompt(s).", Color.BLUE));
        helpBar.attachDynamic(negVisualPromptCB, () ->
                roisByFrame.getOrDefault(frameSelector.getPromptFrame(), Collections.emptyList()).isEmpty()
                        ? new DialogHelpBar.Hint(requireRoiHint, COLOR_INSTRUCTION)
                        : new DialogHelpBar.Hint("Select which ROI group on the current prompt frame to use as a negative prompt.", Color.BLUE));


        Runnable updateVisualPromptState = () -> {
            IJ.log("updateVisualPromptState called here!");
            int frame = frameSelector.getPromptFrame();
            List<Roi> roisAtFrame = roisByFrame.getOrDefault(frame, Collections.emptyList());
            TreeSet<Integer> groupsAtFrame = new TreeSet<>();
            for (Roi r : roisAtFrame) groupsAtFrame.add(r.getGroup());
            boolean anyGroupAtFrame = !groupsAtFrame.isEmpty();

            boolean posMismatch = refreshGroupChoiceList(posChoiceList, groupsAtFrame, rememberedPositiveSelection,
                    true, updatingChoices);
            boolean negMismatch = refreshGroupChoiceList(negChoiceList, groupsAtFrame, rememberedNegativeSelection,
                    false, updatingChoices);

            IJ.log("posMismatch: " + posMismatch + " negMismatch: " + negMismatch);
            IJ.log("rememberedPositiveSelection: " + Arrays.toString(rememberedPositiveSelection));
            IJ.log("rememberedNegativeSelection: " + Arrays.toString(rememberedNegativeSelection));


            if (!anyGroupAtFrame) {
                IJ.log("   pas de ROI sur la frame");
                if (posVisualPromptCB.getState() || negVisualPromptCB.getState()) {
                    IJ.log("   au moins un des deux doit être turned off de force");
                    helpBar.sticky(ROI_AVAILABILITY_OWNER, "Prompt frame " + frame
                            + " has no usable ROI - visual prompts turned off.", Color.RED);
                } else {
                    helpBar.clearSticky(ROI_AVAILABILITY_OWNER);
                }
                posVisualPromptCB.setState(false);
                negVisualPromptCB.setState(false);
            } else {
                IJ.log("   des ROIs sur la frame !!");
                if (posMismatch && posVisualPromptCB.getState()) {
                    helpBar.sticky(ROI_AVAILABILITY_OWNER, "The previously selected positive ROI group isn't on frame "
                            + frame + " - positive visual prompt turned off", Color.RED);
                    posVisualPromptCB.setState(false);
                }
                if (negMismatch && negVisualPromptCB.getState()) {
                    helpBar.sticky(ROI_AVAILABILITY_OWNER, "The previously selected negative ROI group isn't on frame "
                            + frame + " - negative visual prompt turned off", Color.RED);
                    negVisualPromptCB.setState(false);
                }
                if (!posMismatch && !negMismatch) {
                    helpBar.clearSticky(ROI_AVAILABILITY_OWNER);
                }
            }

            // greyed out (not setEnabled(false) - see FrameRangeSelector javadoc for why) when
            // there's nothing to choose; the checkboxes' own item listeners (below) block
            // checking them in that state.
            Color groupColor = anyGroupAtFrame ? Color.BLACK : Color.GRAY;
            posVisualPromptCB.setForeground(groupColor);
            negVisualPromptCB.setForeground(groupColor);
            checkCBs(textCB, textPromptPanel, posVisualPromptCB, posVisualPromptPanel, negVisualPromptCB, negVisualPromptPanel, helpBar);

            gd.pack();
        };
        frameSelector.addPromptFrameListener(updateVisualPromptState);
        updateVisualPromptState.run(); // initial state, based on the default prompt frame set above

        // 4.4 update panels visibility

        gd.addDialogListener(new DialogListener() {
            @Override
            public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
                IJ.log("dialog item changed called here: " + e);
                updateVisualPromptState.run();

               checkCBs(textCB, textPromptPanel, posVisualPromptCB, posVisualPromptPanel, negVisualPromptCB, negVisualPromptPanel, helpBar);

                // clear the ROI messages
                helpBar.clearSticky(ROI_AVAILABILITY_OWNER);
                gd.pack();
                return true;
            }
        });

        // 5 - RANGE SELECTOR
        gd.addMessage("__________");
        gd.addMessage("Frame range");
        gd.addPanel(frameSelector.getPanel());

        // 6- PARAMETERS
        gd.addMessage("__________");
        detectionParams = new Sam3ModelParameters();
        detectionParams.setNFrameToProcess(nFrames);
        Sam3Dialogs.addParameterDialog(gd, detectionParams, DetectionUtils.DetectionMode.VIDEO);

        // 7- OUTPUTS
        gd.addMessage("__________");
        Sam3Dialogs.addOutputDialog(gd, DetectionUtils.DetectionMode.VIDEO);

        // 8- SHOW DIALOG
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // 9- get results
        // frame range
        chosenFirstFrame = frameSelector.getFirstFrame();
        chosenPromptFrame = frameSelector.getPromptFrame();
        chosenLastFrame = frameSelector.getLastFrame();

        // model path
        detectionModelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_DETECT_MODEL_KEY);
        trackingModelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_TRACK_MODEL_KEY);

        // prompt
        // text prompt
        textPromptUsed = textCB.getState();
        gd.getNextBoolean();
        textPrompt = textPromptField.getText();
        if(textPrompt == null || textPrompt.trim().isEmpty()) textPromptUsed = false;
        if (!textPromptUsed) textPrompt="visual";
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
            List<Roi> roisAtChosenPromptFrame = roisByFrame.getOrDefault(chosenPromptFrame, Collections.emptyList());
            TreeSet<Integer> groupsAtChosenFrame = new TreeSet<>();
            for (Roi r : roisAtChosenPromptFrame) groupsAtChosenFrame.add(r.getGroup());

            positiveGroups = new ArrayList<>();
            if (Objects.equals(selectedPositiveGroup, ALL_ROIS_GROUPS_TXT)) { // if all ROI group are prompts
                for (int id : groupsAtChosenFrame) {
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

        // detection and tracking parameters
        Sam3Dialogs.getParameters(gd, detectionParams, DetectionUtils.DetectionMode.VIDEO);
        // outputs
        outputOptions = Sam3Dialogs.getOutputAnswer(gd,DetectionUtils.DetectionMode.VIDEO);
    }

    private void checkCBs(Checkbox textCB, Panel textPromptPanel, Checkbox posVisualPromptCB, Panel posVisualPromptPanel,
                          Checkbox negVisualPromptCB, Panel negVisualPromptPanel, DialogHelpBar helpBar) {
        boolean textChecked = textCB.getState();
        boolean visualChecked = posVisualPromptCB.getState();
        boolean negativeChecked = negVisualPromptCB.getState();

        textPromptPanel.setVisible(textChecked);
        posVisualPromptPanel.setVisible(visualChecked);
        negVisualPromptPanel.setVisible(negativeChecked);

        // if no option selected (no box checked) : warning
        if (!textChecked && !visualChecked) {
            helpBar.sticky(NO_OPTION_OWNER, "Please select at least one option to perform prediction.", Color.RED);
        } else {
            helpBar.clearSticky(NO_OPTION_OWNER);
        }
    }

    /**
     * Rebuilds one group {@link Choice} list (positive or negative) for the ROI groups present
     * on the current prompt frame, and re-selects the user's remembered choice if it's still
     * available there.
     * <p>
     *
     * @param allowAll whether an "ALL_ROIS_GROUPS_TXT" entry should be offered when there's more
     *                 than one group (true for the positive list, false for the negative one)
     * @return {@code true} if the user had a real remembered choice (a specific group, or ALL)
     *         that is no longer available on this frame i.e. the caller should warn/uncheck.
     *         {@code false} if there was nothing to remember yet, or the remembered choice is
     *         still honored.
     */
    private boolean refreshGroupChoiceList(Choice choiceList, TreeSet<Integer> groupsAtFrame,
                                           int[] remembered, boolean allowAll, boolean[] updatingChoices) {
        updatingChoices[0] = true;
        try {
            choiceList.removeAll();
            if (groupsAtFrame.isEmpty()) {
                return remembered[0] != NONE_SELECTION; // had a choice, now nothing at all to offer
            }

            if (allowAll && groupsAtFrame.size() > 1) {
                choiceList.add(ALL_ROIS_GROUPS_TXT);
            }
            for (int groupId : groupsAtFrame) {
                choiceList.add(RoiPromptExtractor.formatGroupLabel(groupId));
            }

            boolean hadMemory = remembered[0] != NONE_SELECTION;
            boolean rememberedIsAll = remembered[0] == ALL_SELECTION;
            boolean rememberedAvailable = hadMemory && (rememberedIsAll || groupsAtFrame.contains(remembered[0]));

            if (rememberedAvailable) {
                if (rememberedIsAll) {
                    if (groupsAtFrame.size() > 1) {
                        choiceList.select(ALL_ROIS_GROUPS_TXT);
                    } else {
                        choiceList.select(0); // only one group present - it stands in for "all"
                    }
                } else {
                    choiceList.select(RoiPromptExtractor.formatGroupLabel(remembered[0]));
                }
                return false; // no mismatch, choice is respected
            }

            choiceList.select(0); // visual default; caller decides whether to warn/uncheck
            return hadMemory;
        } finally {
            updatingChoices[0] = false;
        }
    }


    private void parseMacro() {

    }

    private void recordInMacro() {

    }

    private void printParameters(Sam3VideoRunConfig config) {
        Sam3ModelParameters params = config.getDetectionParams();
        boolean sameModel = trackingModelPath == null || trackingModelPath.trim().isEmpty() || trackingModelPath.equals(detectionModelPath);
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        if (sameModel) IJ.log("model: " + config.getDetectionModelPath());
        else {
            IJ.log("detection model: " + config.getDetectionModelPath());
            IJ.log("tracking model: " + config.getTrackingModelPath());
        }
        IJ.log("number of frame to process: " + config.getFrameCount()
                + " (frame " + (config.getFirstFrame() + 1) + (config.getFrameCount()>1 ? " to " + (config.getEndFrame() + 1): "" )+ (config.getFirstFrame()!=config.getPromptFrame()? " - prompt frame: " + (config.getPromptFrame()+1): "") + ")" );
        if (config.isTextPromptUsed()) IJ.log("text prompt: " + config.getTextPrompt().toUpperCase());
        if (config.isVisualPositivePromptUsed()) IJ.log("visual prompts: " + config.getPositiveRois().size() + " ROI(s) as positive prompt(s)");
        if (config.isNegativePromptUsed()) IJ.log("visual prompts: " + config.getNegativeRois().size() + " ROI(s) as negative prompt(s)");
        int nFrameBtwDetec = params.getNFrameBtwDetections();
        if (nFrameBtwDetec >= config.getFrameCount()) IJ.log("Detection only on frame " + config.getPromptFrame());
        else IJ.log("detection every " + (nFrameBtwDetec>1 ? nFrameBtwDetec + " frames" : "frame"));
        IJ.log("confidence threshold for detection: " + params.getConfidenceThreshold());
        IJ.log("----------------------");
    }

}
