package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.apposeplugin.VideoPcsResultsConsumer;
import fr.curie.miclearning.tools.detection.Detection3dUtils;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.MultiFrameDataManager;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;
import ij.process.ImageConverter;
import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static fr.curie.miclearning.tools.detection.Detection3dUtils.addTrackedRoisToManager;
import static fr.curie.miclearning.tools.detection.Detection3dUtils.createInstanceMaskPerClassStackWithFixedIds;
import static fr.curie.miclearning.tools.detection.Detection3dUtils.createInstanceMaskStackWithFixedIds;
import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ plugin: SAM3 promptable-concept-segmentation on video; with an
 *  arbitrary number of independent concepts - each with an optional text prompt and/or an
 *  optional positive/negative visual prompt. Concept are encoded on prompt frame only.
 *  Detection is performed every N frame, and tracking carries object in between.
 */
public class Sam3VideoPcs_Plugin implements PlugIn {

    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-pcs-video.py";

    protected ImagePlus imp;
    int nFrames; // number of frames in the video
    int axis; // 3 : Z axis - 4 : Time axis

    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();
    private String detectionModelPath;
    private String trackingModelPath;
    private List<Sam3Concept> concepts;

    private Sam3ModelParameters modelParams;
    private DetectionUtils.OutputOptions outputOptions;

    // frame range chosen by the user in the FrameRangeSelector widget (1-indexed)
    private int chosenFirstFrame;
    private int chosenPromptFrame;
    private int chosenLastFrame;

