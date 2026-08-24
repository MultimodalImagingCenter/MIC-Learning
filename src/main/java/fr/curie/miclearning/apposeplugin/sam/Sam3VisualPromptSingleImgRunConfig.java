package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.tools.detection.DetectionUtils;

import java.util.*;

/**
 * Immutable description of a single SAM3 promptable-concept-segmentation run over one
 * image, with geometric (box/point) visual prompts grouped by ROI group id, plus one
 * optional negative-prompt group.
 */
public final class Sam3VisualPromptSingleImgRunConfig {
    private final String modelPath;
    private final Sam3ModelParameters detectionParams;
    private final DetectionUtils.OutputOptions outputOptions;
    private final Map<String, Integer> classIdMap;
    private final Map<Integer, String> idClassMap;
    private final Map<Integer, List<double[]>> positiveRois; // key is ROI group id
    private final List<double[]> negativeRois;

    private Sam3VisualPromptSingleImgRunConfig(Builder b) {
        this.modelPath = b.modelPath;
        this.detectionParams = b.detectionParams;
        this.outputOptions = b.outputOptions;
        this.classIdMap = Collections.unmodifiableMap(new HashMap<>(b.classIdMap));
        this.idClassMap = Collections.unmodifiableMap(new HashMap<>(b.idClassMap));

        Map<Integer, List<double[]>> positiveCopy = new HashMap<>();
        for (Map.Entry<Integer, List<double[]>> entry : b.positiveRois.entrySet()) {
            positiveCopy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.positiveRois = Collections.unmodifiableMap(positiveCopy);
        this.negativeRois = Collections.unmodifiableList(new ArrayList<>(b.negativeRois));
    }

    public String getModelPath() { return modelPath; }
    public Sam3ModelParameters getDetectionParams() { return detectionParams; }
    public DetectionUtils.OutputOptions getOutputOptions() { return outputOptions; }
    public Map<String, Integer> getClassIdMap() { return classIdMap; }
    public Map<Integer, String> getIdClassMap() { return idClassMap; }
    public Map<Integer, List<double[]>> getPositiveRois() { return positiveRois; }
    public List<double[]> getNegativeRois() { return negativeRois; }

    public static final class Builder {
        private String modelPath;
        private Sam3ModelParameters detectionParams;
        private DetectionUtils.OutputOptions outputOptions;
        private Map<String, Integer> classIdMap = new HashMap<>();
        private Map<Integer, String> idClassMap = new HashMap<>();
        private Map<Integer, List<double[]>> positiveRois = new HashMap<>();
        private List<double[]> negativeRois = new ArrayList<>();

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

        public Builder classIdMap(Map<String, Integer> classIdMap) {
            this.classIdMap = classIdMap != null ? classIdMap : new HashMap<>();
            return this;
        }

        public Builder idClassMap(Map<Integer, String> idClassMap) {
            this.idClassMap = idClassMap != null ? idClassMap : new HashMap<>();
            return this;
        }

        public Builder positiveRois(Map<Integer, List<double[]>> positiveRois) {
            this.positiveRois = positiveRois != null ? positiveRois : new HashMap<>();
            return this;
        }

        public Builder negativeRois(List<double[]> negativeRois) {
            this.negativeRois = negativeRois != null ? negativeRois : new ArrayList<>();
            return this;
        }

        public Sam3VisualPromptSingleImgRunConfig build() {
            Objects.requireNonNull(modelPath, "modelPath must be set");
            Objects.requireNonNull(detectionParams, "detectionParams must be set");
            Objects.requireNonNull(outputOptions, "outputOptions must be set");
            if (positiveRois.isEmpty()) {
                throw new IllegalStateException("At least one positive prompt ROI must be provided.");
            }
            return new Sam3VisualPromptSingleImgRunConfig(this);
        }
    }
}
