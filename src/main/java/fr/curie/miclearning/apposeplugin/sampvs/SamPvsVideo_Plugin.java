package fr.curie.miclearning.apposeplugin.sampvs;

import fr.curie.miclearning.apposeplugin.PromptRois;
import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static fr.curie.miclearning.tools.detection.Detection3dUtils.*;
import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ plugin: promptable-visual-segmentation on video, with SAM2 or SAM3
 * An arbitrary number of independent objects, each identified by visual prompts
 * (point and/or area ROIs, no text, no class). One object = One Roi Group
 * Tracking runs forward only,
 * starting for each object on whichever frame its own first prompt appears; a later prompt for an
 * already-tracked object refines it going forward without affecting earlier frames.
 */
public class SamPvsVideo_Plugin implements PlugIn {

    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam-pvs-video.py";

    private ImagePlus imp;
    private int nFrames; // number of frames in the video/stack
    private int axis; // 3 : Z axis - 4 : Time axis

    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();
    private String modelPath;
    private List<PvsObject> objects;

    private PvsModelParameters modelParams;
    private DetectionUtils.OutputOptions outputOptions;

    private int chosenLastFrame; // 1-indexed, chosen by the user ("process up to frame")

    @Override
    public void run(String s) {
        // --- 1. Get image ---
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        if (!imp.isRGB()) {
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
        axis = numberOfSlices >= numberOfFrame ? 3 : 4; // just using the axis with multiple images as time axis
        nFrames = dims[axis];

        // make any existing ROIs visible
        RoiManager roiManager = getRoiManager();
        if (roiManager.getCount() > 0) roiManager.runCommand("Show All");

        // --- 2. Retrieve parameters (model path, objects, frame bound, output options) ---
        // via macro or user interface
        if (Macro.getOptions() != null) parseMacro();
        else askUser();

        if (outputOptions == null || modelPath == null || modelParams == null
                || objects == null || objects.isEmpty()) {
            return;
        }

        long startTime = System.nanoTime();
        IJ.log("\n");
        // --- 3. Resolve the table rows into actually-tracked objects, build the sparse
        // per-frame per-object prompts, and the run config ---
        // an OBJECT-mode row is resolved as one tracked object, refined by every one of
        // its positive-group ROIs on every frame. A CLASS-mode row is resolved into one tracked
        // object per distinct ROI name seen in its positive group
        Roi[] roisForRun = roiManager.getRoisAsArray(); // all ROIs in the RoiManager when the user presses "ok"
        Map<Integer, Map<Integer, PromptRois>> framePrompts = new TreeMap<>();
        List<ResolvedObject> resolved = resolveObjects(roisForRun, framePrompts);

        List<String> objectNames = resolved.stream().map(r -> r.name).collect(Collectors.toList());
        Map<String, Integer> classIdMap = new HashMap<>();
        Map<String, String> rowNameByObjectName = new HashMap<>(); // for the instance mask per class: one channel per table row
        for (ResolvedObject r : resolved) {
            classIdMap.put(r.name, r.outputGroup);
            rowNameByObjectName.put(r.name, r.rowName);
        }

        int posLastFrame = chosenLastFrame - 1; // 0-indexed, inclusive
        // frames before the earliest object prompt are ignored entirely
        int posFirstFrame = framePrompts.isEmpty() ? 0 : Collections.min(framePrompts.keySet());

        PvsVideoRunConfig config;
        try {
            config = new PvsVideoRunConfig.Builder()
                    .modelPath(modelPath)
                    .objectNames(objectNames)
                    .classIdMap(classIdMap)
                    .framePrompts(framePrompts)
                    .firstFrame(posFirstFrame)
                    .lastFrame(posLastFrame)
                    .modelParams(modelParams)
                    .outputOptions(outputOptions)
                    .build();
        } catch (IllegalStateException e) {
            IJ.error("Invalid configuration", e.getMessage());
            return;
        }

        MultiFrameDataManager mfdManager = new MultiFrameDataManager(posFirstFrame, posLastFrame);

        // record macro, print parameters in log
        recordInMacro();
        IJ.log("\n   --- Starting SAM Promptable Visual Segmentation on video --- ");
        printParameters(config, resolved);

        // --- 4. Run ---
        runPvs(config, mfdManager, rowNameByObjectName, startTime);
    }

    private void runPvs(PvsVideoRunConfig config, MultiFrameDataManager mfdManager,
                        Map<String, String> rowNameByObjectName, long startTime) {
        IJ.showStatus("sam pvs: environment initialization");
        BlockingQueue<Map<String, Object>> resultsQueue = new LinkedBlockingQueue<>();
        ThreadFactory consumerThreadFactory = r -> {
            Thread t = new Thread(r, "sam-pvs-video-result-consumer");
            t.setDaemon(true);
            return t;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(consumerThreadFactory);

        try (PvsVideoPythonRunner runner = new PvsVideoPythonRunner(SCRIPT_PATH, ENV_FILE_PATH)) {
            runner.initialize();

            PvsVideoResultsConsumer consumer = new PvsVideoResultsConsumer(
                    resultsQueue, mfdManager, config.getClassIdMap(), config.getObjectNames(), imp);
            Future<Void> consumerResult = executor.submit(consumer);

            IJ.log("Executing python script...");
            IJ.showStatus("sam pvs: executing python script");
            IJ.showProgress(0, 100);

            runner.runBlocking(config, imp, resultsQueue);

            // Wait for the consumer to finish draining the queue before generating outputs
            consumerResult.get();

            IJ.log(" --- Generating outputs... ");
            IJ.showStatus("sam pvs: generating outputs");
            generateOutputs(mfdManager, config, rowNameByObjectName);

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
            IJ.log(" --- SAM PVS segmentation and tracking complete. Total time= " + totalTimeInSeconds + " sec ---");
            IJ.showStatus("sam pvs: segmentation complete");
        }
    }

    private void generateOutputs(MultiFrameDataManager mfdManager, PvsVideoRunConfig config, Map<String, String> rowNameByObjectName) {
        DetectionUtils.OutputOptions options = config.getOutputOptions();
        if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
            RoiManager roiManager = getRoiManager();
            addTrackedRoisToManager(roiManager, mfdManager, options.addToRoiManagerBB, options.addToRoiManagerShapes, Detection3dUtils.GroupingMethod.BY_CLASS);
            roiManager.setVisible(true);
            roiManager.runCommand("Show All"); // Make ROIs visible
        }

        if (options.createInstanceMask) {
            ImagePlus stackMask = createInstanceMaskStackWithFixedIds(imp, mfdManager);
            if (stackMask != null) stackMask.show();
        }

        if (options.createSemanticMask) {
            ImagePlus semanticMask = DetectionUtils.createSemanticMaskStack(imp, mfdManager.getDetectionsByFrame());
            if (semanticMask != null) semanticMask.show();
        }

        if (options.createInstanceMaskPerClass) {
            // one channel per table row, in table order
            List<String> rowNames = objects.stream().map(PvsObject::getName).collect(Collectors.toList());
            ImagePlus instanceMaskPerClass = createInstanceMaskPerClassStackWithFixedIds(imp, mfdManager, rowNames, rowNameByObjectName);
            if (instanceMaskPerClass != null) instanceMaskPerClass.show();
        }
    }

    private void askUser() {
        PvsVideoDialog dialog = new PvsVideoDialog(imp, nFrames, axis);
        if (!dialog.showDialogAndWait()) return; // user canceled, or closed the window

        modelPath = dialog.getModelPath();
        modelParams = dialog.getFinalModelParams();
        outputOptions = dialog.getOutputOptions();
        objects = dialog.getObjects();
        chosenLastFrame = dialog.getChosenLastFrame();
    }

    private void parseMacro() {
        IJ.log("\nSAM PVS on macro");
        String options = Macro.getOptions();

        modelPath = Macro.getValue(options, "model_path", null);
        if (modelPath == null || modelPath.trim().isEmpty()) {
            IJ.log("No model path. Closing plug-in.\n");
            IJ.error("No model path specified.");
            return;
        }

        // one entry per object, index-aligned; "output_groups" defines the object count
        String outputGroupsStr = Macro.getValue(options, "output_groups", "");
        if (outputGroupsStr.trim().isEmpty()) {
            IJ.error("No object defined.", "Please provide at least one output group id (\"output_groups\").");
            return;
        }
        String[] outputGroupsArr = outputGroupsStr.split(",");
        String[] namesArr = splitAligned(Macro.getValue(options, "names", ""), outputGroupsArr.length);
        String[] positiveVisualArr = splitAligned(Macro.getValue(options, "positive_visual_groups", ""), outputGroupsArr.length);
        String[] negativeVisualArr = splitAligned(Macro.getValue(options, "negative_visual_groups", ""), outputGroupsArr.length);
        String[] modesArr = splitAligned(Macro.getValue(options, "modes", ""), outputGroupsArr.length);

        objects = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < outputGroupsArr.length; i++) {
            int outputGroup = Integer.parseInt(outputGroupsArr[i].trim());
            PvsObject object = new PvsObject(i + 1, outputGroup);

            object.setPositiveVisualGroup(parseOptionalInt(positiveVisualArr[i]));
            object.setNegativeVisualGroup(parseOptionalInt(negativeVisualArr[i]));
            object.setMode(parseMode(modesArr[i]));

            String requestedName = namesArr[i].trim();
            object.setName(uniqueName(requestedName.isEmpty() ? object.getName() : requestedName, usedNames));
            usedNames.add(object.getName());

            if (!object.hasAnyPositivePrompt()) {
                IJ.log("Warning: object " + object.getName() + " has no positive visual prompt - skipped.");
                continue;
            }
            if (!object.isNegativeGroupValid()) {
                IJ.log("Warning: object " + object.getName() + " uses the same group for positive and negative visual - negative prompt ignored.");
                object.setNegativeVisualGroup(null);
            }
            objects.add(object);
        }
        if (objects.isEmpty()) {
            IJ.error("No valid object defined.");
            return;
        }

        // frame bound (1-indexed), default: last frame of the video/stack
        chosenLastFrame = clampFrame(Integer.parseInt(Macro.getValue(options, "last_frame", String.valueOf(nFrames))));

        // tracking parameters (all "advanced")
        modelParams = new PvsModelParameters();
        double maskThreshold = Double.parseDouble(Macro.getValue(options, "mask_threshold", String.valueOf(modelParams.getMaskScoreThreshold())));
        modelParams.setMaskScoreThreshold(maskThreshold);

        int maxSideLength = Integer.parseInt(Macro.getValue(options, "max_side_length", String.valueOf(modelParams.getMaxSideLength())));
        modelParams.setMaxSideLength(Math.max(maxSideLength, 0));

        int nFramesInMemory = Integer.parseInt(Macro.getValue(options, "n_frames_in_memory", String.valueOf(modelParams.getNFramesInMemory())));
        if (nFramesInMemory <= 0) nFramesInMemory = modelParams.getNFramesInMemory();
        modelParams.setNFramesInMemory(nFramesInMemory);

        double trackingScoreThreshold = Double.parseDouble(Macro.getValue(options, "tracking_score_threshold", String.valueOf(modelParams.getTrackingScoreThreshold())));
        modelParams.setTrackingScoreThreshold(trackingScoreThreshold);

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

    /** Parses a {@link PvsObject.Mode} name (case-insensitive), defaulting to {@code OBJECT} when blank/unrecognized. */
    private PvsObject.Mode parseMode(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.equalsIgnoreCase("class")) return PvsObject.Mode.CLASS;
        return PvsObject.Mode.OBJECT;
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
        Recorder.setCommand("SAM PVS on Video");
        Recorder.recordOption("model_path", modelPath);

        String outputGroups = objects.stream().map(o -> String.valueOf(o.getOutputGroup())).collect(Collectors.joining(","));
        Recorder.recordOption("output_groups", outputGroups);

        String names = objects.stream().map(PvsObject::getName).collect(Collectors.joining(","));
        Recorder.recordOption("names", names);

        String positiveVisualGroups = objects.stream()
                .map(o -> o.isPositiveVisualUsed() ? String.valueOf(o.getPositiveVisualGroup()) : "")
                .collect(Collectors.joining(","));
        Recorder.recordOption("positive_visual_groups", positiveVisualGroups);

        String negativeVisualGroups = objects.stream()
                .map(o -> o.isNegativeVisualUsed() ? String.valueOf(o.getNegativeVisualGroup()) : "")
                .collect(Collectors.joining(","));
        Recorder.recordOption("negative_visual_groups", negativeVisualGroups);

        String modes = objects.stream().map(o -> o.getMode().name().toLowerCase()).collect(Collectors.joining(","));
        Recorder.recordOption("modes", modes);

        Recorder.recordOption("last_frame", String.valueOf(chosenLastFrame));

        Recorder.recordOption("mask_threshold", String.valueOf(modelParams.getMaskScoreThreshold()));
        Recorder.recordOption("max_side_length", String.valueOf(modelParams.getMaxSideLength()));
        Recorder.recordOption("n_frames_in_memory", String.valueOf(modelParams.getNFramesInMemory()));
        Recorder.recordOption("tracking_score_threshold", String.valueOf(modelParams.getTrackingScoreThreshold()));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_box_rois", String.valueOf(true));
        if (outputOptions.addToRoiManagerShapes) Recorder.recordOption("add_shape_rois", String.valueOf(true));
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask", String.valueOf(true));
        if (outputOptions.createSemanticMask) Recorder.recordOption("create_semantic_mask", String.valueOf(true));
        if (outputOptions.createInstanceMaskPerClass) Recorder.recordOption("create_instance_mask_per_class", String.valueOf(true));
    }

