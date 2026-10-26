package fr.curie.miclearning.apposeplugin.sampvs;

import fr.curie.miclearning.apposeplugin.ApposeTaskRunner;
import fr.curie.miclearning.apposeplugin.PromptRois;
import fr.curie.miclearning.tools.appose.ApposeUtils;
import ij.ImagePlus;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * SAM-PVS-video specific wrapper: builds the shared-memory video buffer and the Appose inputs map
 * from a {@link PvsVideoRunConfig}, then delegates environment/task lifecycle to {@link ApposeTaskRunner}.
 */
public class PvsVideoPythonRunner implements AutoCloseable {

    private final ApposeTaskRunner taskRunner;

    public PvsVideoPythonRunner(String scriptResourcePath, String envTomlResourcePath) {
        this.taskRunner = new ApposeTaskRunner(scriptResourcePath, envTomlResourcePath);
    }

    public void initialize() throws IOException, BuildException {
        taskRunner.initialize();
    }

    /**
     * Converts the image into a shared-memory array ({@link PvsVideoRunConfig#getFirstFrame()}
     * {@link PvsVideoRunConfig#getLastFrame()}
     * then runs the SAM PVS video script, blocking until it reaches a terminal state.
     */
    public void runBlocking(PvsVideoRunConfig config, ImagePlus imp, BlockingQueue<Map<String, Object>> resultsQueue)
            throws TaskException, InterruptedException, IOException {
        try (ShmImg<?> sharedVideo = ApposeUtils.video2ShmImg(imp, config.getFirstFrame(), config.getLastFrame())) {
            Map<String, Object> inputs = buildInputs(config, sharedVideo);
            taskRunner.runBlocking(inputs, PvsVideoResultsConsumer.END_SIGNAL, resultsQueue);
        }
    }

    /** Builds the Appose input map for one run. */
    static Map<String, Object> buildInputs(PvsVideoRunConfig config, ShmImg<?> sharedVideo) {
        PvsModelParameters params = config.getModelParams();
        Map<String, Object> inputs = new HashMap<>();

        inputs.put("videoInput", NDArrays.asNDArray(sharedVideo));
        inputs.put("modelPath", config.getModelPath());

        inputs.put("maskScoreThreshold", params.getMaskScoreThreshold());
        inputs.put("maxSideLength", params.getMaxSideLength());
        inputs.put("nFramesInMemory", params.getNFramesInMemory());
        inputs.put("trackingScoreThreshold", params.getTrackingScoreThreshold());

        inputs.put("framePrompts", buildFramePromptsInput(config));
        inputs.put("frameOffset", config.getFirstFrame()); // informational: used by python to report back absolute frame indices

        return inputs;
    }

    /**
     * Flattens the  frame -> object-index -> promptRois structure into pure
     * String-keyed map Appose can send to Python
     * Frame keys are reindexed relative to {@link PvsVideoRunConfig#getFirstFrame()}
     */
    private static Map<String, Object> buildFramePromptsInput(PvsVideoRunConfig config) {
        Map<String, Object> framePromptsInput = new LinkedHashMap<>();
        // for each frame
        for (Map.Entry<Integer, Map<Integer, PromptRois>> frameEntry : config.getFramePrompts().entrySet()) {
            Map<String, Object> perObject = new LinkedHashMap<>(); // map of all prompts, of all objects, on this frame
            // for each object
            for (Map.Entry<Integer, PromptRois> objEntry : frameEntry.getValue().entrySet()) {
                PromptRois promptRois = objEntry.getValue();
                Map<String, Object> promptMap = new LinkedHashMap<>(); // map of all prompt for this object, for this frame
                promptMap.put("positive_rois", promptRois.getPositive());
                promptMap.put("negative_rois", promptRois.getNegative());
                promptMap.put("positive_masks", promptRois.getPositiveMasks());
                promptMap.put("negative_masks", promptRois.getNegativeMasks());
                perObject.put(String.valueOf(objEntry.getKey()), promptMap);
            }
            int relativeFrame = frameEntry.getKey() - config.getFirstFrame();
            framePromptsInput.put(String.valueOf(relativeFrame), perObject);
        }
        return framePromptsInput;
    }

    @Override
    public void close() {
        taskRunner.close();
    }
}