package fr.curie.miclearning.apposeplugin.sam;

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
public final class Sam3VideoRunConfig {

    private final String detectionModelPath;
    private final String trackingModelPath;

    private final String textPrompt;
    private final boolean textPromptUsed;

    private final List<double[]> positiveRois;   // never null; empty if unused
    private final List<double[]> negativeRois;   // never null; empty if unused
    private final boolean visualPositivePromptUsed;
    private final boolean negativePromptUsed;

    private final int promptFrame; // index of the frame of the original image where the prompt are defined, where the pcs starts 0-indexed, inclusive
    private final int endFrame;   // index of the frame of te original image  0-indexed, inclusive
    private final boolean bidirectional;
    private final int firstFrame; // index frame to go back to if bidirectional, firstFrame = startFrame otherwise

    private final Sam3ModelParameters detectionParams;
    private final DetectionUtils.OutputOptions outputOptions;
    private final Map<String, Integer> classIdMap;

    private Sam3VideoRunConfig(Builder b) {
        this.detectionModelPath = b.detectionModelPath;
        this.trackingModelPath = b.trackingModelPath;
        this.textPrompt = b.textPrompt;
        this.textPromptUsed = b.textPromptUsed;
        this.positiveRois = Collections.unmodifiableList(new ArrayList<>(b.positiveRois));
        this.negativeRois = Collections.unmodifiableList(new ArrayList<>(b.negativeRois));
        this.visualPositivePromptUsed = b.visualPositivePromptUsed;
        this.negativePromptUsed = b.negativePromptUsed;
        this.promptFrame = b.promptFrame;
        this.endFrame = b.endFrame;
        this.bidirectional = b.bidirectional;
        this.firstFrame = b.firstFrame;
        this.detectionParams = b.detectionParams;
        this.outputOptions = b.outputOptions;
        this.classIdMap = Collections.unmodifiableMap(new HashMap<>(b.classIdMap));

    }

    public String getDetectionModelPath() { return detectionModelPath; }
    public String getTrackingModelPath() { return trackingModelPath; }
    public String getTextPrompt() { return textPrompt; }
    public boolean isTextPromptUsed() { return textPromptUsed; }
    public List<double[]> getPositiveRois() { return positiveRois; }
    public List<double[]> getNegativeRois() { return negativeRois; }
    public boolean isVisualPositivePromptUsed() { return visualPositivePromptUsed; }
    public boolean isNegativePromptUsed() { return negativePromptUsed; }
    public int getPromptFrame() { return promptFrame; }
    public int getEndFrame() { return endFrame; }
    public int getFrameCount() { return endFrame - firstFrame + 1; }
    public boolean isBidirectional() {return bidirectional; }
    public int getFirstFrame() {return firstFrame; }
    public Sam3ModelParameters getDetectionParams() { return detectionParams; }
    public DetectionUtils.OutputOptions getOutputOptions() { return outputOptions; }
    public Map<String, Integer> getClassIdMap() { return classIdMap; }

    public static final class Builder {
        private String detectionModelPath;
        private String trackingModelPath;
        private String textPrompt = "visual";
        private boolean textPromptUsed;
        private List<double[]> positiveRois = new ArrayList<>();
        private List<double[]> negativeRois = new ArrayList<>();
        private boolean visualPositivePromptUsed;
        private boolean negativePromptUsed;
        private int firstFrame;
        private int promptFrame;
        private int endFrame;
        private boolean bidirectional;
        private Sam3ModelParameters detectionParams;
        private DetectionUtils.OutputOptions outputOptions;
        private Map<String, Integer> classIdMap = new HashMap<>();

        public Builder modelPath(String modelPath) {
            this.detectionModelPath = modelPath;
            this.trackingModelPath = modelPath;
            return this;
        }

        public Builder modelPath(String detectionModelPath, String trackingModelPath) {
            this.detectionModelPath = detectionModelPath;
            this.trackingModelPath = (trackingModelPath == null || trackingModelPath.trim().isEmpty() || trackingModelPath.equals(detectionModelPath))? detectionModelPath : trackingModelPath;
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

        public Builder frameRange(int promptFrame, int endFrame) {
            this.promptFrame = promptFrame;
            this.endFrame = endFrame;
            this.bidirectional = false;
            this.firstFrame = promptFrame;
            return this;
        }

        public Builder frameRange(int firstFrame, int promptFrame, int endFrame) {
            this.promptFrame = promptFrame;
            this.endFrame = endFrame;
            this.bidirectional = firstFrame < promptFrame;
            this.firstFrame = firstFrame;
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

        /**
         * Validates the configuration and builds the {@link Sam3VideoRunConfig}.
         * @throws IllegalStateException if the configuration is incomplete or inconsistent
         */
        public Sam3VideoRunConfig build() {
            Objects.requireNonNull(detectionModelPath, "modelPath must be set");
            Objects.requireNonNull(detectionParams, "detectionParams must be set");
            Objects.requireNonNull(outputOptions, "outputOptions must be set");
            if (!textPromptUsed && !visualPositivePromptUsed) {
                throw new IllegalStateException(
                        "No positive prompt (neither text nor visual) provided.");
            }
            if (endFrame < promptFrame) {
                throw new IllegalStateException(
                        "endFrame (" + endFrame + ") must be >= startFrame (" + promptFrame + ")");
            }
            if (promptFrame < firstFrame) {
                throw new IllegalStateException(
                        "startFrame (" + endFrame + ") must be >= firstFrame (" + promptFrame + ")");
            }
            return new Sam3VideoRunConfig(this);
        }
    }
}