    @Override
    public void run(String s) {
        // --- 1. Get image ---
        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        if (!imp.isRGB()){
            ImageConverter impConverter = new ImageConverter(imp);
            impConverter.convertToRGB();
            IJ.log("\n Warning: image " + imp.getTitle() + " converted to RGB");
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

        // --- 2. Retrieve parameters (model path, concepts, frame range, output options) ---
        // via macro or user interface
        if (Macro.getOptions() != null) parseMacro();
        else askUser();

        if (outputOptions == null || detectionModelPath == null || modelParams == null
                || concepts == null || concepts.isEmpty()) {
            return;
        }

        long startTime = System.nanoTime();

        // --- 3. Prepare prompts, define config ---
        // 3.1 prepare prompts
        // each concept is composed by:
        // - a name = unique key in classIdMap
        // - an output group / group id (value in the classIdMap)
        // - a combination of prompts, defined once :
        //   - a text prompt ("visual" placeholder if null)
        //   - visual prompt(s) (positive and/or negative), defined on prompt frame only (optional)

        List<String> conceptLabels = new ArrayList<>();
        List<String> conceptTexts = new ArrayList<>();
        List<Boolean> conceptTextUsed = new ArrayList<>();
        List<List<double[]>> conceptPositiveRois = new ArrayList<>();
        List<List<double[]>> conceptNegativeRois = new ArrayList<>();
        Map<String, Integer> classIdMap = new HashMap<>();

        Roi[] roisForRun = roiManager.getRoisAsArray(); // all ROIs in the RoiManager when the user presses "ok"
        List<Roi> roisAtPromptFrame = roiPromptExtractor.getRoisAtFrame(roisForRun, axis, chosenPromptFrame); // roi on the prompt frame

        // register each concept + extract relevant ROIs
        for (Sam3Concept concept : concepts) {
            // register concept
            conceptLabels.add(concept.getName());
            conceptTexts.add(concept.isTextPromptUsed() ? concept.getTextPrompt() : "visual");
            conceptTextUsed.add(concept.isTextPromptUsed());
            classIdMap.put(concept.getName(), concept.getOutputGroup()); //

            // extract ROIs from the positive and negative groups (if any)
            // on the prompt frame only
            RoiPromptExtractor.PromptRois promptRois = roiPromptExtractor.buildPromptRois(
                    roisAtPromptFrame, concept.getPositiveVisualGroup(), concept.getNegativeVisualGroup());
            conceptPositiveRois.add(promptRois.getPositive());
            conceptNegativeRois.add(promptRois.getNegative());
        }

        // 3.2 prepare config
        // frame range (as chosen by the user), 0-indexed
        int posFirstFrame = chosenFirstFrame - 1;
        int posPromptFrame = chosenPromptFrame - 1;
        int posEndFrame = chosenLastFrame - 1;
        modelParams.setNFrameToProcess(posEndFrame - posFirstFrame + 1);

        // prepare model and run configuration
        Sam3VideoRunConfig config;
        try {
            config = new Sam3VideoRunConfig.Builder()
                    .modelPath(detectionModelPath, trackingModelPath)
                    .conceptLabels(conceptLabels)
                    .conceptTexts(conceptTexts)
                    .conceptTextUsed(conceptTextUsed)
                    .conceptPositiveRois(conceptPositiveRois)
                    .conceptNegativeRois(conceptNegativeRois)
                    .frameRange(posFirstFrame, posPromptFrame, posEndFrame)
                    .detectionParams(modelParams)
                    .outputOptions(outputOptions)
                    .classIdMap(classIdMap)
                    .build();
        } catch (IllegalStateException e) {
            IJ.error("Invalid configuration", e.getMessage());
            return;
        }

        //prepare output manager
        MultiFrameDataManager mfdManager = new MultiFrameDataManager(posFirstFrame, posEndFrame);

        // record macro, print parameters in log
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
            Thread t = new Thread(r, "sam3-video-result-consumer");
            t.setDaemon(true);
            return t;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(consumerThreadFactory);

        try (Sam3VideoPythonRunner runner = new Sam3VideoPythonRunner(SCRIPT_PATH, ENV_FILE_PATH)) {
            runner.initialize();

            VideoPcsResultsConsumer consumer = new VideoPcsResultsConsumer(
                    resultsQueue, mfdManager, config.getClassIdMap(), config.getConceptLabels(), imp);
            Future<Void> consumerResult = executor.submit(consumer);

            IJ.log("Executing python script...");
            IJ.showStatus("sam3: executing python script");
            IJ.showProgress(0,100);

            runner.runBlocking(config, imp, resultsQueue);

            // Wait for the consumer to finish draining the queue before generating outputs
            consumerResult.get();

            IJ.log(" --- Generating outputs... ");
            IJ.showStatus("sam3: generating outputs");
            generateOutputs(mfdManager, config);

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

    private void generateOutputs(MultiFrameDataManager mfdManager, Sam3VideoRunConfig config) {
        DetectionUtils.OutputOptions options = config.getOutputOptions();
        if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
            RoiManager roiManager = getRoiManager();
            addTrackedRoisToManager(roiManager, mfdManager, options.addToRoiManagerBB, options.addToRoiManagerShapes, Detection3dUtils.GroupingMethod.BY_CLASS);
            roiManager.setVisible(true);
            roiManager.runCommand("Show All"); // Make ROIs visible
        }

        if (options.createInstanceMask){
            ImagePlus stackMask = createInstanceMaskStackWithFixedIds(imp, mfdManager);
            if (stackMask != null) stackMask.show();
        }

        if (options.createSemanticMask) {
            ImagePlus semanticMaskStack = DetectionUtils.createSemanticMaskStack(imp, mfdManager.getDetectionsByFrame());
            if (semanticMaskStack != null) semanticMaskStack.show();
        }

        if (options.createInstanceMaskPerClass) {
            ImagePlus instanceMaskPerClass = createInstanceMaskPerClassStackWithFixedIds(imp, mfdManager, config.getClassIdMap());
            if (instanceMaskPerClass != null) instanceMaskPerClass.show();
        }
    }

    private void askUser() {
        Sam3VideoDialog dialog = new Sam3VideoDialog(imp, nFrames, axis);
        if (!dialog.showDialogAndWait()) return; // user canceled, or closed the window

        // only reached after dialog closes (and if true, e.i. if ok clicked)
        detectionModelPath = dialog.getModelPath();
        trackingModelPath = dialog.getTrackingModelPath();
        modelParams = dialog.getFinalModelParam();
        outputOptions = dialog.getOutputOptions();
        concepts = dialog.getConcepts();

        chosenFirstFrame = dialog.getChosenFirstFrame();
        chosenPromptFrame = dialog.getChosenPromptFrame();
        chosenLastFrame = dialog.getChosenLastFrame();
    }

    private void parseMacro() {
        IJ.log("\nSAM3 sampcs on macro");
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

        // one entry per concept, index-aligned; "output_groups" defines the concept count
        String outputGroupsStr = Macro.getValue(options, "output_groups", "");
        if (outputGroupsStr.trim().isEmpty()) {
            IJ.error("No concept defined.", "Please provide at least one output group id (\"output_groups\").");
            return;
        }
        String[] outputGroupsArr = outputGroupsStr.split(",");
        String[] namesArr = splitAligned(Macro.getValue(options, "names", ""), outputGroupsArr.length);
        String[] textsArr = splitAligned(Macro.getValue(options, "texts", ""), outputGroupsArr.length);
        String[] positiveVisualArr = splitAligned(Macro.getValue(options, "positive_visual_groups", ""), outputGroupsArr.length);
        String[] negativeVisualArr = splitAligned(Macro.getValue(options, "negative_visual_groups", ""), outputGroupsArr.length);

        concepts = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < outputGroupsArr.length; i++) {
            int outputGroup = Integer.parseInt(outputGroupsArr[i].trim());
            Sam3Concept concept = new Sam3Concept(i + 1, outputGroup);

            concept.setTextPrompt(textsArr[i].trim());
            concept.setPositiveVisualGroup(parseOptionalInt(positiveVisualArr[i]));
            concept.setNegativeVisualGroup(parseOptionalInt(negativeVisualArr[i]));

            String requestedName = namesArr[i].trim();
            concept.setName(uniqueName(requestedName.isEmpty() ? concept.getName() : requestedName, usedNames));
            usedNames.add(concept.getName());

            if (!concept.hasAnyPositivePrompt()) {
                IJ.log("Warning: concept " + concept.getName() + " has neither text nor positive visual prompt - skipped.");
                continue;
            }
            if (!concept.isNegativeGroupValid()) {
                IJ.log("Warning: concept " + concept.getName() + " uses the same group for positive and negative visual - negative prompt ignored.");
                concept.setNegativeVisualGroup(null);
            }
            concepts.add(concept);
        }
        if (concepts.isEmpty()) {
            IJ.error("No valid concept defined.");
            return;
        }

        // frame range (1-indexed), same defaults as the dialog: prompt = current slice,
        // first = prompt (non-bidirectional), last = last frame of the video
        chosenPromptFrame = clampFrame(Integer.parseInt(Macro.getValue(options, "prompt_frame", String.valueOf(imp.getCurrentSlice()))));
        chosenLastFrame = Math.max(chosenPromptFrame, clampFrame(Integer.parseInt(Macro.getValue(options, "last_frame", String.valueOf(nFrames)))));
        chosenFirstFrame = Math.min(chosenPromptFrame, clampFrame(Integer.parseInt(Macro.getValue(options, "first_frame", String.valueOf(chosenPromptFrame)))));

        // detection/tracking parameters
        modelParams = new Sam3ModelParameters();
        double confidenceThreshold = Double.parseDouble(Macro.getValue(options, "confidence", String.valueOf(modelParams.getConfidenceThreshold())));
        if (confidenceThreshold < 0 || confidenceThreshold > 1) confidenceThreshold = modelParams.getConfidenceThreshold();
        modelParams.setConfidenceThreshold(confidenceThreshold);

        double maskThreshold = Double.parseDouble(Macro.getValue(options, "mask_threshold", String.valueOf(modelParams.getMaskScoreThreshold())));
        modelParams.setMaskScoreThreshold(maskThreshold);

        int maxSideLengthDetect = Integer.parseInt(Macro.getValue(options, "max_side_length", String.valueOf(modelParams.getMaxSideLengthDetect())));
        if (maxSideLengthDetect <= 0) maxSideLengthDetect = modelParams.getMaxSideLengthDetect();
        modelParams.setMaxSideLengthDetect(maxSideLengthDetect);

        int maxSideLengthTrack = Integer.parseInt(Macro.getValue(options, "max_side_length_track", String.valueOf(modelParams.getMaxSideLengthTrack())));
        if (maxSideLengthTrack <= 0) maxSideLengthTrack = modelParams.getMaxSideLengthTrack();
        modelParams.setMaxSideLengthTrack(maxSideLengthTrack);

        int nFramesBtwDetec = Integer.parseInt(Macro.getValue(options, "frames_between_detections", String.valueOf(modelParams.getNFrameBtwDetections())));
        if (nFramesBtwDetec <= 0) nFramesBtwDetec = chosenLastFrame - chosenFirstFrame + 1;
        modelParams.setNFrameBtwDetections(nFramesBtwDetec);

        double trackingScoreThreshold = Double.parseDouble(Macro.getValue(options, "tracking_score_threshold", String.valueOf(modelParams.getTrackingScoreThreshold())));
        modelParams.setTrackingScoreThreshold(trackingScoreThreshold);

        int removeAfterNMissed = Integer.parseInt(Macro.getValue(options, "remove_after_n_missed", String.valueOf(modelParams.getRemoveAfterNMissed())));
        modelParams.setRemoveAfterNMissed(removeAfterNMissed);

        double boxIouThreshold = Double.parseDouble(Macro.getValue(options, "box_iou_threshold", String.valueOf(modelParams.getTrackingBoxIouThreshold())));
        modelParams.setTrackingBoxIouThreshold(boxIouThreshold);

        // outputs
        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false"));
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false"));
        outputOptions.deletePreviousRoi = false;
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false"));
        outputOptions.createSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "create_semantic_mask", "false"));
        outputOptions.createInstanceMaskPerClass = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask_per_class", "false"));
    }

    private int clampFrame(int value) {
        return Math.max(1, Math.min(nFrames, value));
    }

    /** Parses a trimmed integer, or {@code null} when the text is empty. */
    private Integer parseOptionalInt(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? null : Integer.parseInt(trimmed);
    }

    /** {@code base} trimmed, with "-1", "-2", ... appended until it is absent from {@code taken}. */
    private String uniqueName(String base, Set<String> taken) {
        String candidate = base == null ? "" : base.trim();
        String root = candidate;
        int suffix = 1;
        while (taken.contains(candidate)) {
            candidate = root + "-" + suffix++;
        }
        return candidate;
    }

    /** Splits a comma-separated macro option into exactly {@code expectedLength} entries (missing entries become ""). */
    private String[] splitAligned(String csv, int expectedLength) {
        String[] result = new String[expectedLength];
        Arrays.fill(result, "");
        if (csv == null || csv.trim().isEmpty()) return result;

        String[] parts = csv.split(",", -1);
        if (parts.length != expectedLength) {
            IJ.log("Warning: expected " + expectedLength + " comma-separated value(s), got " + parts.length + " in \"" + csv + "\"");
        }
        for (int i = 0; i < expectedLength && i < parts.length; i++) result[i] = parts[i];
        return result;
    }

    private void recordInMacro() {
        Recorder.setCommand("SAM3 PCS on Video");
        Recorder.recordOption("model_path", detectionModelPath);
        if (trackingModelPath != null && !trackingModelPath.trim().isEmpty()) {
            Recorder.recordOption("tracking_model_path", trackingModelPath);
        }

        String outputGroups = concepts.stream().map(c -> String.valueOf(c.getOutputGroup())).collect(Collectors.joining(","));
        Recorder.recordOption("output_groups", outputGroups);

        String names = concepts.stream().map(Sam3Concept::getName).collect(Collectors.joining(","));
        Recorder.recordOption("names", names);

        String texts = concepts.stream().map(c -> c.isTextPromptUsed() ? c.getTextPrompt() : "").collect(Collectors.joining(","));
        Recorder.recordOption("texts", texts);

        String positiveVisualGroups = concepts.stream()
                .map(c -> c.isPositiveVisualUsed() ? String.valueOf(c.getPositiveVisualGroup()) : "")
                .collect(Collectors.joining(","));
        Recorder.recordOption("positive_visual_groups", positiveVisualGroups);

        String negativeVisualGroups = concepts.stream()
                .map(c -> c.isNegativeVisualUsed() ? String.valueOf(c.getNegativeVisualGroup()) : "")
                .collect(Collectors.joining(","));
        Recorder.recordOption("negative_visual_groups", negativeVisualGroups);

        Recorder.recordOption("first_frame", String.valueOf(chosenFirstFrame));
        Recorder.recordOption("prompt_frame", String.valueOf(chosenPromptFrame));
        Recorder.recordOption("last_frame", String.valueOf(chosenLastFrame));

        Recorder.recordOption("confidence", String.valueOf(modelParams.getConfidenceThreshold()));
        Recorder.recordOption("mask_threshold", String.valueOf(modelParams.getMaskScoreThreshold()));
        Recorder.recordOption("max_side_length", String.valueOf(modelParams.getMaxSideLengthDetect()));
        Recorder.recordOption("max_side_length_track", String.valueOf(modelParams.getMaxSideLengthTrack()));
        Recorder.recordOption("frames_between_detections", String.valueOf(modelParams.getNFrameBtwDetections()));
        Recorder.recordOption("tracking_score_threshold", String.valueOf(modelParams.getTrackingScoreThreshold()));
        Recorder.recordOption("remove_after_n_missed", String.valueOf(modelParams.getRemoveAfterNMissed()));
        Recorder.recordOption("box_iou_threshold", String.valueOf(modelParams.getTrackingBoxIouThreshold()));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_box_rois", String.valueOf(true));
        if (outputOptions.addToRoiManagerShapes) Recorder.recordOption("add_shape_rois", String.valueOf(true));
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask", String.valueOf(true));
        if (outputOptions.createSemanticMask) Recorder.recordOption("create_semantic_mask", String.valueOf(true));
        if (outputOptions.createInstanceMaskPerClass) Recorder.recordOption("create_instance_mask_per_class", String.valueOf(true));
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
        IJ.log("number of concept(s): " + config.getConceptLabels().size());
        List<String> labels = config.getConceptLabels();
        List<String> texts = config.getConceptTexts();
        List<Boolean> textUsed = config.getConceptTextUsed();
        List<List<double[]>> positiveRois = config.getConceptPositiveRois();
        List<List<double[]>> negativeRois = config.getConceptNegativeRois();
        for (int i = 0; i < labels.size(); i++) {
            StringBuilder line = new StringBuilder("  - ").append(labels.get(i)).append(" -> prompt:");
            if (textUsed.get(i)) line.append("  text=\"").append(texts.get(i)).append("\"");
            if (!positiveRois.get(i).isEmpty()) line.append("  positive visual: group ")
                    .append(concepts.get(i).getPositiveVisualGroup()).append(" (")
                    .append(positiveRois.get(i).size()).append(" roi")
                    .append(positiveRois.get(i).size()>1?"s)":")");
            if (!negativeRois.get(i).isEmpty()) line.append("  negative visual: group ")
                    .append(concepts.get(i).getNegativeVisualGroup()).append(" (")
                    .append(negativeRois.get(i).size()).append(" roi")
                    .append(negativeRois.get(i).size()>1?"s)":")");
            line.append(" -> output group: ").append(concepts.get(i).getOutputGroup());
            IJ.log(line.toString());
        }
        int nFrameBtwDetec = params.getNFrameBtwDetections();
        if (nFrameBtwDetec >= config.getFrameCount()) IJ.log("Detection only on frame " + config.getPromptFrame());
        else IJ.log("detection every " + (nFrameBtwDetec>1 ? nFrameBtwDetec + " frames" : "frame"));
        IJ.log("confidence threshold for detection: " + params.getConfidenceThreshold());
        IJ.log("----------------------");
    }

}
