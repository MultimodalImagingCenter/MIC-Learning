package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.tools.detection.DetectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a single SAM3 promptable-concept-segmentation run over an
 * image stack with multiple independent text prompts (no ROI-based visual prompts, no
 * cross-frame tracking - each frame's detections are independent).
 */
public final class Sam3TextPromptPcsMultiImgRunConfig {

    private final String modelPath;
    private final List<String> textPrompts;
    private final Map<String, Integer> classIdMap;
    private final Sam3ModelParameters detectionParams;
    private final DetectionUtils.OutputOptions outputOptions;
    private final DetectionUtils.DetectionMode stackMode;

    private Sam3TextPromptPcsMultiImgRunConfig(Builder b) {
        this.modelPath = b.modelPath;
        this.textPrompts = Collections.unmodifiableList(new ArrayList<>(b.textPrompts));
        this.classIdMap = Collections.unmodifiableMap(new HashMap<>(b.classIdMap));
        this.detectionParams = b.detectionParams;
        this.outputOptions = b.outputOptions;
        this.stackMode = b.stackMode;
    }

    public String getModelPath() { return modelPath; }
    public List<String> getTextPrompts() { return textPrompts; }
    public Map<String, Integer> getClassIdMap() { return classIdMap; }
    public Sam3ModelParameters getDetectionParams() { return detectionParams; }
    public DetectionUtils.OutputOptions getOutputOptions() { return outputOptions; }
    public DetectionUtils.DetectionMode getStackMode() { return stackMode; }

    public static final class Builder {
        private String modelPath;
        private List<String> textPrompts = new ArrayList<>();
        private Map<String, Integer> classIdMap = new HashMap<>();
        private Sam3ModelParameters detectionParams;
        private DetectionUtils.OutputOptions outputOptions;
        private DetectionUtils.DetectionMode stackMode;

        public Builder modelPath(String modelPath) {
            this.modelPath = modelPath;
            return this;
        }

        public Builder textPrompts(List<String> textPrompts) {
            this.textPrompts = textPrompts != null ? textPrompts : new ArrayList<>();
            return this;
        }

        public Builder classIdMap(Map<String, Integer> classIdMap) {
            this.classIdMap = classIdMap != null ? classIdMap : new HashMap<>();
            return this;
        }

        public Builder detectionParams(Sam3ModelParameters detectionParams) {
            this.detectionParams = detectionParams;
            return this;
        }

        public Builder outputOptions(DetectionUtils.OutputOptions outputOptions) {
            this.outputOptions = outputOptions;
            return this;
        }

        public Builder stackMode(DetectionUtils.DetectionMode stackMode) {
            this.stackMode = stackMode;
            return this;
        }

        public Sam3TextPromptPcsMultiImgRunConfig build() {
            Objects.requireNonNull(modelPath, "modelPath must be set");
            Objects.requireNonNull(detectionParams, "detectionParams must be set");
            Objects.requireNonNull(outputOptions, "outputOptions must be set");
            Objects.requireNonNull(stackMode, "stackMode must be set");
            if (textPrompts.isEmpty() || classIdMap.isEmpty()) {
                throw new IllegalStateException("At least one text prompt with a class id must be provided.");
            }
            return new Sam3TextPromptPcsMultiImgRunConfig(this);
        }
    }
}
