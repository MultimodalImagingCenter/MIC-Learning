package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.tools.detection.DetectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a single SAM3 promptable-concept-segmentation run over a video, with
 * an arbitrary number of independent concepts - each with an optional text prompt and/or an
 * optional positive/negative visual prompt. Unlike the image plugin, prompts are resolved once,
 * from a single prompt frame (not per processed frame).
 * Built once (via {@link Builder}) after the user dialog (or macro parsing) completes,
 */
public final class Sam3VideoRunConfig {

    private final String detectionModelPath;
    private final String trackingModelPath;

    // frame-invariant, index-aligned across all five
    private final List<String> conceptLabels;
    private final List<String> conceptTexts; // real text prompt, or "visual" placeholder when unused
    private final List<Boolean> conceptTextUsed;
    private final List<List<double[]>> conceptPositiveRois; // one rois list per concept, resolved from the prompt frame
    private final List<List<double[]>> conceptNegativeRois;
    private final Map<String, Integer> classIdMap; // concept label -> output RoiManager group id

    private final int promptFrame; // index of the frame of the original image where the prompt are defined, where the sampcs starts 0-indexed, inclusive
    private final int endFrame;   // index of the frame of te original image  0-indexed, inclusive
    private final boolean bidirectional;
    private final int firstFrame; // index frame to go back to if bidirectional, firstFrame = startFrame otherwise

    private final Sam3ModelParameters detectionParams;
    private final DetectionUtils.OutputOptions outputOptions;

    private Sam3VideoRunConfig(Builder b) {
        this.detectionModelPath = b.detectionModelPath;
        this.trackingModelPath = b.trackingModelPath;
        this.conceptLabels = Collections.unmodifiableList(new ArrayList<>(b.conceptLabels));
        this.conceptTexts = Collections.unmodifiableList(new ArrayList<>(b.conceptTexts));
        this.conceptTextUsed = Collections.unmodifiableList(new ArrayList<>(b.conceptTextUsed));
        this.conceptPositiveRois = Collections.unmodifiableList(new ArrayList<>(b.conceptPositiveRois));
        this.conceptNegativeRois = Collections.unmodifiableList(new ArrayList<>(b.conceptNegativeRois));
        this.classIdMap = Collections.unmodifiableMap(new HashMap<>(b.classIdMap));
        this.promptFrame = b.promptFrame;
        this.endFrame = b.endFrame;
        this.bidirectional = b.bidirectional;
        this.firstFrame = b.firstFrame;
        this.detectionParams = b.detectionParams;
        this.outputOptions = b.outputOptions;
    }

    public String getDetectionModelPath() { return detectionModelPath; }
    public String getTrackingModelPath() { return trackingModelPath; }
    public List<String> getConceptLabels() { return conceptLabels; }
    public List<String> getConceptTexts() { return conceptTexts; }
    public List<Boolean> getConceptTextUsed() { return conceptTextUsed; }
    public List<List<double[]>> getConceptPositiveRois() { return conceptPositiveRois; }
    public List<List<double[]>> getConceptNegativeRois() { return conceptNegativeRois; }
    public Map<String, Integer> getClassIdMap() { return classIdMap; }
    public int getPromptFrame() { return promptFrame; }
    public int getEndFrame() { return endFrame; }
    public int getFrameCount() { return endFrame - firstFrame + 1; }
    public boolean isBidirectional() {return bidirectional; }
    public int getFirstFrame() {return firstFrame; }
    public Sam3ModelParameters getDetectionParams() { return detectionParams; }
    public DetectionUtils.OutputOptions getOutputOptions() { return outputOptions; }

    public static final class Builder {
        private String detectionModelPath;
        private String trackingModelPath;
        private List<String> conceptLabels = new ArrayList<>();
        private List<String> conceptTexts = new ArrayList<>();
        private List<Boolean> conceptTextUsed = new ArrayList<>();
        private List<List<double[]>> conceptPositiveRois = new ArrayList<>();
        private List<List<double[]>> conceptNegativeRois = new ArrayList<>();
        private Map<String, Integer> classIdMap = new HashMap<>();
        private int firstFrame;
        private int promptFrame;
        private int endFrame;
        private boolean bidirectional;
        private Sam3ModelParameters detectionParams;
        private DetectionUtils.OutputOptions outputOptions;

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

        public Builder conceptPositiveRois(List<List<double[]>> conceptPositiveRois) {
            this.conceptPositiveRois = conceptPositiveRois != null ? conceptPositiveRois : new ArrayList<>();
            return this;
        }

        public Builder conceptNegativeRois(List<List<double[]>> conceptNegativeRois) {
            this.conceptNegativeRois = conceptNegativeRois != null ? conceptNegativeRois : new ArrayList<>();
            return this;
        }

        public Builder classIdMap(Map<String, Integer> classIdMap) {
            this.classIdMap = classIdMap != null ? classIdMap : new HashMap<>();
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

        /**
         * Validates the configuration and builds the {@link Sam3VideoRunConfig}.
         * @throws IllegalStateException if the configuration is incomplete or inconsistent
         */
        public Sam3VideoRunConfig build() {
            Objects.requireNonNull(detectionModelPath, "modelPath must be set");
            Objects.requireNonNull(detectionParams, "detectionParams must be set");
            Objects.requireNonNull(outputOptions, "outputOptions must be set");
            if (conceptLabels.isEmpty()) {
                throw new IllegalStateException("At least one concept must be provided.");
            }
            boolean anyUsablePrompt = false;
            for (int i = 0; i < conceptLabels.size(); i++) {
                boolean hasPositiveVisual = i < conceptPositiveRois.size() && !conceptPositiveRois.get(i).isEmpty();
                boolean textUsed = i < conceptTextUsed.size() && conceptTextUsed.get(i);
                if (textUsed || hasPositiveVisual) {
                    anyUsablePrompt = true;
                    break;
                }
            }
            if (!anyUsablePrompt) {
                throw new IllegalStateException(
                        "No positive prompt (neither text nor visual) provided for any concept.");
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