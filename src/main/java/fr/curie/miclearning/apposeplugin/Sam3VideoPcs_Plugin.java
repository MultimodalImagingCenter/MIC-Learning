package fr.curie.miclearning.apposeplugin;

import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.tools.detection.*;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.*;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static fr.curie.miclearning.apposeplugin.Sam3Dialogs.*;
import static fr.curie.miclearning.tools.appose.ApposeUtils.getResourceAsString;
import static fr.curie.miclearning.tools.appose.ApposeUtils.video2ShmImg;
import static fr.curie.miclearning.tools.detection.Detection3dUtils.generate3dOutputs;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class Sam3VideoPcs_Plugin implements PlugIn {
    protected static ImagePlus imp;
    int nFrames; // number of frames in the video

    private DetectionUtils.OutputOptions outputOptions;
    private String modelPath;
    private Sam3Parameters detectionParams;

    private MultiFrameDataManager mfdManager; // registry of all detections by frames
    // actually to useful for now, may be if we want to extract stats, or add filter to remove 1 slice only objects...

    private Map<String, Integer> classIdMap; // list to map class name (string) to roi group id (must be integer <256)
    // as only one class/concept, and group id is not relevant, it is only {prompt:1}

    // PROMPTS : only 1 concept/class for the whole video, can be described with mix of text and visual (positive and negative) prompts
    // text prompt (written by user in first generic dialog)
    private boolean textPromptUsed;
    private String textPrompt = "visual";

    // visual prompts (based on selected ROIs in ROI manager)
    private Set<Integer> uniqueGroups; // list of Roi group ID in manager
    private int groupNumber; // Number of ROI groups in manager
    private int roiNumber; // Number of selected rectangle/points ROIs
    // positive prompts
    private boolean visualPositivePromptUsed; // if visual positive prompts are used
    private String[] positiveGroupChoices; // list of ROI to display in the genericDialog for the user to choose
    private List<Integer> positiveGroups; // list of positive groups ID
    private List<double[]> positiveRois; // list of Roi to be used as positive prompts
    // negative prompts
    private boolean negativePromptUsed; // if visual negative prompts are used
    private String[] negativeGroupChoices; // list of ROI to display in the genericDialog for the user to choose
    private int negativeGroup; // id of the negative group selected (if any)
    private List<double[]> negativeRois; // list of Roi to be used as negative prompts

    private static final Map<String, Object> END_SIGNAL = new HashMap<>();

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-pcs-video-m.py";

    @Override
    public void run(String s) {
        // --- 1. get image and geom Rois ---
        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        nFrames = Math.max(imp.getNSlices(), imp.getNFrames()); // je considère que les frames peuvent être définies de différentes manières, à revoir ?
        if (!imp.isRGB()) {
            IJ.error("Only RGB images are supported.");
            return;
        }

        // 1.2 get selected ROIs list
        RoiManager roiManager = getRoiManager();
        Roi[] selectedRoiList = roiManager.getSelectedRoisAsArray();
        List<Roi> allRoiList = new ArrayList<>();
        int firstUnusedRoiId=0;
        if (selectedRoiList.length == 0) {
            visualPositivePromptUsed = false;
            firstUnusedRoiId = 1;
        } else {
            // 1.3 get ROI groups IDs list
            uniqueGroups = new TreeSet<>();
            for (Roi roi : selectedRoiList) {
                // check that Roi is a box or point
                if (roi.getType() == 0 || roi.getType() == 10) {
                    allRoiList.add(roi);
                    uniqueGroups.add(roi.getGroup());
                } else {
                    IJ.log("Only box and point ROIs are used as prompts. ROI " + roi.getName() + " will be ignored");
                }
            }
            groupNumber = uniqueGroups.size();
            roiNumber = allRoiList.size();

            if (groupNumber == 0){
                IJ.error("No box or point ROIs were found");
                visualPositivePromptUsed = false;
                firstUnusedRoiId = 1;
            } else if (groupNumber > 1) {
                // create 2 lists of group that will be displayed in generic dialog
                positiveGroupChoices = new String[uniqueGroups.size() + 1]; // names that will be displayed in generic dialog
                negativeGroupChoices = new String[uniqueGroups.size()];
                positiveGroupChoices[0] = ALL_ROIS_GROUPS_TXT;
                int j = 0;
                for (int groupId :  uniqueGroups) {
                    String name = Roi.getGroupName(groupId);
                    if (name == null) name = String.valueOf(groupId);
                    else name = name + " (" +  groupId + ")";
                    negativeGroupChoices[j] = name;
                    positiveGroupChoices[j+1] = name;
                    j++;
                }
                for (int roiID = 1; roiID <= 255; roiID++) {
                    if (!uniqueGroups.contains(roiID)) {
                        firstUnusedRoiId = roiID;
                        break;
                    }
                }
            } else { // if only 1 group
                positiveGroupChoices = new String[uniqueGroups.size()];
                negativeGroupChoices = new String[uniqueGroups.size()];
                int groupId = uniqueGroups.iterator().next();
                String name = Roi.getGroupName(groupId);
                if (name == null) name = String.valueOf(groupId);
                else name = name + " (" +  groupId + ")";
                negativeGroupChoices[0] = name;
                positiveGroupChoices[0] = name;
                firstUnusedRoiId = (groupId % 255) + 1;
            }
        }

        // --- 2. retrieve parameters (model path, groups, output options) ---
        if (Macro.getOptions() != null) {
            parseMacro();
        } else {
            askUser();
        }

        if (outputOptions == null  || modelPath == null || detectionParams == null) {
            return;
        }

        long startTime = System.nanoTime();

        if (!textPromptUsed && !visualPositivePromptUsed) {
            IJ.log("Please enter a prompt, either text or ROI.");
            return;
        } else {
            classIdMap = new HashMap<>();
            classIdMap.put(textPrompt,firstUnusedRoiId);
        }



        // 2.2 Prepare input
        // text prompt
        textPrompt = classIdMap.keySet().toArray()[0].toString();
        textPromptUsed = textPrompt != null && !textPrompt.trim().isEmpty();
        if (!textPromptUsed) textPrompt = "visual";

        // geom prompt
        // sort positive and negative ROIs
        if (visualPositivePromptUsed) {
            positiveRois = new ArrayList<>(); // positive_rois format: [[x,y,w,h], [x,y], ...] (absolute values)
            negativeRois = new ArrayList<>(); // negative_rois format: [[x,y,w,h], [x,y], ...] (absolute values)

            for (Roi roi : allRoiList) {
                int groupId = roi.getGroup();
                double[] coord;
                if (roi.getType() == Roi.RECTANGLE) { // if roi is box
                    Rectangle rect = roi.getBounds();
                    coord = new double[]{rect.x, rect.y, rect.width, rect.height};
                    if (positiveGroups.contains(groupId)) {
                        positiveRois.add(coord);
                    }
                    else if (negativePromptUsed && groupId == negativeGroup) {
                        negativeRois.add(coord);
                    }
                } else if (roi.getType() == Roi.POINT) { // if point (or list of points)
                    roi.getContainedPoints();
                    Point[] points = roi.getContainedPoints();  // get list of points in ROI
                    // add every point of the group to the input
                    for (Point point : points) {
                        coord = new double[]{point.getX(), point.getY()};
                        if (positiveGroups.contains(groupId)) {
                            positiveRois.add(coord);
                        }
                        else if (negativePromptUsed && groupId == negativeGroup) {
                            negativeRois.add(coord);
                        }
                    }
                } else {
                    IJ.log("error with Roi type : " + roi);
                }

                if (positiveRois.isEmpty()) {
                    IJ.log("No positive ROIs found to process.");
                    visualPositivePromptUsed = false;
                }
            }
        }

        if (!textPromptUsed && !visualPositivePromptUsed) {
            IJ.log("ERROR : No valid prompt were found");
            IJ.error("No valid prompt were found");
            return;
        }

        //prepare output
        mfdManager = new MultiFrameDataManager(detectionParams.getMaxFrame());

        recordInMacro();
        IJ.log("\n   --- Starting SAM3 Promptable Concept Segmentation on video --- ");
        printParameters();

        // --- 2. load script and create env ---
        // 2.1 load python script
        String script = getResourceAsString(SCRIPT_PATH);
        if (script == null) {
            IJ.error("Unable to load script");
            return;
        }

        IJ.log("Python script loaded");

        // 2.2 create environment
        // load toml file
        String envTomlContent = getResourceAsString(ENV_FILE_PATH);
        if (envTomlContent == null) {
            IJ.error("Unable to load environment file");
            return;
        }

        // build env
        try {
            Environment env = Appose.pixi()
                    .content(envTomlContent)
                    //.logDebug()
                    .build();

            IJ.log("Python environment built");

            // --- 3. prediction ---
            // 3.1 copy video in shared memory
            try (ShmImg<?> sharedVid = video2ShmImg(imp)) {

                // 3.2 create python service in env
                try (Service python = env.python()) {

                    // 3.3 store video and prompt into a map of inputs to the Python script.
                    final Map<String, Object> inputs = new HashMap<>();

                    inputs.put("video_input", NDArrays.asNDArray(sharedVid));
                    inputs.put("detection_model_path", modelPath);
                    inputs.put("tracking_model_path", null);

                    inputs.put("detectionScoreThreshold", detectionParams.getConfidenceThreshold());
                    inputs.put("trackingScoreThreshold", detectionParams.getTrackingScoreThreshold());
                    inputs.put("maskThreshold", detectionParams.getMaskScoreThreshold());
                    inputs.put("detectEveryNFrames", detectionParams.getNFrameBtwDetections());
                    inputs.put("removeAfterNMissed", detectionParams.getRemoveAfterNMissed());
                    inputs.put("maxSideLengthDetect", detectionParams.getMaxSideLengthDetect());
                    inputs.put("maxSideLengthTrack", detectionParams.getMaxSideLengthTrack());
                    inputs.put("max_frame_number", detectionParams.getMaxFrame());

                    inputs.put("text_prompt", textPrompt);
                    inputs.put("positive_rois", positiveRois);
                    inputs.put("negative_rois", negativeRois);

                    // 3.4 Execute script by launching task
                    Service.Task task = python.task(script, inputs);

                    // follow task execution and send results to resultsQueue frame by frame
                    BlockingQueue<Map<String, Object>> resultsQueue = new LinkedBlockingQueue<>(); // thread-safe queue for results
                    task.listen(event -> {
                        switch (event.responseType) {
                            case LAUNCH:
                                IJ.log("Task launched");
                                break;
                            case UPDATE:
                                if (event.message != null && !event.message.isEmpty()) {
                                    IJ.log("   " + event.message);

                                    Map<String, Object> info = event.info;
                                    if(info != null) {
                                        resultsQueue.offer(info); // immediately send result of the frame to the queue
                                    }
                                }
                                break;
                            case CRASH:
                                IJ.error("Python task crashed : ", task.error);
                                IJ.log("ERROR while running python script");
                                return;
                            case FAILURE:
                                IJ.error("Python task failed with error: ", task.error);
                                IJ.log("ERROR while running python script");
                                return;
                            case COMPLETION:
                                System.out.println("Task completed successfully");
                                resultsQueue.offer(END_SIGNAL); // send signal to stop processing
                                break;
                            case CANCELATION:
                                System.out.println("Task canceled");
                                break;
                            default:
                                //System.out.println("task response type : " + event.responseType);
                                break;
                        }
                    });

                    // --- 4. get  results ---
                    // thread dedicated to result processing (in parallel of python prediction)
                    new Thread(() -> {
                        try {
                            while (true) {
                                Map<String, Object> info = resultsQueue.take();

                                // Check if this is the "End of Stream" signal
                                // meaning that all results have been sent
                                if (info == END_SIGNAL) {
                                    IJ.log("All frames processed.");
                                    break; // Break the loop
                                }

                                // if no end signal, extract results for the frame
                                int numResults = (int) info.get("n_results");
                                int frameIdx = (int) info.get("frame_idx");

                                if (numResults == 0){
                                    // add something to manager to generate empty masks ?
                                    continue;
                                }

                                NDArray output_boxes = (NDArray) info.get("boxes");
                                NDArray output_masks = (NDArray) info.get("masks");
                                NDArray output_scores = (NDArray) info.get("scores");
                                NDArray output_ids = (NDArray) info.get("object_ids");

                                if (output_boxes == null || output_masks == null || output_scores == null || output_ids == null) {
                                    IJ.log("Missing output arrays (boxes, masks, scores or ids) from Python.");
                                    return;
                                }

                                // --- 5. process results --
                                // 5.1 extract bounding boxes
                                // Initialize the 2D array [Number of Boxes][4 Coordinates]
                                double[][] boxesArray = new double[numResults][4];
                                // extract bounding boxes coordinates
                                DoubleBuffer buf_boxes = output_boxes.buffer().asDoubleBuffer();
                                buf_boxes.rewind();
                                for (int i = 0; i < numResults; i++) {
                                    buf_boxes.get(boxesArray[i]);
                                }

                                // 5.2 Extract masks
                                long[] shape = output_masks.shape().toLongArray();
                                int height;
                                int width;
                                if (shape.length == 2) {
                                    height = (int) shape[0];
                                    width = (int) shape[1];
                                } else {
                                    height = (int) shape[1];
                                    width = (int) shape[2];
                                }

                                byte[][][] masksArray = new byte[numResults][height][width];

                                ByteBuffer buf_masks = output_masks.buffer();
                                buf_masks.rewind();
                                for (int i = 0; i < numResults; i++) {
                                    for (int y = 0; y < height; y++) {
                                        buf_masks.get(masksArray[i][y]);
                                    }
                                }

                                // 5.3 Extract probabilities
                                double[] probaArray = new double[numResults];
                                DoubleBuffer buf_scores = output_scores.buffer().asDoubleBuffer();
                                buf_scores.rewind();
                                buf_scores.get(probaArray);

                                // 5.4 Extract object ids
                                int[] idsArray = new int[numResults];
                                IntBuffer buf_ids = output_ids.buffer().asIntBuffer();
                                buf_ids.rewind();
                                buf_ids.get(idsArray);

                                // 5.5 convert results to DetectedObjects
                                List<String> classNames = new ArrayList<>();
                                List<Double> probabilities = new ArrayList<>();
                                List<BoundingBox> boundingBoxes = new ArrayList<>();

                                for (int i = 0; i < numResults; i++) {
                                    classNames.add(textPrompt);
                                    double[] coord = boxesArray[i];
                                    MaskByte mask = new MaskByte(coord[0], coord[1], coord[2], coord[3], masksArray[i], true);
                                    boundingBoxes.add(mask);
                                    probabilities.add(probaArray[i]);
                                }

                                DetectedObjects detectedObjects = new DetectedObjects(classNames, probabilities, boundingBoxes);
                                //IJ.log(" --- Prediction received for frame "+ frameIdx +"- total number of detection= " + numResults);

                                // --- 6. process detections ---
                                // 6.1 create Roi from bounding boxes and masks
                                List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detectedObjects, classIdMap);
                                if (processedDetections.isEmpty()) {
                                    IJ.log(" --- No valid detections were processed for frame " + frameIdx +1);
                                    return;
                                }

                                // 6.2 aggregate detection by id and by frame
                                for (int i = 0; i < numResults; i++) {
                                   mfdManager.registerDetection(frameIdx, processedDetections.get(i), idsArray[i], true);
                                }
                            }

                            // once all results have been processed (and end_signal received)
                            // --- 7. generate Outputs, based on user choices
                            IJ.log(" --- Generating outputs... ");
                            generate3dOutputs(imp, mfdManager, outputOptions);

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            IJ.log("Consumer thread stopped.");
                        } finally {
                            long endTime = System.nanoTime();
                            double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
                            IJ.log(" --- SAM3 segmentation and detection complete. Total time= "+ totalTimeInSeconds + " sec ---");
                        }
                    }).start();

                    // execute task
                    IJ.log("Executing python script...");
                    task.waitFor();

                } catch (InterruptedException | TaskException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (BuildException e) {
            throw new RuntimeException(e);
        }
    }

    private void askUser() {
        GenericDialog gd = new GenericDialog("SAM3 Promptable Concept Segmentation on video");

        // 1- dynamic HELP SECTION (at the top ?? or at bottom ??)
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
        gd.addMessage("");
        gd.addButton("instructions to download SAM3 model", modelInstructionAction);
        gd.addMessage("");

        // 3- MODEL PATH
        Sam3Dialogs.addModelPathDialog(gd, PREF_LAST_MODEL_KEY);

        // 4-PROMPT(S)
        // 4.1- text prompt
        gd.addMessage("__________");
        gd.addCheckbox("Text prompt", true);
        Checkbox textCB = (Checkbox) gd.getCheckboxes().lastElement();

        Panel textPromptPanel = new Panel(new GridLayout(2, 1));
        textPromptPanel.add(new Label("Please enter a prompt"));
        TextField textPromptField = new TextField(10);
        textPromptPanel.add(textPromptField);
        gd.addPanel(textPromptPanel);

        // 4.2- positive visual prompt(s)
        gd.addCheckbox("Positive visual prompts", false);
        Checkbox visualCB = (Checkbox) gd.getCheckboxes().lastElement();

        final Panel visualPromptPanel = new Panel(new GridLayout(2, 1));
        final Choice choiceList = new Choice();

        if (groupNumber > 0) {
            // add list of groups id to choose from
            for (String choice : positiveGroupChoices) choiceList.add(choice);
            visualPromptPanel.add(new Label("Select positive group(s):"));
            visualPromptPanel.add(choiceList);

            gd.addPanel(visualPromptPanel);
            textPromptPanel.setVisible(true);
            visualPromptPanel.setVisible(false);

        // 4-3 if no ROI selected, block the checkboxes + print help message at top
        } else if (groupNumber == 0) {
            textCB.setForeground(Color.GRAY);
            textCB.addItemListener(e -> {
                textCB.setState(true); // revert if user try to uncheck
                updateHelpLabelEmpty(helpLabel, gd);
            });
            textPromptPanel.setVisible(true);

            visualCB.setForeground(Color.GRAY);
            visualCB.addItemListener(e -> {
                visualCB.setState(false); // Revert if user tries to check
                updateHelpLabelRequireRoi(helpLabel, gd);
            });
            visualPromptPanel.setVisible(false);

            // hover logic: update the label at the top if mouse
            visualCB.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    updateHelpLabelRequireRoi(helpLabel, gd);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    updateHelpLabelEmpty(helpLabel, gd);
                }
            });
        }

        // 4-4 update section visibility
        gd.addDialogListener(new DialogListener() {
            @Override
            public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
                boolean textChecked = textCB.getState();
                boolean visualChecked = visualCB.getState();

                // sections visible if boxes checked
                if (groupNumber > 0) {
                    textPromptPanel.setVisible(textChecked);
                    visualPromptPanel.setVisible(visualChecked);
                }

                // if no option selected (no boxe checked) : OK button is unabled
                // -> doesn't work !!
                if (!textChecked && !visualChecked) {
                    okButton.setEnabled(false);
                    updateHelpLabelNoOption(helpLabel, gd);
                } else {
                    okButton.setEnabled(true);
                    updateHelpLabelEmpty(helpLabel, gd);
                }

                return true;
            }
        });


        // 5- PARAMETERS
        gd.addMessage("__________");
        detectionParams = new Sam3Parameters();
        detectionParams.setMaxFrame(nFrames);
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
        if(textPrompt == null || textPrompt.trim().isEmpty() ){
            textPromptUsed = false;
            textPrompt="visual";
        }

        // visual positive prompt
        visualPositivePromptUsed = visualCB.getState() && groupNumber > 0;
        gd.getNextBoolean();
        String selectedPositiveGroup = choiceList.getSelectedItem();

        // visual negative prompt
        negativePromptUsed = false;
        negativeGroup = -1;

        if (visualPositivePromptUsed) {
            positiveGroups = new ArrayList<>();
            if (Objects.equals(selectedPositiveGroup, ALL_ROIS_GROUPS_TXT)){ // if all ROI group are prompts
                for (int id : uniqueGroups){
                    if (id != negativeGroup || !negativePromptUsed){
                        positiveGroups.add(id);
                    }
                }
            } else { // if only one group is prompt
                positiveGroups.add(Integer.parseInt(selectedPositiveGroup));
            }
        }

        if (positiveGroups == null || positiveGroups.isEmpty()) visualPositivePromptUsed = false;

        // detection and tracking parameters
        Sam3Dialogs.getParameters(gd, detectionParams, DetectionUtils.DetectionMode.VIDEO);
        // outputs
        outputOptions = Sam3Dialogs.getOutputAnswerVideo(gd);
    }

    private static void updateHelpLabelRequireRoi(Label helpLabel, GenericDialog gd) {
        helpLabel.setText("Please add ROI(s) to the ROI manager to enable visual prompts.");
        helpLabel.setForeground(Color.RED);
        gd.pack();
    }

    private static void updateHelpLabelEmpty(Label helpLabel, GenericDialog gd) {
        helpLabel.setText(" ");
        helpLabel.setForeground(Color.BLUE);
        gd.pack();
    }

    private static void updateHelpLabelNoOption(Label helpLabel, GenericDialog gd) {
        helpLabel.setText("Please select at least one option to perform prediction.");
        helpLabel.setForeground(Color.RED);
        gd.pack();
    }



    private void parseMacro() {

    }

    private void recordInMacro() {

    }

    private void printParameters() {
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("model: " + modelPath);
        IJ.log("number of frames to process: " + detectionParams.getMaxFrame());
        IJ.log("text prompt: " + textPrompt.toUpperCase());
        IJ.log("----------------------");
    }
}