    /**
     * Resolves the table rows into the objects actually tracked, fills {@code framePrompts)
     * <p>
     * An {@link PvsObject.Mode#OBJECT} row resolves to exactly one tracked object: every usable
     * ROI in its positive group, on every processed frame, refines that same object
     * <p>
     * A {@link PvsObject.Mode#CLASS} row resolves to one tracked object per distinct ROI name
     * seen in its positive group, named {@code "<row name>-<n>"} in order of first appearance
     * (n = 1, 2, ...). A later ROI only refines one of these if it reuses that exact name - any
     * other name starts a new object.
     */
    private List<ResolvedObject> resolveObjects(Roi[] roisForRun, Map<Integer, Map<Integer, PromptRois>> framePrompts) {
        List<ResolvedObject> resolved = new ArrayList<>();
        for (PvsObject object : objects) {
            if (!object.isPositiveVisualUsed()) {
                resolved.add(new ResolvedObject(object));
            } else if (object.getMode() == PvsObject.Mode.CLASS) {
                resolveClassRow(object, roisForRun, resolved, framePrompts);
            } else {
                resolveObjectRow(object, roisForRun, resolved, framePrompts);
            }
        }
        return resolved;
    }

    private void resolveObjectRow(PvsObject object, Roi[] roisForRun, List<ResolvedObject> resolved,
                                   Map<Integer, Map<Integer, PromptRois>> framePrompts) {
        int objIndex = resolved.size();
        ResolvedObject resolvedObject = new ResolvedObject(object);
        resolved.add(resolvedObject);

        boolean tracked = false; // a negative-only prompt can only refine an object already prompted on an earlier frame
        for (int frame = 1; frame <= chosenLastFrame; frame++) {
            List<Roi> roisAtFrame = roiPromptExtractor.getRoisAtFrame(roisForRun, axis, frame);
            if (roisAtFrame.isEmpty()) continue;
            PromptRois promptRois = withoutNegativeMasks(roiPromptExtractor.buildPromptRois(
                    roisAtFrame, object.getPositiveVisualGroup(), object.getNegativeVisualGroup(), true));
            if (!promptRois.hasPositive() && (!tracked || !promptRois.hasNegative())) {
                if (promptRois.hasNegative()) IJ.log("warning: negative prompts for object " + object.getName() +" on frame "+ frame + " will be ignored." +
                        " (no positive prompt on any previous images)");
                continue;
            }
            tracked = true;
            framePrompts.computeIfAbsent(frame - 1, k -> new TreeMap<>()).put(objIndex, promptRois);
            resolvedObject.addPromptCounts(promptRois);
        }
    }

