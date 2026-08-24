package fr.curie.miclearning.apposeplugin.sam;

public class Sam3ModelParameters {
    // Default values
    public static final double DEFAULT_CONFIDENCE = 0.5;
    public static final double DEFAULT_MASK_THRESHOLD = 0.0;
    public static final boolean DEFAULT_INCLUDE_COORDINATE_ENCODING = true; // false  will generally degrade performance, but may be useful for cross-image for example
    public static final int DEFAULT_FRAME_BTW_DETECT = 2;
    public static final int DEFAULT_FRAME_IN_MEMORY = 6;
    public static final double DEFAULT_TRACK_SCORE_THRESHOLD = 2;
    public static final double DEFAULT_BOX_IOU_THRESHOLD = 0.15;
    public static final int DEFAULT_REMOVE_AFTER_MISSING = 5;
    public static final int DEFAULT_MAX_SIDE_LENGTH_DETECT = 1008;
    public static final int DEFAULT_MAX_SIDE_LENGTH_TRACK = 1008; // Reduce this to increase speed at the cost of mask quality
    public static final int N_FRAME_TO_PROCESS = -1; // number of frame to process (not the number of frame in original image)

    // for image and video
    private double confidenceThreshold =DEFAULT_CONFIDENCE; // min probability (between 0 and 1) to add new detection
    private double maskScoreThreshold = DEFAULT_MASK_THRESHOLD; // min score (between -inf and +inf, centered on 0) to add pixel to binary mask

    //only useful for cross-detection
    private boolean includeCoordinateEncoding = DEFAULT_INCLUDE_COORDINATE_ENCODING;
    // for video/tracking
    private int nFrameBtwDetections = DEFAULT_FRAME_BTW_DETECT;
    private int nFrameInMemory = DEFAULT_FRAME_IN_MEMORY; // number of frames in memory for each object (only the last frames where the object was detected)
    private double trackingScoreThreshold = DEFAULT_TRACK_SCORE_THRESHOLD; // min presence score to keep an object already existing
    private double trackingBoxIouThreshold = DEFAULT_BOX_IOU_THRESHOLD; // iou threshold to consider 2 objects as the same object (on 2 consecutive frames)
    private int removeAfterNMissed = DEFAULT_REMOVE_AFTER_MISSING; // if an object is not detected for x frames, it is removed from memory
    private int maxSideLengthDetect = DEFAULT_MAX_SIDE_LENGTH_DETECT;
    private int maxSideLengthTrack = DEFAULT_MAX_SIDE_LENGTH_TRACK;
    private int nFrameToProcess = N_FRAME_TO_PROCESS;


    public Sam3ModelParameters() {
    }

    public double getConfidenceThreshold() {return confidenceThreshold;}
    public double getMaskScoreThreshold() {return maskScoreThreshold;}
    public boolean isIncludeCoordinateEncoding() {return includeCoordinateEncoding;}
    public int getNFrameBtwDetections() {return nFrameBtwDetections;}
    public int getNFrameInMemory() {return nFrameInMemory;}
    public double getTrackingScoreThreshold() {return trackingScoreThreshold;}
    public double getTrackingBoxIouThreshold() {return trackingBoxIouThreshold;}
    public int getRemoveAfterNMissed() {return removeAfterNMissed;}
    public int getMaxSideLengthDetect() {return maxSideLengthDetect;}
    public int getMaxSideLengthTrack() {return maxSideLengthTrack;}
    public int getNFrameToProcess() {return nFrameToProcess;}

    public void setConfidenceThreshold(double confidenceThreshold) {this.confidenceThreshold = confidenceThreshold;}
    public void setMaskScoreThreshold(double maskScoreThreshold) {this.maskScoreThreshold = maskScoreThreshold;}
    public void setIncludeCoordinateEncoding(boolean includeCoordinateEncoding) {this.includeCoordinateEncoding = includeCoordinateEncoding;}
    public void setNFrameBtwDetections(int nFrameBtwDetections) {this.nFrameBtwDetections = nFrameBtwDetections;}
    public void setNFrameInMemory(int nFrameInMemory) {this.nFrameInMemory = nFrameInMemory;}
    public void setTrackingScoreThreshold(double trackingScoreThreshold) {this.trackingScoreThreshold = trackingScoreThreshold;}
    public void setTrackingBoxIouThreshold(double trackingBoxIouThreshold) {this.trackingBoxIouThreshold = trackingBoxIouThreshold;}
    public void setRemoveAfterNMissed(int removeAfterNMissed) {this.removeAfterNMissed = removeAfterNMissed;}
    public void setMaxSideLengthDetect(int maxSideLengthDetect) {this.maxSideLengthDetect = maxSideLengthDetect;}
    public void setMaxSideLengthTrack(int maxSideLengthTrack) {this.maxSideLengthTrack = maxSideLengthTrack;}
    public void setNFrameToProcess(int nFrame) {this.nFrameToProcess = nFrame;}

}
