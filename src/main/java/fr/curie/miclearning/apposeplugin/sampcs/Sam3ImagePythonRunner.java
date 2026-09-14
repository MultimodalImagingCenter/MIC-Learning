package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.ApposeTaskRunner;
import fr.curie.miclearning.apposeplugin.MultiImagePcsResultsConsumer;
import fr.curie.miclearning.tools.appose.ApposeUtils;
import ij.ImagePlus;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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

        inputs.put("images_input", NDArrays.asNDArray(sharedImg));
        inputs.put("model_path", config.getModelPath());
        inputs.put("confidence_threshold", params.getConfidenceThreshold());
        inputs.put("mask_threshold", params.getMaskScoreThreshold());
        inputs.put("max_side_length", params.getMaxSideLengthDetect());
        inputs.put("concept_labels", config.getConceptLabels());
        inputs.put("concept_texts", config.getConceptTexts());
        inputs.put("concept_text_used", config.getConceptTextUsed());
        inputs.put("frame_prompts", config.getFramePrompts());

        return inputs;
    }

    @Override
    public void close() {
        taskRunner.close();
    }
}