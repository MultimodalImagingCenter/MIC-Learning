package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.tools.detection.DetectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a single SAM3 promptable-concept-segmentation run over an image
 * (or image stack), with an arbitrary number of independent concepts - each with an optional
 * text prompt and/or an optional positive/negative visual prompt, resolved per frame.
 */
public final class Sam3ImageRunConfig {

    private final String modelPath;
    private final Sam3ModelParameters detectionParams;
    private final DetectionUtils.OutputOptions outputOptions;
    private final DetectionUtils.DetectionMode stackMode;

    // frame-invariant, index-aligned across all three
    private final List<String> conceptLabels;
    private final List<String> conceptTexts; // real text prompt, or "visual" placeholder when unused
    private final List<Boolean> conceptTextUsed;
    private final Map<String, Integer> classIdMap; // concept label -> output RoiManager group id (may repeat across concepts)

    // one entry per frame, one inner entry per concept :
    // {"positive_rois": List<double[]>, "negative_rois": List<double[]>}
    private final List<List<Map<String, List<double[]>>>> framePrompts;

    private Sam3ImageRunConfig(Builder b) {
        this.modelPath = b.modelPath;
        this.detectionParams = b.detectionParams;
        this.outputOptions = b.outputOptions;
        this.stackMode = b.stackMode;
        this.conceptLabels = Collections.unmodifiableList(new ArrayList<>(b.conceptLabels));
        this.conceptTexts = Collections.unmodifiableList(new ArrayList<>(b.conceptTexts));
        this.conceptTextUsed = Collections.unmodifiableList(new ArrayList<>(b.conceptTextUsed));
        this.classIdMap = Collections.unmodifiableMap(new HashMap<>(b.classIdMap));
        this.framePrompts = Collections.unmodifiableList(new ArrayList<>(b.framePrompts));
    }

    public String getModelPath() {return modelPath;}
    public Sam3ModelParameters getDetectionParams() {return detectionParams;}
    public DetectionUtils.OutputOptions getOutputOptions() {return outputOptions;}
    public DetectionUtils.DetectionMode getStackMode() {return stackMode;}
    public List<String> getConceptLabels() {return conceptLabels;}
    public List<String> getConceptTexts() {return conceptTexts;}
    public List<Boolean> getConceptTextUsed() {return conceptTextUsed;}
    public Map<String, Integer> getClassIdMap() {return classIdMap;}
    public List<List<Map<String, List<double[]>>>> getFramePrompts() {return framePrompts;}

    public static final class Builder {
        private String modelPath;
        private Sam3ModelParameters detectionParams;
        private DetectionUtils.OutputOptions outputOptions;
        private DetectionUtils.DetectionMode stackMode;
        private List<String> conceptLabels = new ArrayList<>();
        private List<String> conceptTexts = new ArrayList<>();
        private List<Boolean> conceptTextUsed = new ArrayList<>();
        private Map<String, Integer> classIdMap = new HashMap<>();
        private List<List<Map<String, List<double[]>>>> framePrompts = new ArrayList<>();

        public Builder modelPath(String modelPath) {
            this.modelPath = modelPath;
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

        public Builder conceptLabels(List<String> conceptLabels) {
            this.conceptLabels = conceptLabels != null ? conceptLabels : new ArrayList<>();
            return this;
        }

        public Builder conceptTexts(List<String> conceptTexts) {
            this.conceptTexts = conceptTexts != null ? conceptTexts : new ArrayList<>();
            return this;
        }

        public Builder conceptTextUsed(List<Boolean> conceptTextUsed) {
            this.conceptTextUsed = conceptTextUsed != null ? conceptTextUsed : new ArrayList<>();
            return this;
        }

        public Builder classIdMap(Map<String, Integer> classIdMap) {
            this.classIdMap = classIdMap != null ? classIdMap : new HashMap<>();
            return this;
        }

        public Builder framePrompts(List<List<Map<String, List<double[]>>>> framePrompts) {
            this.framePrompts = framePrompts != null ? framePrompts : new ArrayList<>();
            return this;
        }

        public Sam3ImageRunConfig build() {
            Objects.requireNonNull(modelPath, "modelPath must be set");
            Objects.requireNonNull(detectionParams, "detectionParams must be set");
            Objects.requireNonNull(outputOptions, "outputOptions must be set");
            Objects.requireNonNull(stackMode, "stackMode must be set");
            if (conceptLabels.isEmpty() || classIdMap.isEmpty()) {
                throw new IllegalStateException("At least one concept must be provided.");
            }
            if (framePrompts.isEmpty()) {
                throw new IllegalStateException("At least one frame of prompts must be provided.");
            }
            return new Sam3ImageRunConfig(this);
        }
    }
}