package fr.curie.miclearning.apposeplugin.sampvs;

import fr.curie.miclearning.apposeplugin.PromptRois;
import fr.curie.miclearning.tools.detection.DetectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable description of a single promptable-visual-segmentation run over a video: an arbitrary
 * number of independent objects, each identified purely by visual prompts.
 * Prompts are resolved on every frame that has one, for every object. Tracking runs forward only,
 * from {@link #getFirstFrame()}  to {@link #getLastFrame()}.
 * Built once (via {@link Builder}) after the user dialog (or macro parsing) completes.
 */
public final class PvsVideoRunConfig {

    private final String modelPath;

    private final List<String> objectNames; // name given by user to 1 object - row order, index-aligned with the object index used in framePrompts
    private final Map<String, Integer> classIdMap; // object name -> output RoiManager group id

    // 0-indexed frame -> 0-indexed object index -> the prompt(s) defined on that frame for that object
    private final Map<Integer, Map<Integer, PromptRois>> framePrompts;

    private final int firstFrame; // 0-indexed, inclusive; earliest frame any object has a prompt on - processing starts here
    private final int lastFrame; // 0-indexed, inclusive

    private final PvsModelParameters modelParams;
    private final DetectionUtils.OutputOptions outputOptions;

    private PvsVideoRunConfig(Builder b) {
        this.modelPath = b.modelPath;
        this.objectNames = Collections.unmodifiableList(new ArrayList<>(b.objectNames));
        this.classIdMap = Collections.unmodifiableMap(new HashMap<>(b.classIdMap));
        this.framePrompts = Collections.unmodifiableMap(new TreeMap<>(b.framePrompts));
        this.firstFrame = b.firstFrame;
        this.lastFrame = b.lastFrame;
        this.modelParams = b.modelParams;
        this.outputOptions = b.outputOptions;
    }

    public String getModelPath() { return modelPath; }
    public List<String> getObjectNames() { return objectNames; }
    public Map<String, Integer> getClassIdMap() { return classIdMap; }
    public Map<Integer, Map<Integer, PromptRois>> getFramePrompts() { return framePrompts; }
    public int getFirstFrame() { return firstFrame; }
    public int getLastFrame() { return lastFrame; }
    public int getFrameCount() { return lastFrame - firstFrame + 1; }
    public PvsModelParameters getModelParams() { return modelParams; }
    public DetectionUtils.OutputOptions getOutputOptions() { return outputOptions; }

    public static final class Builder {
        private String modelPath;
        private List<String> objectNames = new ArrayList<>();
        private Map<String, Integer> classIdMap = new HashMap<>();
        private Map<Integer, Map<Integer, PromptRois>> framePrompts = new TreeMap<>();
        private int firstFrame;
        private int lastFrame;
        private PvsModelParameters modelParams;
        private DetectionUtils.OutputOptions outputOptions;

        public Builder modelPath(String modelPath) {
            this.modelPath = modelPath;
            return this;
        }

        public Builder objectNames(List<String> objectNames) {
            this.objectNames = objectNames != null ? objectNames : new ArrayList<>();
            return this;
        }

        public Builder classIdMap(Map<String, Integer> classIdMap) {
            this.classIdMap = classIdMap != null ? classIdMap : new HashMap<>();
            return this;
        }

        public Builder framePrompts(Map<Integer, Map<Integer, PromptRois>> framePrompts) {
            this.framePrompts = framePrompts != null ? framePrompts : new TreeMap<>();
            return this;
        }

        public Builder firstFrame(int firstFrame) {
            this.firstFrame = firstFrame;
            return this;
        }

        public Builder lastFrame(int lastFrame) {
            this.lastFrame = lastFrame;
            return this;
        }

        public Builder modelParams(PvsModelParameters modelParams) {
            this.modelParams = modelParams;
            return this;
        }

        public Builder outputOptions(DetectionUtils.OutputOptions outputOptions) {
            this.outputOptions = outputOptions;
            return this;
        }

        /**
         * Validates the configuration and builds the {@link PvsVideoRunConfig}.
         * @throws IllegalStateException if the configuration is incomplete or inconsistent
         */
        public PvsVideoRunConfig build() {
            Objects.requireNonNull(modelPath, "modelPath must be set");
            Objects.requireNonNull(modelParams, "modelParams must be set");
            Objects.requireNonNull(outputOptions, "outputOptions must be set");
            if (objectNames.isEmpty()) {
                throw new IllegalStateException("At least one object must be provided.");
            }
            if (firstFrame < 0) {
                throw new IllegalStateException("firstFrame (" + firstFrame + ") must be >= 0");
            }
            if (lastFrame < firstFrame) {
                throw new IllegalStateException("lastFrame (" + lastFrame + ") must be >= firstFrame (" + firstFrame + ")");
            }
            boolean anyUsablePrompt = framePrompts.values().stream()
                    .flatMap(perObject -> perObject.values().stream())
                    .anyMatch(prompt -> prompt.hasPositive());
            if (!anyUsablePrompt) {
                throw new IllegalStateException("No positive visual prompt provided for any object.");
            }
            return new PvsVideoRunConfig(this);
        }
    }
}