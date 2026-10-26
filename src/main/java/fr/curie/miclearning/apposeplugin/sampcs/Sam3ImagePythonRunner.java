package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.ApposeTaskRunner;
import fr.curie.miclearning.apposeplugin.MultiImagePcsResultsConsumer;
import fr.curie.miclearning.apposeplugin.PromptRois;
import fr.curie.miclearning.tools.appose.ApposeUtils;
import ij.ImagePlus;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;

/**
 * SAM3-concepts specific wrapper: builds the shared-memory image-stack buffer and the Appose
 * inputs map from a {@link Sam3ImageRunConfig}, then delegates environment/task lifecycle
 * to a {@link ApposeTaskRunner}.
 */
public class Sam3ImagePythonRunner implements AutoCloseable {

    private final ApposeTaskRunner taskRunner;

    public Sam3ImagePythonRunner(String scriptResourcePath, String envTomlResourcePath) {
        this.taskRunner = new ApposeTaskRunner(scriptResourcePath, envTomlResourcePath);
    }

    public void initialize() throws IOException, BuildException {
        taskRunner.initialize();
    }

    /**
     * Runs the SAM3 concepts script, blocking until it reaches a terminal state. See
     * {@link ApposeTaskRunner#runBlocking} for the completion-signal guarantee.
     */
    public void runBlocking(Sam3ImageRunConfig config, ImagePlus imp,
                            BlockingQueue<Map<String, Object>> resultsQueue)
            throws TaskException, InterruptedException, IOException {
        try (ShmImg<?> sharedImg = ApposeUtils.video2ShmImg(imp)) {
            Map<String, Object> inputs = buildInputs(config, sharedImg);
            taskRunner.runBlocking(inputs, MultiImagePcsResultsConsumer.END_SIGNAL, resultsQueue);
        }
    }

    /** Builds the Appose input map for one run */
    static Map<String, Object> buildInputs(Sam3ImageRunConfig config, ShmImg<?> sharedImg) {
        Sam3ModelParameters params = config.getDetectionParams();
        Map<String, Object> inputs = new HashMap<>();

        inputs.put("imagesInput", NDArrays.asNDArray(sharedImg));
        inputs.put("modelPath", config.getModelPath());
        inputs.put("confidenceThreshold", params.getConfidenceThreshold());
        inputs.put("maskThreshold", params.getMaskScoreThreshold());
        inputs.put("maxSideLength", params.getMaxSideLengthDetect());
        inputs.put("conceptLabels", config.getConceptLabels());
        inputs.put("conceptTexts", config.getConceptTexts());
        inputs.put("conceptTextUsed", config.getConceptTextUsed());
        inputs.put("framePrompts", buildFramePromptsInput(config));

        return inputs;
    }

    /**
     * Flattens the  frame -> concept -> promptRois list structure into pure
     * String-keyed map Appose can send to Python
     */
    private static List<List<Map<String, Object>>> buildFramePromptsInput(Sam3ImageRunConfig config) {
        List<List<Map<String, Object>>> framePromptsInput = new ArrayList<>(); // one entry per frame, one inner entry per concept
        // for each frame
        for (List<PromptRois> frameList : config.getFramePrompts()) {
            List<Map<String, Object>> perConcept = new ArrayList<>(); // for this frame only, one entry per concept
            // for each object
            for (PromptRois promptRois : frameList) {
                Map<String, Object> promptMap = new LinkedHashMap<>(); // map of all prompt for this object, for this frame
                promptMap.put("positive_rois", promptRois.getPositive());
                promptMap.put("negative_rois", promptRois.getNegative()); // no masks: area ROIs are sent as bounding boxes for PCS
                perConcept.add(promptMap);
            }
            framePromptsInput.add(perConcept);
        }
        return framePromptsInput;
    }

    @Override
    public void close() {
        taskRunner.close();
    }
}