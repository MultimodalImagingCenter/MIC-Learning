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
import ij.gui.*;
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
import javax.swing.Timer;

import static fr.curie.miclearning.apposeplugin.DialogHelpBar.*;
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
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-pcs-video-bidirectional.py";

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

    // all ROIs in the RoiManager as askUser's dialog close
    private Roi[] finalRoiSnapshot;

    @Override
    public void run(String s) {
        // --- 1. get image ---
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

        // 1.2 make any existing ROIs visible;
        RoiManager roiManager = getRoiManager();
        if (roiManager.getCount() > 0) roiManager.runCommand("Show All");

        // --- 2. retrieve parameters (model path, prompts, output options) ---
        if (Macro.getOptions() != null) {
            parseMacro();
        } else {
            askUser();
        }

        if (outputOptions == null || detectionModelPath == null || detectionParams == null) {
            return;
        }

        long startTime = System.nanoTime();

        // --- 3. Prepare prompts, define config ---
        // all ROIs as they stood when the dialog closed
        Roi[] roisForRun = finalRoiSnapshot != null ? finalRoiSnapshot : roiManager.getRoisAsArray();
        RoiPromptExtractor.RoiGroupIdsScanResult groupIdScan = roiPromptExtractor.scanGroupsIds(roisForRun);
        int firstUnusedRoiId = groupIdScan.hasUsableRois() ? groupIdScan.getFirstUnusedGroupId() : 1;

        // text prompt (even if just "visual")
        Map<String, Integer> classIdMap = new HashMap<>();
        classIdMap.put(textPrompt, firstUnusedRoiId);

        // extract visual prompts, positive and negative - taken from the frame the user chose as prompt frame
        List<Roi> roisAtPromptFrame = roiPromptExtractor.getRoisAtFrame(roisForRun, axis, chosenPromptFrame);
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
        IJ.showStatus("sam3: environment initialization");
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
            IJ.showStatus("sam3: executing python script");
            IJ.showProgress(0,100);

            runner.runBlocking(config, imp, resultsQueue);

            // Wait for the consumer to finish draining the queue before generating outputs
            consumerResult.get();

            IJ.log(" --- Generating outputs... ");
            IJ.showStatus("sam3: generating outputs");
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
            IJ.showStatus("sam3: segmentation complete");
        }
    }

    private void askUser() {
        // NonBlockingGenericDialog (rather than plain GenericDialog): the image window and the RoiManager stay interactive while this dialog is open
        NonBlockingGenericDialog gd = new NonBlockingGenericDialog("SAM3 Promptable Concept Segmentation on video");

        // 1- HELP SECTION
        DialogHelpBar helpBar = new DialogHelpBar(gd, DialogHelpBar.loadSavedMode());
        gd.addButton("Settings", e -> helpBar.openSettingsDialog()); // only helpbar setting for now, other settings could be added in the future
        gd.addPanel(helpBar.getPanel());
        final Button okButton = gd.getButtons()[0]; // ok button is the first one

        // 2- MODEL PATH
        gd.addMessage("Model", Sam3Dialogs.SECTION_HEADER_FONT);
        gd.addButton("Instructions to download SAM3 model", e ->Sam3Dialogs.addDownloadInstruction());
        Sam3Dialogs.addModelPathDialog(gd, PREF_LAST_DETECT_MODEL_KEY);
        Sam3Dialogs.attachFieldHint(gd, helpBar, (TextField) gd.getStringFields().lastElement(),
                "Path to the SAM3 model checkpoint (.pt file) used for detection.");

        // 4- PROMPT(S)
        // 4.1 first define FRAME RANGE SELECTOR (first / prompt / last)
        FrameRangeSelector frameSelector = new FrameRangeSelector(imp, nFrames, helpBar);
        frameSelector.setPromptFrame(imp.getCurrentSlice());

        // 4.2 create checkboxes and panels
        gd.addMessage("Prompt", Sam3Dialogs.SECTION_HEADER_FONT);

        // 4.2.1 text prompt
        gd.addCheckbox("Text prompt", true);
        Checkbox textCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel textPromptPanel = new Panel(new GridLayout(2, 1));
        textPromptPanel.add(new Label("Please enter a prompt"));
        TextField textPromptField = new TextField(10);
        textPromptPanel.add(textPromptField);

        Panel textEmptyCard = new Panel();

        Panel textCardHolder = new Panel(new CardLayout());
        textCardHolder.add(textEmptyCard, "EMPTY");
        textCardHolder.add(textPromptPanel, "CONTENT");
        CardLayout textCardLayout = (CardLayout) textCardHolder.getLayout();
        textCardLayout.show(textCardHolder, "CONTENT");

        helpBar.attachHelp(okButton, () ->
                (textCB.getState() && textPromptField.getText().trim().isEmpty())
                        ? new DialogHelpBar.Hint("Please enter a text prompt to enable prediction with text prompt.", DEFAULT_WARNING_COLOR)
                        : null);

        // 4.2.2 positive visual prompt(s)
        gd.addCheckbox("Positive visual prompt(s)", false);
        Checkbox posVisualPromptCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel posVisualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice posChoiceList = new Choice();
        posVisualPromptPanel.add(new Label("Select positive group(s):"));
        posVisualPromptPanel.add(posChoiceList);

        Panel posEmptyCard = new Panel();
        final Panel posCardHolder = new Panel(new CardLayout());
        posCardHolder.add(posEmptyCard, "EMPTY");
        posCardHolder.add(posVisualPromptPanel, "CONTENT");
        final CardLayout posCardLayout = (CardLayout) posCardHolder.getLayout();
        posCardLayout.show(posCardHolder, "EMPTY");

        // 4.2.3 negative visual prompt(s)
        gd.addCheckbox("Negative visual prompt(s)", false);
        Checkbox negVisualPromptCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel negVisualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice negChoiceList = new Choice();
        negVisualPromptPanel.add(new Label("Select negative group(s):"));
        negVisualPromptPanel.add(negChoiceList);

        Panel negEmptyCard = new Panel();
        final Panel negCardHolder = new Panel(new CardLayout());
        negCardHolder.add(negEmptyCard, "EMPTY");
        negCardHolder.add(negVisualPromptPanel, "CONTENT");
        final CardLayout negCardLayout = (CardLayout) negCardHolder.getLayout();
        negCardLayout.show(negCardHolder, "EMPTY");

        // 4.2.4 add all prompts panel in column
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

        // 4.3 Update ROIs, groupID lists and checkbox depending on whether the current prompt frame has usable ROI

        // 4.3.1 "remembered" selections of group IDs
        final int[] rememberedPositiveSelection = {NONE_SELECTION};
        final int[] rememberedNegativeSelection = {NONE_SELECTION};

        // guards the choice lists own item listeners while the selection is programmatically changed during a refresh
        final boolean[] updatingChoices = {false};

        Runnable updateRememberedPositiveSelection = () -> {
            if (updatingChoices[0]) return;
            if (posVisualPromptCB.getState()) {
                String sel = posChoiceList.getSelectedItem();
                if (Objects.equals(sel, ALL_ROIS_GROUPS_TXT)) {
                    rememberedPositiveSelection[0] = ALL_SELECTION;
                } else if (sel != null) {
                    rememberedPositiveSelection[0] = Integer.parseInt(sel.split(" ")[0]);
                }
            }
        };
        Runnable updateRememberedNegativeSelection = () -> {
            if (updatingChoices[0]) return;
            if (negVisualPromptCB.getState()) {
                String sel = negChoiceList.getSelectedItem();
                if (sel != null) {
                    rememberedNegativeSelection[0] = Integer.parseInt(sel.split(" ")[0]);
                }
            }
        };

        // listeners to remember last selected group : when new group is selected in list
        posChoiceList.addItemListener(e -> {updateRememberedPositiveSelection.run();});
        negChoiceList.addItemListener(e -> {updateRememberedNegativeSelection.run();});

        // listeners to remember last selected group :  when checkbox is checked, if nothing previously remembered
        posVisualPromptCB.addItemListener(e -> {
            if (rememberedPositiveSelection[0] == NONE_SELECTION ) updateRememberedPositiveSelection.run();
        });
        negVisualPromptCB.addItemListener(e -> {
            if (rememberedNegativeSelection[0] == NONE_SELECTION ) updateRememberedNegativeSelection.run();
        });

        // 4.3.2 adapt help text depending on whether ROI(s) are present on the current prompt frame
        final String requireRoiHint = "No usable ROI on the current prompt frame. \n" +
                "To enable visual prompts, add point/box ROI(s) to the ROI manager " +
                "or pick a different prompt frame.";
        helpBar.attachHelp(posVisualPromptCB, () ->
                currentRoisByFrame().getOrDefault(frameSelector.getPromptFrame(), Collections.emptyList()).isEmpty()
                        ? new DialogHelpBar.Hint( requireRoiHint, DEFAULT_INSTRUCTION_COLOR)
                        : new DialogHelpBar.Hint("Select which ROI group(s) on the current prompt frame to use as positive prompt(s).", Color.BLUE));
        helpBar.attachHelp(negVisualPromptCB, () ->
                currentRoisByFrame().getOrDefault(frameSelector.getPromptFrame(), Collections.emptyList()).isEmpty()
                        ? new DialogHelpBar.Hint(requireRoiHint, DEFAULT_INSTRUCTION_COLOR)
                        : new DialogHelpBar.Hint("Select which ROI group on the current prompt frame to use as a negative prompt.", Color.BLUE));


        // 4.3.3 update choice list depending on Roi on the prompt frame
        Runnable updateVisualPromptState = () -> {
            int frame = frameSelector.getPromptFrame();
            List<Roi> roisAtFrame = currentRoisByFrame().getOrDefault(frame, Collections.emptyList());
            TreeSet<Integer> groupsAtFrame = new TreeSet<>();
            for (Roi r : roisAtFrame) groupsAtFrame.add(r.getGroup());
            boolean anyGroupAtFrame = !groupsAtFrame.isEmpty();

            boolean posMismatch = refreshGroupChoiceList(posChoiceList, groupsAtFrame, rememberedPositiveSelection,
                    true, updatingChoices);
            boolean negMismatch = refreshGroupChoiceList(negChoiceList, groupsAtFrame, rememberedNegativeSelection,
                    false, updatingChoices);

            helpBar.clearInfo(ROI_AVAILABILITY_OWNER);
            if (!anyGroupAtFrame) {
                if (posVisualPromptCB.getState() || negVisualPromptCB.getState()) {
                    helpBar.showInfo(ROI_AVAILABILITY_OWNER, "Prompt frame " + frame
                            + " has no usable ROI - visual prompts are turned off.", DEFAULT_INSTRUCTION_COLOR);
                }
                posVisualPromptCB.setState(false);
                rememberedPositiveSelection[0] = NONE_SELECTION;
                negVisualPromptCB.setState(false);
                rememberedNegativeSelection[0] = NONE_SELECTION;
            } else { // il y a des ROIs
                if (posVisualPromptCB.getState()) { // veut un ROI positif
                    if (posMismatch) { // mais il y a mismatch -> visual prompt turned off + rememberedPositiveSelection reset
                        helpBar.showInfo(ROI_AVAILABILITY_OWNER, "The previously selected positive ROI group isn't on frame "
                                + frame + " - positive visual prompt are turned off", DEFAULT_NOTIFICATION_COLOR);
                        posVisualPromptCB.setState(false);
                        rememberedPositiveSelection[0] = NONE_SELECTION;
                    }
                }
                if (negMismatch && negVisualPromptCB.getState()) {
                    helpBar.showInfo(ROI_AVAILABILITY_OWNER, "The previously selected negative ROI group isn't on frame "
                            + frame + " - negative visual prompt are turned off", DEFAULT_NOTIFICATION_COLOR);
                    negVisualPromptCB.setState(false);
                    rememberedNegativeSelection[0] = NONE_SELECTION;
                }
            }

            // greyed out when there's nothing to choose
            Color groupColor = anyGroupAtFrame ? Color.BLACK : Color.GRAY;
            posVisualPromptCB.setForeground(groupColor);
            negVisualPromptCB.setForeground(groupColor);

            checkCBs(textCB, textCardLayout,  textCardHolder,
                    posVisualPromptCB, posCardLayout,  posCardHolder,
                    negVisualPromptCB, negCardLayout,  negCardHolder,
                    helpBar);
            gd.pack();
        };

        frameSelector.addPromptFrameListener(updateVisualPromptState);
        updateVisualPromptState.run(); // initial state, based on the default prompt frame set above

        // 4.4 update panels visibility
        gd.addDialogListener(new DialogListener() {
            @Override
            public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
                updateVisualPromptState.run();

               checkCBs(textCB, textCardLayout,  textCardHolder,
                       posVisualPromptCB, posCardLayout,  posCardHolder,
                       negVisualPromptCB, negCardLayout,  negCardHolder,
                       helpBar);

                gd.pack();
                return true;
            }
        });

        // 5 - RANGE SELECTOR
        gd.addMessage("Frame range", Sam3Dialogs.SECTION_HEADER_FONT);
        gd.addPanel(frameSelector.getPanel(), GridBagConstraints.CENTER, new Insets(5, 0, 0, 0));

        // 6- OUTPUTS
        gd.addMessage("Outputs", Sam3Dialogs.SECTION_HEADER_FONT);
        Sam3Dialogs.addOutputDialog(gd, DetectionUtils.DetectionMode.VIDEO);
        gd.addMessage("");
        // 7- PARAMETERS
        detectionParams = new Sam3ModelParameters();
        detectionParams.setNFrameToProcess(nFrames);
        Sam3Dialogs.addVideoBasicParameterDialog(gd, detectionParams, helpBar);
        gd.addButton("Advanced parameters...", e ->
                trackingModelPath = Sam3Dialogs.openVideoAdvancedParametersDialog(detectionParams, trackingModelPath));

        // 8- SHOW DIALOG
        // poll the RoiManager every 0.5 sec while the dialog is open
        final String[] lastRoiSignature = {roiPromptExtractor.signature(getRoiManager().getRoisAsArray(), axis)};
        Timer roiPollTimer = new Timer(500, e -> {
            String sig = roiPromptExtractor.signature(getRoiManager().getRoisAsArray(), axis);
            if (!sig.equals(lastRoiSignature[0])) {
                lastRoiSignature[0] = sig;
                updateVisualPromptState.run();
            }
        });
        roiPollTimer.start();
        helpBar.lockWidth(gd.getPreferredSize().width);
        try {
            gd.showDialog();
        } finally {
            roiPollTimer.stop();
        }
        finalRoiSnapshot = getRoiManager().getRoisAsArray();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // 9- get results
        // frame range
        chosenFirstFrame = frameSelector.getFirstFrame();
        chosenPromptFrame = frameSelector.getPromptFrame();
        chosenLastFrame = frameSelector.getLastFrame();

        // model path (tracking model path, if any, was already applied by the "Advanced parameters..." button)
        detectionModelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_DETECT_MODEL_KEY);

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
            Map<Integer, List<Roi>> finalRoisByFrame = roiPromptExtractor.groupRoisByFrame(finalRoiSnapshot, axis);
            List<Roi> roisAtChosenPromptFrame = finalRoisByFrame.getOrDefault(chosenPromptFrame, Collections.emptyList());
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

        // outputs
        outputOptions = Sam3Dialogs.getOutputAnswer(gd,DetectionUtils.DetectionMode.VIDEO);

        // detection and tracking parameters
        Sam3Dialogs.getVideoBasicParameters(gd, detectionParams);

    }

    /** Live view of all RoiManager ROIs grouped by frame (always re-reads the manager) */
    private Map<Integer, List<Roi>> currentRoisByFrame() {
        return roiPromptExtractor.groupRoisByFrame(getRoiManager().getRoisAsArray(), axis);
    }

    private void checkCBs(Checkbox textCB, CardLayout textCardLayout, Panel textCardHolder,
                          Checkbox posVisualPromptCB, CardLayout posCardLayout, Panel posCardHolder,
                          Checkbox negVisualPromptCB, CardLayout negCardLayout, Panel negCardHolder,
                          DialogHelpBar helpBar) {
        boolean textChecked = textCB.getState();
        boolean visualChecked = posVisualPromptCB.getState();
        boolean negativeChecked = negVisualPromptCB.getState();

        textCardLayout.show(textCardHolder, textChecked ? "CONTENT" : "EMPTY");
        posCardLayout.show(posCardHolder, visualChecked ? "CONTENT" : "EMPTY");
        negCardLayout.show(negCardHolder, negativeChecked ? "CONTENT" : "EMPTY");

        // if no option selected (no box checked) : warning
        if (!textChecked && !visualChecked) {
            helpBar.warn(NO_OPTION_OWNER, "Please select at least one positive prompt option (either text or visual) to perform prediction.");
        } else {
            helpBar.clearWarning(NO_OPTION_OWNER);
        }
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

    /**
     * Rebuilds one group {@link Choice} list (positive or negative) for the ROI groups present
     * on the current prompt frame, and re-selects the user's remembered choice if it's still
     * available there.
     * <p>
     *
     * @param allowAll whether an "ALL_ROIS_GROUPS_TXT" entry should be offered when there's more
     *                 than one group (true for the positive list, false for the negative one)
     * @return {@code true} if the user had a real remembered choice (a specific group, or ALL)
     *         that is no longer available on this frame
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
        IJ.log("\nSAM3 pcs on macro");
        String options = Macro.getOptions();

        // model path(s)
        detectionModelPath = Macro.getValue(options, "model_path", null);
        if (detectionModelPath == null || detectionModelPath.trim().isEmpty()) {
            IJ.log("No model path. Closing plug-in.\n");
            IJ.error("No model path specified.");
            return;
        }
        trackingModelPath = Macro.getValue(options, "tracking_model_path", null);
        if (trackingModelPath != null && trackingModelPath.trim().isEmpty()) trackingModelPath = null;

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

        // frame range (1-indexed), same defaults as the dialog: prompt = current slice,
        // first = prompt (non-bidirectional), last = last frame of the video
        chosenPromptFrame = clampFrame(Integer.parseInt(Macro.getValue(options, "prompt_frame", String.valueOf(imp.getCurrentSlice()))));
        chosenLastFrame = Math.max(chosenPromptFrame, clampFrame(Integer.parseInt(Macro.getValue(options, "last_frame", String.valueOf(nFrames)))));
        chosenFirstFrame = Math.min(chosenPromptFrame, clampFrame(Integer.parseInt(Macro.getValue(options, "first_frame", String.valueOf(chosenPromptFrame)))));

        // detection/tracking parameters
        detectionParams = new Sam3ModelParameters();
        double confidenceThreshold = Double.parseDouble(Macro.getValue(options, "confidence", String.valueOf(detectionParams.getConfidenceThreshold())));
        if (confidenceThreshold < 0 || confidenceThreshold > 1) confidenceThreshold = detectionParams.getConfidenceThreshold();
        detectionParams.setConfidenceThreshold(confidenceThreshold);

        double maskThreshold = Double.parseDouble(Macro.getValue(options, "mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold())));
        detectionParams.setMaskScoreThreshold(maskThreshold);

        int maxSideLengthDetect = Integer.parseInt(Macro.getValue(options, "max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect())));
        if (maxSideLengthDetect <= 0) maxSideLengthDetect = detectionParams.getMaxSideLengthDetect();
        detectionParams.setMaxSideLengthDetect(maxSideLengthDetect);

        int maxSideLengthTrack = Integer.parseInt(Macro.getValue(options, "max_side_length_track", String.valueOf(detectionParams.getMaxSideLengthTrack())));
        if (maxSideLengthTrack <= 0) maxSideLengthTrack = detectionParams.getMaxSideLengthTrack();
        detectionParams.setMaxSideLengthTrack(maxSideLengthTrack);

        int nFramesBtwDetec = Integer.parseInt(Macro.getValue(options, "frames_between_detections", String.valueOf(detectionParams.getNFrameBtwDetections())));
        if (nFramesBtwDetec <= 0) nFramesBtwDetec = chosenLastFrame - chosenFirstFrame + 1;
        detectionParams.setNFrameBtwDetections(nFramesBtwDetec);

        double trackingScoreThreshold = Double.parseDouble(Macro.getValue(options, "tracking_score_threshold", String.valueOf(detectionParams.getTrackingScoreThreshold())));
        detectionParams.setTrackingScoreThreshold(trackingScoreThreshold);

        int removeAfterNMissed = Integer.parseInt(Macro.getValue(options, "remove_after_n_missed", String.valueOf(detectionParams.getRemoveAfterNMissed())));
        detectionParams.setRemoveAfterNMissed(removeAfterNMissed);

        double boxIouThreshold = Double.parseDouble(Macro.getValue(options, "box_iou_threshold", String.valueOf(detectionParams.getTrackingBoxIouThreshold())));
        detectionParams.setTrackingBoxIouThreshold(boxIouThreshold);

        // outputs
        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false"));
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false"));
        outputOptions.deletePreviousRoi = false;
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false"));
    }

    private int clampFrame(int value) {
        return Math.max(1, Math.min(nFrames, value));
    }

    private void recordInMacro() {
        Recorder.setCommand("SAM3 Video PCS");
        Recorder.recordOption("model_path", detectionModelPath);
        if (trackingModelPath != null && !trackingModelPath.trim().isEmpty()) {
            Recorder.recordOption("tracking_model_path", trackingModelPath);
        }

        if (textPromptUsed) Recorder.recordOption("text_prompt", textPrompt);

        if (visualPositivePromptUsed) {
            String positiveGroupIds = positiveGroups.stream().map(String::valueOf).collect(Collectors.joining(","));
            Recorder.recordOption("positive_group_ids", positiveGroupIds);
        }
        if (negativePromptUsed) Recorder.recordOption("negative_group_id", String.valueOf(negativeGroup));

        Recorder.recordOption("first_frame", String.valueOf(chosenFirstFrame));
        Recorder.recordOption("prompt_frame", String.valueOf(chosenPromptFrame));
        Recorder.recordOption("last_frame", String.valueOf(chosenLastFrame));

        Recorder.recordOption("confidence", String.valueOf(detectionParams.getConfidenceThreshold()));
        Recorder.recordOption("mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold()));
        Recorder.recordOption("max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect()));
        Recorder.recordOption("max_side_length_track", String.valueOf(detectionParams.getMaxSideLengthTrack()));
        Recorder.recordOption("frames_between_detections", String.valueOf(detectionParams.getNFrameBtwDetections()));
        Recorder.recordOption("tracking_score_threshold", String.valueOf(detectionParams.getTrackingScoreThreshold()));
        Recorder.recordOption("remove_after_n_missed", String.valueOf(detectionParams.getRemoveAfterNMissed()));
        Recorder.recordOption("box_iou_threshold", String.valueOf(detectionParams.getTrackingBoxIouThreshold()));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_box_rois", String.valueOf(true));
        if (outputOptions.addToRoiManagerShapes) Recorder.recordOption("add_shape_rois", String.valueOf(true));
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask", String.valueOf(true));
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