    private void resolveClassRow(PvsObject object, Roi[] roisForRun, List<ResolvedObject> resolved,
                                  Map<Integer, Map<Integer, PromptRois>> framePrompts) {
        Map<String, Integer> objIndexByRoiName = new HashMap<>();

        for (int frame = 1; frame <= chosenLastFrame; frame++) {
            List<Roi> roisAtFrame = roiPromptExtractor.getRoisAtFrame(roisForRun, axis, frame);
            if (roisAtFrame.isEmpty()) continue;

            // negative prompt is shared by every instance of this class on this frame, not matched by name
            PromptRois sharedNegatives = withoutNegativeMasks(roiPromptExtractor
                    .buildPromptRois(roisAtFrame, null, object.getNegativeVisualGroup(), true));

            for (Roi roi : roisAtFrame) {
                if (roi.getGroup() != object.getPositiveVisualGroup()) continue;
                PromptRois onlyThisRoi = roiPromptExtractor.buildPromptRois(
                        Collections.singletonList(roi), object.getPositiveVisualGroup(), null, true);
                if (!onlyThisRoi.hasPositive()) continue;

                String roiName = roi.getName();
                Integer objIndex = objIndexByRoiName.get(roiName); // if name is the exact same as a previously seen ROI, the prompts are added to the same object
                if (objIndex == null) { // if this is the first time this resolvedObject is seen
                    objIndex = resolved.size();
                    String instanceName = object.getName() + "-" + (objIndexByRoiName.size() + 1);
                    resolved.add(new ResolvedObject(instanceName, object));
                    objIndexByRoiName.put(roiName, objIndex);
                }
                // all resolvedObjects with a ROI on this frame produce a promptRoi, with the positive prompt + the shared negative prompts
                PromptRois promptRois = roiPromptExtractor.combinePromptRois(onlyThisRoi.getPositive(), sharedNegatives.getNegative(), onlyThisRoi.getPositiveMasks(), Collections.emptyList());
                framePrompts.computeIfAbsent(frame - 1, k -> new TreeMap<>()).put(objIndex, promptRois);
                resolved.get(objIndex).addPromptCounts(promptRois);
            }

            // negative-only refinement for every instance already tracked but without positive prompt on this frame
            if (!sharedNegatives.hasNegative()) continue;
            for (int objIndex : objIndexByRoiName.values()) { // only the resolvedObject already registered for this class
                Map<Integer, PromptRois> promptsAtFrame = framePrompts.computeIfAbsent(frame - 1, k -> new TreeMap<>());
                if (promptsAtFrame.containsKey(objIndex)) continue; // if object already on frame, its promptRois is already build with the shared negative prompts
                // for each object without a positive prompt on frame -> create a promptROI with the shared negative prompts (and empty positive prompt)
                PromptRois promptRois = roiPromptExtractor.combinePromptRois(Collections.emptyList(), sharedNegatives.getNegative(), Collections.emptyList(), Collections.emptyList());
                promptsAtFrame.put(objIndex, promptRois);
                resolved.get(objIndex).addPromptCounts(promptRois);
            }
            if (objIndexByRoiName.isEmpty()) IJ.log("warning: negative prompt(s) for class " + object.getName() +" on frame "+ frame + " will be ignored." +
                    " (no positive prompt on any previous images)");;
        }
    }

