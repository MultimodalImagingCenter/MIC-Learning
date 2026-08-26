package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.MultiImagePcsResultsConsumer;
import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.tools.detection.Detection3dUtils;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.DetectionUtils.DetectionMode;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ plugin: SAM3 promptable-concept-segmentation over an image (or image stack), with an
 * arbitrary number of independent concepts - each with an optional text prompt and/or an
 * optional positive/negative visual prompt. Detections are independent per frame; a concept's
 * text prompt stays constant across frames, but its visual prompt is re-resolved from the
 * RoiManager on every frame.
 */
public class Sam3SingleImgPcs_Plugin implements PlugIn {
    protected static ImagePlus imp;
    private int nFrames;
    private int axis; // 3 = Z axis, 4 = Time axis (matches ImagePlus.getDimensions() indexing)
    private int current_slice = 0; // 0 if all slices are processed, position of the processed frame otherwise

    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();

    private DetectionUtils.OutputOptions outputOptions;
    private String modelPath;
    private Sam3ModelParameters detectionParams;
    private DetectionMode stackMode;
    private List<Sam3Concept> concepts;

    private final Map<Integer, List<ProcessedDetection>> detectionsByFrame = new HashMap<>();

    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml"; // inside the resources folder
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-image-concepts-m.py"; // inside the resources folder

    @Override
    public void run(String s) {
        // --- 1. get image ---
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }
        if (!imp.isRGB()) {
            ImageConverter impConverter = new ImageConverter(imp);
            impConverter.convertToRGB();
            IJ.log("image " + imp.getTitle() + " converted to RGB");
        }

        final int[] dims = imp.getDimensions();
        int numberOfFrame = dims[4];
        int numberOfSlices = dims[3];
        if (numberOfSlices > 1 && numberOfFrame > 1) { // does not work on hyperstacks for now
            IJ.error("Hyperstacks are not supported");
            return;
        }
        axis = numberOfSlices >= numberOfFrame ? 3 : 4; // just using the axis with multiple images as time axis
        nFrames = dims[axis];

        // make any existing ROIs visible
        RoiManager roiManager = getRoiManager();
        if (roiManager.getCount() > 0) roiManager.runCommand("Show All");

        // --- 2. retrieve parameters (model path, concepts, stack mode, output options) ---
        boolean ok = Macro.getOptions() != null ? parseMacro() : askUser();
        if (!ok || outputOptions == null || modelPath == null || detectionParams == null
                || stackMode == null || concepts == null || concepts.isEmpty()) {
            return;
        }

        long startTime = System.nanoTime();

        if (stackMode == DetectionMode.SINGLE_IMAGE) {
            current_slice = imp.getCurrentSlice();
            imp = new ImagePlus(imp.getTitle(), imp.getProcessor());
        }

        // --- 3. build concept labels/texts/classIdMap + per-frame prompts ---
        List<String> conceptLabels = new ArrayList<>();
        List<String> conceptTexts = new ArrayList<>();
        List<Boolean> conceptTextUsed = new ArrayList<>();
        Map<String, Integer> classIdMap = new HashMap<>();
        for (Sam3Concept concept : concepts) {
            conceptLabels.add(concept.getLabel());
            conceptTexts.add(concept.isTextPromptUsed() ? concept.getTextPrompt() : "visual");
            conceptTextUsed.add(concept.isTextPromptUsed());
            classIdMap.put(concept.getLabel(), concept.getClassId());
        }

        Roi[] allRois = roiManager.getRoisAsArray();
        List<List<Map<String, Object>>> framePrompts = new ArrayList<>();
        if (stackMode == DetectionMode.SINGLE_IMAGE) {
            framePrompts.add(buildFrameConceptPrompts(allRois, current_slice));
        } else {
            for (int frame = 1; frame <= nFrames; frame++) {
                framePrompts.add(buildFrameConceptPrompts(allRois, frame));
            }
        }

