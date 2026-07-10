package fr.curie.miclearning.apposeplugin;

import fr.curie.miclearning.tools.detection.Detection3dUtils;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.MultiFrameDataManager;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
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

import static fr.curie.miclearning.apposeplugin.Sam3Dialogs.ALL_ROIS_GROUPS_TXT;
import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ plugin: SAM3 promptable-concept-segmentation on video.
 */
public class Sam3VideoPcs_Plugin implements PlugIn {
    protected ImagePlus imp;
    int nFrames; // number of frames in the video
    int axis; // 3 : Z axis - 4 : Time axis

    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();
    private String modelPath;
    private String textPrompt = "visual";
    private boolean textPromptUsed;
    private boolean visualPositivePromptUsed;
    private boolean negativePromptUsed;
    private List<Integer> positiveGroups;
    private int negativeGroup;
    private Sam3Parameters detectionParams;
    private DetectionUtils.OutputOptions outputOptions;

    int posStartFrame =0; // starting from index 0
    int posEndFrame; // starting from index 0
    boolean bidirectional = false;

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-pcs-video-m.py";

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
        String[] positiveGroupChoices = null;
        String[] negativeGroupChoices = null;

        //
        if (!roiSelection.hasUsableRois()) {
            visualPositivePromptUsed = false;
            negativePromptUsed = false;
            firstUnusedRoiId = 1;
            imp.setPosition(1);
        } else {
            imp.setPosition(roiSelection.getFirstFramePosition());
            roiManager.runCommand("Show All");
            firstUnusedRoiId = roiSelection.getFirstUnusedGroupId();

            // create 2 lists of group that will be displayed in generic dialog
            TreeSet<Integer> uniqueGroups = roiSelection.getUniqueGroups();
            if (uniqueGroups.size() > 1) {
                positiveGroupChoices = new String[uniqueGroups.size() + 1];
                negativeGroupChoices = new String[uniqueGroups.size()];
                positiveGroupChoices[0] = ALL_ROIS_GROUPS_TXT;
                int j = 0;
                for (int groupId : uniqueGroups) {
                    String label = RoiPromptExtractor.formatGroupLabel(groupId);
                    negativeGroupChoices[j] = label;
                    positiveGroupChoices[j + 1] = label;
                    j++;
                }
            } else {
                positiveGroupChoices = new String[]{RoiPromptExtractor.formatGroupLabel(uniqueGroups.first())};
                negativeGroupChoices = new String[]{RoiPromptExtractor.formatGroupLabel(uniqueGroups.first())};
            }
        }

        // --- 2. retrieve parameters (model path, prompts, output options) ---
        if (Macro.getOptions() != null) {
            parseMacro();
        } else {
            askUser(roiSelection.getGroupCount(), positiveGroupChoices, negativeGroupChoices);
        }

        if (outputOptions == null || modelPath == null || detectionParams == null) {
            return;
        }

        long startTime = System.nanoTime();

        // --- 3. Prepare prompts, define config ---
        // text prompt (even if just "visual"
        Map<String, Integer> classIdMap = new HashMap<>();
        classIdMap.put(textPrompt, firstUnusedRoiId);

        // visual prompts, positive and negative
        RoiPromptExtractor.PromptRois promptRois = roiPromptExtractor.buildPromptRois(
                roiSelection.getRoisAtFirstFrame(),
                visualPositivePromptUsed ? positiveGroups : java.util.Collections.emptyList(),
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


        // first and last frames to segment
        int posStartFrame = (visualPositivePromptUsed || negativePromptUsed)
                ? roiSelection.getFirstFramePosition() - 1
                : 0;
        if (!(visualPositivePromptUsed || negativePromptUsed)) {
            imp.setPosition(1);
        }

        int posEndFrame = Math.min(nFrames - 1, posStartFrame + detectionParams.getNFrame() - 1);
        detectionParams.setNFrame(posEndFrame - posStartFrame + 1);

        // prepare model and run configuration
        Sam3RunConfig config;
        try {
            config = new Sam3RunConfig.Builder()
                    .modelPath(modelPath)
                    .textPrompt(textPrompt, textPromptUsed)
                    .visualPrompts(promptRois.getPositive(), promptRois.getNegative(),
                            visualPositivePromptUsed, negativePromptUsed)
                    .frameRange(posStartFrame, posEndFrame)
                    .detectionParams(detectionParams)
                    .outputOptions(outputOptions)
                    .classIdMap(classIdMap)
                    .build();
        } catch (IllegalStateException e) {
            IJ.error("Invalid configuration", e.getMessage());
            return;
        }

        //prepare output
        MultiFrameDataManager mfdManager = new MultiFrameDataManager(posStartFrame, posEndFrame);

        recordInMacro();
        IJ.log("\n   --- Starting SAM3 Promptable Concept Segmentation on video --- ");
        printParameters(config);

        // --- 4. Run ---
        runSam3(config, mfdManager, startTime);
    }


