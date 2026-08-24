package fr.curie.miclearning.apposeplugin.sam;

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
 * SAM3-cross-image specific wrapper: builds the shared-memory video buffer and the Appose inputs
 * map from a {@link Sam3CrossImageRunConfig}, then delegates environment/task lifecycle to a {@link ApposeTaskRunner}.
 */

public class Sam3CrossImagePythonRunner implements AutoCloseable {

    private final ApposeTaskRunner taskRunner;
    
    public Sam3CrossImagePythonRunner(String scriptResourcePath, String envTomlResourcePath) {
        this.taskRunner = new ApposeTaskRunner(scriptResourcePath, envTomlResourcePath);
    }

    public void initialize() throws IOException, BuildException {
        taskRunner.initialize();
    }

    /**
     * Runs the SAM3 cross-image script, blocking until it reaches a terminal state. See
     * {@link ApposeTaskRunner#runBlocking} for the completion-signal guarantee.
     *
     * @param refImp    single-frame image the prompt (text and/or visual) is encoded on
     * @param targetImp image (or image stack) promptable-concept-segmentation is run on
     */
    public void runBlocking(Sam3CrossImageRunConfig config, ImagePlus refImp, ImagePlus targetImp,
                            BlockingQueue<Map<String, Object>> resultsQueue)
            throws TaskException, InterruptedException, IOException {
        try (ShmImg<?> refShmImg = ApposeUtils.video2ShmImg(refImp);
             ShmImg<?> targetShmImg = ApposeUtils.video2ShmImg(targetImp)) {
            Map<String, Object> inputs = buildInputs(config, refShmImg, targetShmImg);
            taskRunner.runBlocking(inputs, MultiImagePcsResultsConsumer.END_SIGNAL, resultsQueue);
        }
    }

    /** Builds the Appose input map for one run */
    static Map<String, Object> buildInputs(Sam3CrossImageRunConfig config, ShmImg<?> refShmImg, ShmImg<?> targetShmImg) {
        Sam3ModelParameters params = config.getDetectionParams();
        Map<String, Object> inputs = new HashMap<>();

        inputs.put("refImage", NDArrays.asNDArray(refShmImg));
        inputs.put("targetImages", NDArrays.asNDArray(targetShmImg));
        inputs.put("modelPath", config.getModelPath());

        inputs.put("detectionScoreThreshold", params.getConfidenceThreshold());
        inputs.put("maskThreshold", params.getMaskScoreThreshold());
        inputs.put("maxSideLength", params.getMaxSideLengthDetect());
        inputs.put("includeCoordinateEncoding", params.isIncludeCoordinateEncoding());

        inputs.put("textPrompt", config.getTextPrompt());
        inputs.put("positiveRois", config.getPositiveRois());
        inputs.put("negativeRois", config.getNegativeRois());

        return inputs;
    }

    @Override
    public void close() {
        taskRunner.close();
    }

}