    /**
     * PVS negative prompts are points only: negative area ROIs other than rectangles (ellipse, freehand...)
     * are dropped here
     */
    private PromptRois withoutNegativeMasks(PromptRois promptRois) {
        return roiPromptExtractor.combinePromptRois(promptRois.getPositive(), promptRois.getNegative(),
                promptRois.getPositiveMasks(), Collections.emptyList());
    }

    private void printParameters(PvsVideoRunConfig config, List<ResolvedObject> resolved) {
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("model: " + config.getModelPath());
        IJ.log("number of frame(s) to process: " + config.getFrameCount()
                + " (frame " + (config.getFirstFrame() + 1) + " to " + (config.getLastFrame() + 1) + ")");
        IJ.log("number of object(s): " + resolved.size());
        for (int i = 0; i < resolved.size(); i++) {
            int objIndex = i;
            ResolvedObject object = resolved.get(objIndex);
            long promptFrameCount = config.getFramePrompts().values().stream().filter(m -> m.containsKey(objIndex)).count();
            StringBuilder line = new StringBuilder("  - ").append(object.name)
                    .append(" (object ").append(objIndex).append(") ")
                    .append("-> ").append(promptFrameCount).append(" prompt frame").append(promptFrameCount>1?"s":"");

            line.append(" - positive group: ").append(object.positiveGroup).append(" (")
                    .append(object.positivePromptCount).append(" roi").append(object.positivePromptCount > 1 ? "s)" : ")");
            if (object.negativeGroup != null && object.negativePromptCount>0) line.append(", negative group: ").append(object.negativeGroup).append(" (")
                    .append(object.negativePromptCount).append(" roi").append(object.negativePromptCount > 1 ? "s)" : ")");
            line.append(" -> output group: ").append(object.outputGroup);
            IJ.log(line.toString());

        }
        IJ.log("----------------------");
    }

    /**
     * One object actually tracked during a run: either
     * - a table row 1:1 (OBJECT mode, or a row with no positive prompt),
     * - or one of the several objects a CLASS-mode row expands into (one per distinct ROI name in its positive group).
     */
    private static final class ResolvedObject {
        final String name;
        final String rowName; // name of the table row this object was resolved from
        final int outputGroup;
        final Integer positiveGroup;
        final Integer negativeGroup;
        int positivePromptCount = 0;
        int negativePromptCount = 0;

        ResolvedObject(PvsObject source) {
            this(source.getName(), source);
        }

        ResolvedObject(String name, PvsObject source) {
            this(name, source.getName(), source.getOutputGroup(), source.getPositiveVisualGroup(), source.getNegativeVisualGroup());
        }

        ResolvedObject(String name, String rowName, int outputGroup, Integer positiveGroup, Integer negativeGroup) {
            this.name = name;
            this.rowName = rowName;
            this.outputGroup = outputGroup;
            this.positiveGroup = positiveGroup;
            this.negativeGroup = negativeGroup;
        }

        void addPromptCounts(PromptRois promptRois) {
            positivePromptCount += promptRois.positiveCount();
            negativePromptCount += promptRois.negativeCount();
        }
    }

}