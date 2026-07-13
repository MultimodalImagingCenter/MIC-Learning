package fr.curie.miclearning.apposeplugin;

import fr.curie.miclearning.tools.appose.ApposeUtils;
import ij.IJ;
import ij.ImagePlus;
import net.imglib2.appose.NDArrays;
import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.TaskException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Manage the Appose lifecycle for running the SAM3
 * python script against a single {@link Sam3RunConfig}.
 */
public class Sam3PythonRunner implements AutoCloseable {

    private final String scriptResourcePath;
    private final String envTomlResourcePath;
    private Environment environment;

    public Sam3PythonRunner(String scriptResourcePath, String envTomlResourcePath) {
        this.scriptResourcePath = scriptResourcePath;
        this.envTomlResourcePath = envTomlResourcePath;
    }

    /**
     * Loads the python script and pixi environment definition and builds the Appose
     * environment. Must be called once before {@link #runBlocking}.
     *
     * @throws IOException    if the script or environment resource cannot be read
     * @throws BuildException if the pixi environment fails to build
     */
    public void initialize() throws IOException, BuildException {
        String envTomlContent = ApposeUtils.getResourceAsString(envTomlResourcePath);
        if (envTomlContent == null) {
            throw new IOException("Unable to load environment resource: " + envTomlResourcePath);
        }
        this.environment = Appose.pixi()
                .content(envTomlContent)
                .build();
        IJ.log("Python environment built");
    }

    /**
     * Runs the SAM3 script for the given configuration. Per-frame results are pushed
     * onto {@code resultsQueue} as they arrive; a sentinel ({@link DetectionResultConsumer#END_SIGNAL})
     * is always pushed exactly once at the end,
     *
     * @param config      the run configuration (model path, prompts, frame range, parameters)
     * @param imp         the source image
     * @param resultsQueue destination queue for per-frame results
     *
     * @throws IllegalStateException if {@link #initialize()} was not called first
     * @throws TaskException         if the Appose task itself throws while starting/waiting
     * @throws InterruptedException  if the calling thread is interrupted while waiting for the task
     */
    public void runBlocking(Sam3RunConfig config, ImagePlus imp,
                            BlockingQueue<Map<String, Object>> resultsQueue)
            throws TaskException, InterruptedException {
        if (environment == null) {
            throw new IllegalStateException("Sam3PythonRunner.initialize() must be called before runBlocking()");
        }

        String script = ApposeUtils.getResourceAsString(scriptResourcePath);
        if (script == null) {
            IJ.error("Unable to load resource", "Unable to load script: " + scriptResourcePath);
            resultsQueue.offer(DetectionResultConsumer.END_SIGNAL);
            return;
        }
        IJ.log("Python script loaded");

        try (ShmImg<?> sharedVideo = ApposeUtils.video2ShmImg(imp, config.getStartFrame(), config.getEndFrame())) {
            try (Service python = environment.python()) {

                Map<String, Object> inputs = buildInputs(config, sharedVideo, config.getStartFrame());
                Service.Task task = python.task(script, inputs);

                task.listen(event -> {
                    switch (event.responseType) {
                        case LAUNCH:
                            System.out.println("Python task launched");
                            break;
                        case UPDATE:
                            if (event.message != null && !event.message.isEmpty()) {
                                IJ.log("   " + event.message);
                                if (event.current > 0 && event.maximum >0)
                                    System.out.println(" python task: " + event.current + "/" + event.maximum);
                                Map<String, Object> info = event.info;
                                if (info != null) {
                                    resultsQueue.offer(info);
                                }
                            }
                            break;
                        case CRASH:
                            System.out.println("Python task crashed : " + task.error);
                            IJ.log("Python task crashed: " + task.error);
                            IJ.error("Python task crashed", String.valueOf(task.error));
                            resultsQueue.offer(DetectionResultConsumer.END_SIGNAL);
                            break;
                        case FAILURE:
                            System.out.println("Python task failed with error: " + task.error);
                            IJ.log("Python task failed: " + task.error);
                            IJ.error("Python task failed", String.valueOf(task.error));
                            resultsQueue.offer(DetectionResultConsumer.END_SIGNAL);
                            break;
                        case COMPLETION:
                            System.out.println("Python task completed successfully");
                            resultsQueue.offer(DetectionResultConsumer.END_SIGNAL);
                            break;
                        case CANCELATION:
                            System.out.println("Python task canceled");
                            resultsQueue.offer(DetectionResultConsumer.END_SIGNAL);
                            break;
                        default:
                            break;
                    }
                });

                IJ.log("Executing python script...");
                task.waitFor();
            }
        }
    }

    /** Builds the Appose input map for one run.*/
    static Map<String, Object> buildInputs(Sam3RunConfig config, ShmImg<?> sharedVideo, int frameOffset) {
        Sam3Parameters params = config.getDetectionParams();
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
        inputs.put("max_frame_number", params.getNFrame());

        inputs.put("text_prompt", config.getTextPrompt());
        inputs.put("positive_rois", config.getPositiveRois());
        inputs.put("negative_rois", config.getNegativeRois());

        inputs.put("frame_offset", frameOffset); // informational only, used for logging on the python side

        return inputs;
    }

    @Override
    public void close() {
        // Service and ShmImg are already closed per-run via try-with-resources above.
        environment = null;
    }
}
