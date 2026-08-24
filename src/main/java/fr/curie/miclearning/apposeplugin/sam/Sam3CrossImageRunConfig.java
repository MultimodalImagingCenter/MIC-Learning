package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.tools.detection.DetectionUtils;

import java.util.*;

/**
 * Immutable description of a single SAM3 promptable-concept-segmentation run over 
 * one or multiple image, with geometric (box/point) visual prompts and text prompts
 */
public final class Sam3CrossImageRunConfig {
    private final String modelPath;

    private final String textPrompt;
    private final boolean textPromptUsed;
    
    private final List<double[]> positiveRois;
    private final List<double[]> negativeRois;
    private final boolean visualPositivePromptUsed;
    private final boolean negativePromptUsed;
    
    private final Sam3ModelParameters detectionParams;
    private final DetectionUtils.OutputOptions outputOptions;
    private final Map<String, Integer> classIdMap;
    private final DetectionUtils.DetectionMode stackMode;


    private Sam3CrossImageRunConfig (Builder b) {
        this.modelPath = b.modelPath;

        this.textPrompt = b.textPrompt;
        this.textPromptUsed = b.textPromptUsed;
        this.positiveRois = Collections.unmodifiableList(new ArrayList<>(b.positiveRois));
        this.negativeRois = Collections.unmodifiableList(new ArrayList<>(b.negativeRois));
        this.visualPositivePromptUsed = b.visualPositivePromptUsed;
        this.negativePromptUsed = b.negativePromptUsed;

        this.detectionParams = b.detectionParams;
        this.outputOptions = b.outputOptions;
        this.classIdMap = Collections.unmodifiableMap(new HashMap<>(b.classIdMap));
        this.stackMode = b.stackMode;
    }

    public String getModelPath() { return modelPath; }
    public String getTextPrompt() { return textPrompt; }
    public boolean isTextPromptUsed() { return textPromptUsed; }
    public List<double[]> getPositiveRois() { return positiveRois; }
    public List<double[]> getNegativeRois() { return negativeRois; }
    public boolean isVisualPositivePromptUsed() { return visualPositivePromptUsed; }
    public boolean isNegativePromptUsed() { return negativePromptUsed; }
    public Sam3ModelParameters getDetectionParams() { return detectionParams; }
    public DetectionUtils.OutputOptions getOutputOptions() { return outputOptions; }
    public Map<String, Integer> getClassIdMap() { return classIdMap; }
    public DetectionUtils.DetectionMode getStackMode() { return stackMode; }

    public static final class Builder {
        private String modelPath;
        private String textPrompt = "visual";
        private boolean textPromptUsed;
        private List<double[]> positiveRois = new ArrayList<>();
        private List<double[]> negativeRois = new ArrayList<>();
        private boolean visualPositivePromptUsed;
        private boolean negativePromptUsed;
        private Sam3ModelParameters detectionParams;
        private DetectionUtils.OutputOptions outputOptions;
        private Map<String, Integer> classIdMap = new HashMap<>();
        private DetectionUtils.DetectionMode stackMode;

        public Builder modelPath(String modelPath) {
            this.modelPath = modelPath;
            return this;
        }
        
        public Builder textPrompt(String textPrompt, boolean used) {
            this.textPrompt = textPrompt;
            this.textPromptUsed = used;
            return this;
        }

        public Builder visualPrompts(List<double[]> positiveRois, List<double[]> negativeRois,
                                     boolean positiveUsed, boolean negativeUsed) {
            this.positiveRois = positiveRois != null ? positiveRois : new ArrayList<>();
            this.negativeRois = negativeRois != null ? negativeRois : new ArrayList<>();
            this.visualPositivePromptUsed = positiveUsed;
            this.negativePromptUsed = negativeUsed;
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

        public Builder stackMode(DetectionUtils.DetectionMode stackMode) {
            this.stackMode = stackMode;
            return this;
        }

        public Sam3CrossImageRunConfig build() {
            Objects.requireNonNull(modelPath, "modelPath must be set");
            Objects.requireNonNull(detectionParams, "detectionParams must be set");
            Objects.requireNonNull(outputOptions, "outputOptions must be set");
            Objects.requireNonNull(stackMode, "stackMode must be set");
            if (!textPromptUsed && !visualPositivePromptUsed) {
                throw new IllegalStateException(
                        "No positive prompt (neither text nor visual) provided.");
            }
            return new Sam3CrossImageRunConfig(this);
        }
    }
}
