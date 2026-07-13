package fr.curie.miclearning.apposeplugin;

import fr.curie.miclearning.tools.detection.DetectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a single SAM3 promptable-concept-segmentation run.
 * Built once (via {@link Builder}) after the user dialog (or macro parsing) completes,
 */
public final class Sam3RunConfig {

    private final String modelPath;

    private final String textPrompt;
    private final boolean textPromptUsed;

    private final List<double[]> positiveRois;   // never null; empty if unused
    private final List<double[]> negativeRois;   // never null; empty if unused
    private final boolean visualPositivePromptUsed;
    private final boolean negativePromptUsed;

    private final int startFrame; // 0-indexed, inclusive
    private final int endFrame;   // 0-indexed, inclusive

    private final Sam3Parameters detectionParams;
    private final DetectionUtils.OutputOptions outputOptions;
    private final Map<String, Integer> classIdMap;

    private Sam3RunConfig(Builder b) {
        this.modelPath = b.modelPath;
        this.textPrompt = b.textPrompt;
        this.textPromptUsed = b.textPromptUsed;
        this.positiveRois = Collections.unmodifiableList(new ArrayList<>(b.positiveRois));
        this.negativeRois = Collections.unmodifiableList(new ArrayList<>(b.negativeRois));
        this.visualPositivePromptUsed = b.visualPositivePromptUsed;
        this.negativePromptUsed = b.negativePromptUsed;
        this.startFrame = b.startFrame;
        this.endFrame = b.endFrame;
        this.detectionParams = b.detectionParams;
        this.outputOptions = b.outputOptions;
        this.classIdMap = Collections.unmodifiableMap(new HashMap<>(b.classIdMap));
    }

    public String getModelPath() { return modelPath; }
    public String getTextPrompt() { return textPrompt; }
    public boolean isTextPromptUsed() { return textPromptUsed; }
    public List<double[]> getPositiveRois() { return positiveRois; }
    public List<double[]> getNegativeRois() { return negativeRois; }
    public boolean isVisualPositivePromptUsed() { return visualPositivePromptUsed; }
    public boolean isNegativePromptUsed() { return negativePromptUsed; }
    public int getStartFrame() { return startFrame; }
    public int getEndFrame() { return endFrame; }
    public int getFrameCount() { return endFrame - startFrame + 1; }
    public Sam3Parameters getDetectionParams() { return detectionParams; }
    public DetectionUtils.OutputOptions getOutputOptions() { return outputOptions; }
    public Map<String, Integer> getClassIdMap() { return classIdMap; }

    public static final class Builder {
        private String modelPath;
        private String textPrompt = "visual";
        private boolean textPromptUsed;
        private List<double[]> positiveRois = new ArrayList<>();
        private List<double[]> negativeRois = new ArrayList<>();
        private boolean visualPositivePromptUsed;
        private boolean negativePromptUsed;
        private int startFrame;
        private int endFrame;
        private Sam3Parameters detectionParams;
        private DetectionUtils.OutputOptions outputOptions;
        private Map<String, Integer> classIdMap = new HashMap<>();

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

        public Builder frameRange(int startFrame, int endFrame) {
            this.startFrame = startFrame;
            this.endFrame = endFrame;
            return this;
        }

        public Builder detectionParams(Sam3Parameters detectionParams) {
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

        /**
         * Validates the configuration and builds the {@link Sam3RunConfig}.
         * @throws IllegalStateException if the configuration is incomplete or inconsistent
         */
        public Sam3RunConfig build() {
            Objects.requireNonNull(modelPath, "modelPath must be set");
            Objects.requireNonNull(detectionParams, "detectionParams must be set");
            Objects.requireNonNull(outputOptions, "outputOptions must be set");
            if (!textPromptUsed && !visualPositivePromptUsed) {
                throw new IllegalStateException(
                        "No positive prompt (neither text nor visual) provided.");
            }
            if (endFrame < startFrame) {
                throw new IllegalStateException(
                        "endFrame (" + endFrame + ") must be >= startFrame (" + startFrame + ")");
            }
            return new Sam3RunConfig(this);
        }
    }
}
