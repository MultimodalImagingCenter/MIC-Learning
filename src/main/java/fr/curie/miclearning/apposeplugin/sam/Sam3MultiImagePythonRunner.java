package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.ApposeTaskRunner;
import fr.curie.miclearning.apposeplugin.MultiImageDetectionConsumer;
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
 * SAM3-multi-image specific wrapper: builds the shared-memory video buffer and the Appose inputs
 * map from a {@link Sam3VideoRunConfig}, then delegates environment/task lifecycle to * {@link ApposeTaskRunner}.
 */

public class Sam3MultiImagePythonRunner implements AutoCloseable {

    private final ApposeTaskRunner taskRunner;

    public Sam3MultiImagePythonRunner(String scriptResourcePath, String envTomlResourcePath) {
        this.taskRunner = new ApposeTaskRunner(scriptResourcePath, envTomlResourcePath);
    }

    public void initialize() throws IOException, BuildException {
        taskRunner.initialize();
    }

    /**
     * Runs the SAM3 multi-image script, blocking until it reaches a terminal state. See
     * {@link ApposeTaskRunner#runBlocking} for the completion-signal guarantee.
     */
    public void runBlocking(Sam3MultiImageRunConfig config, ImagePlus imp,
                            BlockingQueue<Map<String, Object>> resultsQueue)
            throws TaskException, InterruptedException {
        try (ShmImg<?> sharedImg = ApposeUtils.video2ShmImg(imp)) {
            Map<String, Object> inputs = buildInputs(config, sharedImg);
            taskRunner.runBlocking(inputs, MultiImageDetectionConsumer.END_SIGNAL, resultsQueue);
        }
    }

    /** Builds the Appose input map for one run. Package-visible for unit testing. */
    static Map<String, Object> buildInputs(Sam3MultiImageRunConfig config, ShmImg<?> sharedImg) {
        Sam3ModelParameters params = config.getDetectionParams();
        Map<String, Object> inputs = new HashMap<>();

        inputs.put("images_input", NDArrays.asNDArray(sharedImg));
        inputs.put("text_prompts", config.getTextPrompts());
        inputs.put("model_path", config.getModelPath());
        inputs.put("confidence_threshold", params.getConfidenceThreshold());
        inputs.put("mask_threshold", params.getMaskScoreThreshold());
        inputs.put("max_side_length", params.getMaxSideLengthDetect());

        return inputs;
    }

    @Override
    public void close() {
        taskRunner.close();
    }
}