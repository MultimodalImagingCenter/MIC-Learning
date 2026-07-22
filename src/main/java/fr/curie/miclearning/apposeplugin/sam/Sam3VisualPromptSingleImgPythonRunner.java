package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.ApposeTaskRunner;
import fr.curie.miclearning.tools.appose.ApposeUtils;
import ij.ImagePlus;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * SAM3 visual-prompt specific wrapper: builds the shared-memory image buffer and the
 * appose inputs map from a {@link Sam3VisualPromptSingleImgRunConfig}, then delegates the
 * environment/task lifecycle to {@link ApposeTaskRunner}.
 */
public class Sam3VisualPromptSingleImgPythonRunner implements AutoCloseable {

    private final ApposeTaskRunner taskRunner;

    public Sam3VisualPromptSingleImgPythonRunner(String scriptResourcePath, String envTomlResourcePath) {
        this.taskRunner = new ApposeTaskRunner(scriptResourcePath, envTomlResourcePath);
    }

    public void initialize() throws IOException, BuildException {
        taskRunner.initialize();
    }

    /**
     * Runs the SAM3 geometric-prompt script, blocking until it completes, and returns the
     * task's raw outputs map (results_number, boxes, masks, scores, group_ids)
     */
    public Map<String, Object> runAndGetOutputs(Sam3VisualPromptSingleImgRunConfig config, ImagePlus imp)
            throws TaskException, InterruptedException, IOException {
        try (ShmImg<?> sharedImg = ApposeUtils.imp2ShmImg(imp)) {
            Map<String, Object> inputs = buildInputs(config, sharedImg);
            return taskRunner.runAndGetOutputs(inputs);
        }
    }

    /** Builds the Appose input map for one run */
    static Map<String, Object> buildInputs(Sam3VisualPromptSingleImgRunConfig config, ShmImg<?> sharedImg) {
        Sam3ModelParameters params = config.getDetectionParams();
        Map<String, Object> inputs = new HashMap<>();

        inputs.put("image_input", NDArrays.asNDArray(sharedImg));
        inputs.put("model_path", config.getModelPath());
        inputs.put("confidence_threshold", params.getConfidenceThreshold());
        inputs.put("mask_threshold", params.getMaskScoreThreshold());
        inputs.put("max_side_length", params.getMaxSideLengthDetect());
        inputs.put("positive_rois", config.getPositiveRois());
        inputs.put("negative_rois", config.getNegativeRois());

        return inputs;
    }

    @Override
    public void close() {
        taskRunner.close();
    }
}