        recordInMacro();
        IJ.log("\n   --- Starting SAM Promptable Concept Segmentation - on concepts ---");
        printParameters();

        Sam3ConceptsPcsRunConfig config;
        try {
            config = new Sam3ConceptsPcsRunConfig.Builder()
                    .modelPath(modelPath)
                    .detectionParams(detectionParams)
                    .outputOptions(outputOptions)
                    .stackMode(stackMode)
                    .conceptLabels(conceptLabels)
                    .conceptTexts(conceptTexts)
                    .conceptTextUsed(conceptTextUsed)
                    .classIdMap(classIdMap)
                    .framePrompts(framePrompts)
                    .build();
        } catch (IllegalStateException e) {
            IJ.error("Invalid configuration", e.getMessage());
            return;
        }

        runSam3(config, startTime);
    }

    /** Resolves each concept's positive/negative visual prompt from the RoiManager, at {@code frame}. */
    private List<Map<String, Object>> buildFrameConceptPrompts(Roi[] allRois, int frame) {
        List<Roi> roisAtFrame = roiPromptExtractor.getRoisAtFrame(allRois, axis, frame);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Sam3Concept concept : concepts) {
            List<Integer> positiveGroups = concept.isPositiveVisualUsed()
                    ? Collections.singletonList(concept.getClassId()) : Collections.emptyList();
            Integer negativeGroup = concept.isNegativeVisualUsed() ? concept.getNegativeGroupId() : null;
            RoiPromptExtractor.PromptRois promptRois = roiPromptExtractor.buildPromptRois(roisAtFrame, positiveGroups, negativeGroup);

            Map<String, Object> entry = new HashMap<>();
            entry.put("positive_rois", promptRois.getPositive());
            entry.put("negative_rois", promptRois.getNegative());
            result.add(entry);
        }
        return result;
    }

    private void runSam3(Sam3ConceptsPcsRunConfig config, long startTime) {
        BlockingQueue<Map<String, Object>> resultsQueue = new LinkedBlockingQueue<>();
        ThreadFactory consumerThreadFactory = r -> {
            Thread t = new Thread(r, "sam3-concepts-consumer");
            t.setDaemon(true);
            return t;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(consumerThreadFactory);

        try (Sam3ConceptsPcsPythonRunner runner = new Sam3ConceptsPcsPythonRunner(SCRIPT_PATH, ENV_FILE_PATH)) {
            runner.initialize();

            MultiImagePcsResultsConsumer consumer = new MultiImagePcsResultsConsumer(
                    resultsQueue, detectionsByFrame, config.getClassIdMap(), config.getConceptLabels(), imp);
            Future<Void> consumerResult = executor.submit(consumer);

            IJ.log("Executing python script...");
            runner.runBlocking(config, imp, resultsQueue);

            consumerResult.get();

            IJ.log(" --- Generating output... ");
            generateOutputs(config);

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
            IJ.log(" --- SAM3 segmentation complete. " + detectionsByFrame.size()
                    + " Frame processed. Total time= " + totalTimeInSeconds + " sec ---");
        }
    }

    private void generateOutputs(Sam3ConceptsPcsRunConfig config) {
        DetectionUtils.OutputOptions options = config.getOutputOptions();
        DetectionMode mode = config.getStackMode();

        if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
            RoiManager roiManager = getRoiManager();
            if (current_slice != 0) detectionsByFrame.put(current_slice - 1, detectionsByFrame.remove(0));
            Detection3dUtils.addTrackedRoisToManager(roiManager, detectionsByFrame,
                    options.addToRoiManagerBB, options.addToRoiManagerShapes, Detection3dUtils.GroupingMethod.BY_CLASS);
            roiManager.setVisible(true);
            roiManager.runCommand("Show All");
        }

        if (options.createInstanceMask) {
            ImagePlus instanceMaskStack = DetectionUtils.createInstanceMaskStack(imp, detectionsByFrame);
            if (instanceMaskStack != null) instanceMaskStack.show();
        }

        if (options.createSemanticMask) {
            ImagePlus semanticMaskStack = DetectionUtils.createSemanticMaskStack(imp, detectionsByFrame);
            if (semanticMaskStack != null) semanticMaskStack.show();
        }

        if (mode == DetectionMode.SINGLE_IMAGE && options.createInstanceMaskPerClass) {
            ImagePlus instanceMaskPerClass = DetectionUtils.createInstanceMaskPerClass(
                    imp, detectionsByFrame.values().iterator().next(), config.getClassIdMap());
            if (instanceMaskPerClass != null) instanceMaskPerClass.show();
        }
    }

    private boolean askUser() {
        Sam3ConceptsDialog dialog = new Sam3ConceptsDialog(imp, nFrames, axis);
        if (!dialog.showDialogAndWait()) return false;

        modelPath = dialog.getModelPath();
        detectionParams = dialog.getDetectionParams();
        outputOptions = dialog.getOutputOptions();
        stackMode = dialog.getStackMode();
        concepts = dialog.getConcepts();
        return true;
    }

    private boolean parseMacro() {
        IJ.log("\nSAM3 sampcs on macro");
        String options = Macro.getOptions();

        modelPath = Macro.getValue(options, "model_path", null);
        if (modelPath == null || modelPath.trim().isEmpty()) {
            IJ.log("No model path. Closing plug-in.\n");
            IJ.error("No model path specified.");
            return false;
        }

        String classIdsStr = Macro.getValue(options, "class_ids", "");
        if (classIdsStr.trim().isEmpty()) {
            IJ.error("No concept defined.", "Please provide at least one class id (\"class_ids\").");
            return false;
        }
        String[] classIdsArr = classIdsStr.split(",");
        String[] textsArr = splitAligned(Macro.getValue(options, "texts", ""), classIdsArr.length);
        String[] positiveVisualArr = splitAligned(Macro.getValue(options, "positive_visual", ""), classIdsArr.length);
        String[] negativeGroupIdsArr = splitAligned(Macro.getValue(options, "negative_group_ids", ""), classIdsArr.length);

        concepts = new ArrayList<>();
        for (int i = 0; i < classIdsArr.length; i++) {
            int classId = Integer.parseInt(classIdsArr[i].trim());
            Sam3Concept concept = new Sam3Concept(classId);

            String text = textsArr[i].trim();
            concept.setTextPromptUsed(!text.isEmpty());
            concept.setTextPrompt(text);

            concept.setPositiveVisualUsed(Boolean.parseBoolean(positiveVisualArr[i].trim()));

            String negativeGroupStr = negativeGroupIdsArr[i].trim();
            int negativeGroup = negativeGroupStr.isEmpty() ? -1 : Integer.parseInt(negativeGroupStr);
            concept.setNegativeVisualUsed(negativeGroup != -1);
            concept.setNegativeGroupId(negativeGroup != -1 ? negativeGroup : null);

            if (!concept.hasAnyPositivePrompt()) {
                IJ.log("Warning: concept (group " + classId + ") has neither text nor positive visual prompt - skipped.");
                continue;
            }
            if (!concept.isNegativeGroupValid()) {
                IJ.log("Warning: concept (group " + classId + ") can't use its own group as negative - negative prompt ignored.");
                concept.setNegativeVisualUsed(false);
                concept.setNegativeGroupId(null);
            }
            concepts.add(concept);
        }
        if (concepts.isEmpty()) {
            IJ.error("No valid concept defined.");
            return false;
        }

        detectionParams = new Sam3ModelParameters();
        double confidenceThreshold = Double.parseDouble(Macro.getValue(options, "confidence", String.valueOf(detectionParams.getConfidenceThreshold())));
        if (confidenceThreshold < 0 || confidenceThreshold > 1) confidenceThreshold = detectionParams.getConfidenceThreshold();
        detectionParams.setConfidenceThreshold(confidenceThreshold);

        double maskThreshold = Double.parseDouble(Macro.getValue(options, "mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold())));
        detectionParams.setMaskScoreThreshold(maskThreshold);

        int maxSideLength = Integer.parseInt(Macro.getValue(options, "max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect())));
        if (maxSideLength <= 0) maxSideLength = detectionParams.getMaxSideLengthDetect();
        detectionParams.setMaxSideLengthDetect(maxSideLength);

        stackMode = (nFrames > 1 && options.contains("stack")) ? DetectionMode.MULTI_IMAGE : DetectionMode.SINGLE_IMAGE;

        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false")) || options.contains("add_box_rois ");
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false")) || options.contains("add_shape_rois ");
        outputOptions.deletePreviousRoi = false;
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false")) || options.contains("create_instance_mask ");
        outputOptions.createSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "create_semantic_mask", "false")) || options.contains("create_semantic_mask ");
        outputOptions.createInstanceMaskPerClass = (Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask_per_class", "false")) || options.contains("create_instance_mask_per_class "))
                && stackMode == DetectionMode.SINGLE_IMAGE;

        return true;
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
        Recorder.setCommand("SAM3 PCS Concepts");
        Recorder.recordOption("model_path", modelPath);

        String classIds = concepts.stream().map(c -> String.valueOf(c.getClassId())).collect(Collectors.joining(","));
        Recorder.recordOption("class_ids", classIds);

        String texts = concepts.stream().map(c -> c.isTextPromptUsed() ? c.getTextPrompt() : "").collect(Collectors.joining(","));
        Recorder.recordOption("texts", texts);

        String positiveVisual = concepts.stream().map(c -> String.valueOf(c.isPositiveVisualUsed())).collect(Collectors.joining(","));
        Recorder.recordOption("positive_visual", positiveVisual);

        String negativeGroupIds = concepts.stream()
                .map(c -> String.valueOf(c.isNegativeVisualUsed() && c.getNegativeGroupId() != null ? c.getNegativeGroupId() : -1))
                .collect(Collectors.joining(","));
        Recorder.recordOption("negative_group_ids", negativeGroupIds);

        Recorder.recordOption("confidence", String.valueOf(detectionParams.getConfidenceThreshold()));
        Recorder.recordOption("mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold()));
        Recorder.recordOption("max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect()));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_box_rois", String.valueOf(true));
        if (outputOptions.addToRoiManagerShapes) Recorder.recordOption("add_shape_rois", String.valueOf(true));
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask", String.valueOf(true));
        if (outputOptions.createSemanticMask) Recorder.recordOption("create_semantic_mask", String.valueOf(true));
        if (outputOptions.createInstanceMaskPerClass) Recorder.recordOption("create_instance_mask_per_class", String.valueOf(true));

        if (stackMode == DetectionMode.MULTI_IMAGE) Recorder.recordOption("stack");
    }

    private void printParameters() {
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("number of slice(s): " + (stackMode == DetectionMode.SINGLE_IMAGE ? 1 : nFrames));
        IJ.log("model: " + modelPath);
        IJ.log("confidence threshold: " + detectionParams.getConfidenceThreshold());
        IJ.log("mask score threshold: " + detectionParams.getMaskScoreThreshold());
        IJ.log("number of concept(s): " + concepts.size());
        for (Sam3Concept concept : concepts) {
            StringBuilder line = new StringBuilder("  - group ").append(concept.getClassId());
            if (concept.isTextPromptUsed()) line.append(" - text: \"").append(concept.getTextPrompt()).append("\"");
            if (concept.isPositiveVisualUsed()) line.append(" - positive visual");
            if (concept.isNegativeVisualUsed()) line.append(" - negative group: ").append(concept.getNegativeGroupId());
            IJ.log(line.toString());
        }
        IJ.log("----------------------");
    }
}