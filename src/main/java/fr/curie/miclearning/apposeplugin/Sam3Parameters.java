package fr.curie.miclearning.apposeplugin;

public class Sam3Parameters {
    // Default values
    public static final double DEFAULT_CONFIDENCE = 0.5;
    public static final double DEFAULT_MASK_THRESHOLD = 0.0;
    public static final int DEFAULT_FRAME_BTW_DETECT = 2;
    public static final int DEFAULT_FRAME_IN_MEMORY = 6;
    public static final double DEFAULT_TRACK_SCORE_THRESHOLD = 2;
    public static final double DEFAULT_BOX_IOU_THRESHOLD = 0.15;
    public static final int DEFAULT_REMOVE_AFTER_MISSING = 5;
    public static final int DEFAULT_MAX_SIDE_LENGTH_DETECT = 1008;
    public static final int DEFAULT_MAX_SIDE_LENGTH_TRACK = 1008; // Reduce this to increase speed at the cost of mask quality
    public static final int N_FRAME = -1; // number of frame to process
    // for image and video
    private double confidenceThreshold =DEFAULT_CONFIDENCE; // min probability (between 0 and 1) to add new detection
    private double maskScoreThreshold = DEFAULT_MASK_THRESHOLD; // min score (between -inf and +inf, centered on 0) to add pixel to binary mask

    // for video/tracking
    private int nFrameBtwDetections = DEFAULT_FRAME_BTW_DETECT;
    private int nFrameInMemory = DEFAULT_FRAME_IN_MEMORY; // number of frames in memory for each object (only the last frames where the object was detected)
    private double trackingScoreThreshold = DEFAULT_TRACK_SCORE_THRESHOLD; // min presence score to keep an object already existing
    private double existingBoxIouThreshold = DEFAULT_BOX_IOU_THRESHOLD; // iou threshold to consider 2 objects as the same object
    private int removeAfterNMissed = DEFAULT_REMOVE_AFTER_MISSING; // if an object is not detected for x frames, it is removed from memory
    private int maxSideLengthDetect = DEFAULT_MAX_SIDE_LENGTH_DETECT;
    private int maxSideLengthTrack = DEFAULT_MAX_SIDE_LENGTH_TRACK;
    private int nFrame = N_FRAME;

    // for image
    public Sam3Parameters() {
    }

    public double getConfidenceThreshold() {return confidenceThreshold;}
    public double getMaskScoreThreshold() {return maskScoreThreshold;}
    public int getNFrameBtwDetections() {return nFrameBtwDetections;}
    public int getNFrameInMemory() {return nFrameInMemory;}
    public double getTrackingScoreThreshold() {return trackingScoreThreshold;}
    public double getExistingBoxIouThreshold() {return existingBoxIouThreshold;}
    public int getRemoveAfterNMissed() {return removeAfterNMissed;}
    public int getMaxSideLengthDetect() {return maxSideLengthDetect;}
    public int getMaxSideLengthTrack() {return maxSideLengthTrack;}
    public int getNFrame() {return nFrame;}

    public void setConfidenceThreshold(double confidenceThreshold) {this.confidenceThreshold = confidenceThreshold;}
    public void setMaskScoreThreshold(double maskScoreThreshold) {this.maskScoreThreshold = maskScoreThreshold;}
    public void setNFrameBtwDetections(int nFrameBtwDetections) {this.nFrameBtwDetections = nFrameBtwDetections;}
    public void setNFrameInMemory(int nFrameInMemory) {this.nFrameInMemory = nFrameInMemory;}
    public void setTrackingScoreThreshold(double trackingScoreThreshold) {this.trackingScoreThreshold = trackingScoreThreshold;}
    public void setExistingBoxIouThreshold(double existingBoxIouThreshold) {this.existingBoxIouThreshold = existingBoxIouThreshold;}
    public void setRemoveAfterNMissed(int removeAfterNMissed) {this.removeAfterNMissed = removeAfterNMissed;}
    public void setMaxSideLengthDetect(int maxSideLengthDetect) {this.maxSideLengthDetect = maxSideLengthDetect;}
    public void setMaxSideLengthTrack(int maxSideLengthTrack) {this.maxSideLengthTrack = maxSideLengthTrack;}
    public void setNFrame(int nFrame) {this.nFrame = nFrame;}
}
