package fr.curie.miclearning.apposeplugin;

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
 * SAM3-video specific wrapper: builds the shared-memory video buffer and the Appose inputs
 * map from a {@link Sam3VideoRunConfig}, then delegates environment/task lifecycle to * {@link ApposeTaskRunner}.
 */
public class Sam3VideoPythonRunner implements AutoCloseable {

    private final ApposeTaskRunner taskRunner;

    public Sam3VideoPythonRunner(String scriptResourcePath, String envTomlResourcePath) {
        this.taskRunner = new ApposeTaskRunner(scriptResourcePath, envTomlResourcePath);
    }

    public void initialize() throws IOException, BuildException {
        taskRunner.initialize();
    }

    /**
     * Runs the SAM3 video script, blocking until it reaches a terminal state.
     */
    public void runBlocking(Sam3VideoRunConfig config, ImagePlus imp, BlockingQueue<Map<String, Object>> resultsQueue)
            throws TaskException, InterruptedException {
        try (ShmImg<?> sharedVideo = ApposeUtils.video2ShmImg(imp, config.getPromptFrame(), config.getEndFrame())) {
            Map<String, Object> inputs = buildInputs(config, sharedVideo);
            taskRunner.runBlocking(inputs, VideoDetectionConsumer.END_SIGNAL, resultsQueue);
        }
    }

    /** Builds the Appose input map for one run.*/
    static Map<String, Object> buildInputs(Sam3VideoRunConfig config, ShmImg<?> sharedVideo) {
        Sam3ModelParameters params = config.getDetectionParams();
        Map<String, Object> inputs = new HashMap<>();

        inputs.put("video_input", NDArrays.asNDArray(sharedVideo));
        inputs.put("detection_model_path", config.getModelPath());
        inputs.put("tracking_model_path", null); // TODO: wire up once a separate tracking model is supported

        inputs.put("detectionScoreThreshold", params.getConfidenceThreshold());
        inputs.put("trackingScoreThreshold", params.getTrackingScoreThreshold());
        inputs.put("maskThreshold", params.getMaskScoreThreshold());
        inputs.put("detectEveryNFrames", params.getNFrameBtwDetections());
        inputs.put("removeAfterNMissed", params.getRemoveAfterNMissed());
        inputs.put("maxSideLengthDetect", params.getMaxSideLengthDetect());
        inputs.put("maxSideLengthTrack", params.getMaxSideLengthTrack());

        inputs.put("nFrameToProcess", params.getNFrameToProcess()); // = lasFrameIndex - firstFrameIndex +1
        inputs.put("bidirectional", config.isBidirectional());
        inputs.put("promptFrame", config.getPromptFrame() - config.getFirstFrame());

        inputs.put("text_prompt", config.getTextPrompt());
        inputs.put("positive_rois", config.getPositiveRois());
        inputs.put("negative_rois", config.getNegativeRois());

        inputs.put("frame_offset", config.getFirstFrame()); // informational only, used for logging on the python side

        return inputs;
    }

    @Override
    public void close() {
        taskRunner.close();
    }
}