    private void runSam3(Sam3RunConfig config, MultiFrameDataManager mfdManager, long startTime) {
        // create queue and thread to process results
        BlockingQueue<Map<String, Object>> resultsQueue = new LinkedBlockingQueue<>();
        ThreadFactory consumerThreadFactory = r -> {
            Thread t = new Thread(r, "sam3-result-consumer");
            t.setDaemon(true);
            return t;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(consumerThreadFactory);

        try (Sam3PythonRunner runner = new Sam3PythonRunner(SCRIPT_PATH, ENV_FILE_PATH)) {
            runner.initialize();

            DetectionResultConsumer consumer = new DetectionResultConsumer(
                    resultsQueue, mfdManager, config.getClassIdMap(), config.getTextPrompt(), imp);
            Future<Void> consumerResult = executor.submit(consumer);

            IJ.log("Executing python script...");
            runner.runBlocking(config, imp, resultsQueue);

            // Wait for the consumer to finish draining the queue before generating outputs.
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

    private void askUser(int groupNumber, String[] positiveGroupChoices, String[] negativeGroupChoices) {
        GenericDialog gd = new GenericDialog("SAM3 Promptable Concept Segmentation on video");

        // 1- dynamic HELP SECTION (at the top ? or put at bottom ?)
        Panel helpPanel = new Panel(new FlowLayout(FlowLayout.LEFT));
        Label helpLabel = new Label(" "); // nothing for now
        helpLabel.setForeground(Color.BLUE);
        helpPanel.add(helpLabel);
        gd.addPanel(helpPanel);

        final Button okButton = gd.getButtons()[0]; // ok button is the first one

        // 2- INSTRUCTIONS to download model
        ActionListener modelInstructionAction = e -> {
            Sam3Dialogs.addDownloadInstruction();
        };
        gd.addButton("Instructions to download SAM3 model", modelInstructionAction);
        gd.addMessage("");

        // 3- MODEL PATH
        Sam3Dialogs.addModelPathDialog(gd, PREF_LAST_MODEL_KEY);

        // 4-PROMPT(S)
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

        okButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if(textCB.getState() && textPromptField.getText().trim().isEmpty()) updateHelpLabelNoText(helpLabel, gd);
            }
            @Override
            public void mouseExited(MouseEvent e) {
                updateHelpLabelDefault(helpLabel, gd);
            }
        });

        // 4.1.2 positive visual prompt(s)
        gd.addCheckbox("Positive visual prompt(s)", false);
        Checkbox posVisualPromptCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel posVisualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice posChoiceList = new Choice();
        // 4.2 fill lists for visuals prompts choice if at least one ROI
        if (groupNumber > 0) {
            // positive visual prompts panel
            // add list of groups id to choose from
            for (String choice : positiveGroupChoices) posChoiceList.add(choice);
            posVisualPromptPanel.add(new Label("Select positive group(s):"));
            posVisualPromptPanel.add(posChoiceList);
            gd.addPanel(posVisualPromptPanel);
            posVisualPromptPanel.setVisible(false);
        }

        // 4.1.3 negative visual prompt(s)
        gd.addCheckbox("Negative visual prompt(s)", false);
        Checkbox negVisualPromptCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel negVisualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice negChoiceList = new Choice();

        if (groupNumber > 0) {
            // negative visual prompts panel
            for (String choice : negativeGroupChoices) negChoiceList.add(choice);
            negVisualPromptPanel.add(new Label("Select negative group(s):"));
            negVisualPromptPanel.add(negChoiceList);
            gd.addPanel(negVisualPromptPanel);
            negVisualPromptPanel.setVisible(false);
        }

        // 4.3 if no ROI selected (only prompt allowed = text prompt), block the checkboxes + print help message at top
        if (groupNumber == 0) {
            // text prompt
            textCB.setForeground(Color.GRAY);
            textCB.addItemListener(e -> {
                textCB.setState(true); // revert if user try to uncheck
                updateHelpLabelDefault(helpLabel, gd);
            });
            textPromptPanel.setVisible(true);

            // positive visual prompt
            posVisualPromptCB.setForeground(Color.GRAY);
            posVisualPromptCB.addItemListener(e -> {
                posVisualPromptCB.setState(false); // revert if user tries to check
                updateHelpLabelRequireRoi(helpLabel, gd);
            });
            posVisualPromptPanel.setVisible(false);

            // negative visual prompt
            negVisualPromptCB.setForeground(Color.GRAY);
            negVisualPromptCB.addItemListener(e -> {
                negVisualPromptCB.setState(false); // revert if user tries to check
                updateHelpLabelRequireRoi(helpLabel, gd);
            });
            negVisualPromptPanel.setVisible(false);

            // hover logic: update the label at the top if mouse
            posVisualPromptCB.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    updateHelpLabelRequireRoi(helpLabel, gd);
                }
                @Override
                public void mouseExited(MouseEvent e) {
                    updateHelpLabelDefault(helpLabel, gd);
                }
            });

            negVisualPromptCB.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    updateHelpLabelRequireRoi(helpLabel, gd);
                }
                @Override
                public void mouseExited(MouseEvent e) {
                    updateHelpLabelDefault(helpLabel, gd);
                }
            });
        }

        // 4.4 update panels visibility

        gd.addDialogListener(new DialogListener() {
            @Override
            public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
                boolean textChecked = textCB.getState();
                boolean visualChecked = posVisualPromptCB.getState();
                boolean negativeChecked = negVisualPromptCB.getState();
                boolean noText = textPromptField.getText().trim().isEmpty();

                // sections visible if boxes checked
                if (groupNumber > 0) {
                    textPromptPanel.setVisible(textChecked);
                    posVisualPromptPanel.setVisible(visualChecked);
                    negVisualPromptPanel.setVisible(negativeChecked);
                }

                // if no option selected (no box checked) : OK button is unabled -> doesn't work !!
                if (!textChecked && !visualChecked) {
                    okButton.setEnabled(false); // no effect
                    updateHelpLabelNoOption(helpLabel, gd);
                } else {
                    okButton.setEnabled(true);
                    updateHelpLabelDefault(helpLabel, gd);
                }

                return true;
            }
        });

        // 5- PARAMETERS
        gd.addMessage("__________");
        detectionParams = new Sam3Parameters();
        detectionParams.setNFrame(nFrames);
        Sam3Dialogs.addParameterDialog(gd, detectionParams, DetectionUtils.DetectionMode.VIDEO);

        // 6- OUTPUTS
        gd.addMessage("__________");
        Sam3Dialogs.addOutputDialogVideo(gd);

        // 7- SHOW DIALOG
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // 8- get results
        // model path
        modelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_MODEL_KEY);

        // prompt
        // text prompt
        textPromptUsed = textCB.getState();
        gd.getNextBoolean();
        textPrompt = textPromptField.getText();
        if(textPrompt == null || textPrompt.trim().isEmpty()) textPromptUsed = false;
        if (!textPromptUsed) textPrompt="visual";
        // visual positive prompt
        visualPositivePromptUsed = posVisualPromptCB.getState() && groupNumber > 0;
        gd.getNextBoolean();
        String selectedPositiveGroup = posChoiceList.getSelectedItem();

        // visual negative prompt
        negativePromptUsed = negVisualPromptCB.getState() && groupNumber > 0;
        gd.getNextBoolean();
        if (negativePromptUsed) {
            negativeGroup = Integer.parseInt(negChoiceList.getSelectedItem().split(" ")[0]);
            if (negativeGroup == -1) negativePromptUsed = false;
        }

        if (visualPositivePromptUsed) {
            positiveGroups = new ArrayList<>();
            if (Objects.equals(selectedPositiveGroup, ALL_ROIS_GROUPS_TXT)){ // if all ROI group are prompts
                for (String choice : positiveGroupChoices) {
                    if (Objects.equals(choice, ALL_ROIS_GROUPS_TXT)) continue;
                    int id = Integer.parseInt(choice.split(" ")[0]);
                    if (!negativePromptUsed || id != negativeGroup) {
                        positiveGroups.add(id);
                    } else {
                        IJ.log("group " + id + " used as negative prompt");
                    }
                }
            } else { // if only one group is prompt
                int id = Integer.parseInt(selectedPositiveGroup.split(" ")[0]);
                if (negativePromptUsed && id == negativeGroup){
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
        outputOptions = Sam3Dialogs.getOutputAnswerVideo(gd);
    }

    private static void updateHelpLabelDefault(Label helpLabel, GenericDialog gd) {
        helpLabel.setText(" ");
        helpLabel.setForeground(Color.BLUE);
        gd.pack();
    }

    private static void updateHelpLabelRequireRoi(Label helpLabel, GenericDialog gd) {
        helpLabel.setText("Please add point and box ROI(s) to the ROI manager to enable visual prompts.");
        helpLabel.setForeground(Color.MAGENTA);
        gd.pack();
    }

    private static void updateHelpLabelNoOption(Label helpLabel, GenericDialog gd) {
        helpLabel.setText("Please select at least one option to perform prediction.");
        helpLabel.setForeground(Color.RED);
        gd.pack();
    }

    private static void updateHelpLabelNoText(Label helpLabel, GenericDialog gd) {
        helpLabel.setText("Please enter a text prompt to enable prediction with text prompt.");
        helpLabel.setForeground(Color.RED);
        gd.pack();
    }



    private void parseMacro() {

    }

    private void recordInMacro() {

    }

    private void printParameters(Sam3RunConfig config) {
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("model: " + config.getModelPath());
        IJ.log("number of frames to process: " + config.getFrameCount()
                + " (frame " + (config.getStartFrame() + 1) + " to " + (config.getEndFrame() + 1) + ")");
        IJ.log("detection every " + config.getDetectionParams().getNFrameBtwDetections() + " frames");
        if (config.isTextPromptUsed()) IJ.log("text prompt: " + config.getTextPrompt().toUpperCase());
        if (config.isVisualPositivePromptUsed()) IJ.log("visual prompts: " + config.getPositiveRois().size() + " ROI(s) as positive prompt(s)");
        if (config.isNegativePromptUsed()) IJ.log("visual prompts: " + config.getNegativeRois().size() + " ROI(s) as negative prompt(s)");
        IJ.log("----------------------");
    }

}
