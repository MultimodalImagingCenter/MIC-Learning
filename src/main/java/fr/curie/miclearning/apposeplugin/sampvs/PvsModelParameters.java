package fr.curie.miclearning.apposeplugin.sampvs;

/**
 * The parameters for promptable visual segmentation
 */
public class PvsModelParameters {
    // Default values
    public static final double DEFAULT_MASK_SCORE_THRESHOLD = 0.0;
    public static final int DEFAULT_MAX_SIDE_LENGTH = 0; // 0 or less -> no limit (python None) i.e. default value (1024 for sam2, 1008 for sam3)
    public static final int DEFAULT_N_FRAMES_IN_MEMORY = 6; // matches muggled_sam's own SAMVideoMemoryBank default
    public static final double DEFAULT_TRACKING_SCORE_THRESHOLD = 0.0;

    private double maskScoreThreshold = DEFAULT_MASK_SCORE_THRESHOLD; // min score (between -inf and +inf, centered on 0) to add pixel to binary mask
    private int maxSideLength = DEFAULT_MAX_SIDE_LENGTH; // masks are downsized to at most this side length (pixels); <=0 = no limit
    private int nFramesInMemory = DEFAULT_N_FRAMES_IN_MEMORY; // number of recent frame encodings kept in memory per tracked object
    private double trackingScoreThreshold = DEFAULT_TRACKING_SCORE_THRESHOLD; // min presence score to keep an already-tracked object

    public PvsModelParameters() {
    }

    public double getMaskScoreThreshold() {return maskScoreThreshold;}
    public int getMaxSideLength() {return maxSideLength;}
    public int getNFramesInMemory() {return nFramesInMemory;}
    public double getTrackingScoreThreshold() {return trackingScoreThreshold;}

    public void setMaskScoreThreshold(double maskScoreThreshold) {this.maskScoreThreshold = maskScoreThreshold;}
    public void setMaxSideLength(int maxSideLength) {this.maxSideLength = maxSideLength;}
    public void setNFramesInMemory(int nFramesInMemory) {this.nFramesInMemory = nFramesInMemory;}
    public void setTrackingScoreThreshold(double trackingScoreThreshold) {this.trackingScoreThreshold = trackingScoreThreshold;}
}