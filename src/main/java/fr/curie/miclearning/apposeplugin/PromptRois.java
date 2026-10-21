package fr.curie.miclearning.apposeplugin;

import java.util.Collections;
import java.util.List;

/** Positive/negative ROI coordinates (boxes, points and polygon masks) ready to be sent to the Python side. */
public final class PromptRois {
    private final List<double[]> positive; // boxes [x, y, w, h] and points [x, y] (pixels)
    private final List<double[]> negative;
    private final List<double[]> positiveMasks; // polygon outlines, one flat [x0, y0, x1, y1, ...] array per mask ROI (pixels)
    private final List<double[]> negativeMasks;

    PromptRois(List<double[]> positive, List<double[]> negative, List<double[]> positiveMasks, List<double[]> negativeMasks) {
        this.positive = Collections.unmodifiableList(positive);
        this.negative = Collections.unmodifiableList(negative);
        this.positiveMasks = Collections.unmodifiableList(positiveMasks);
        this.negativeMasks = Collections.unmodifiableList(negativeMasks);
    }

    PromptRois(List<double[]> positive, List<double[]> negative) {
        this.positive = Collections.unmodifiableList(positive);
        this.negative = Collections.unmodifiableList(negative);
        this.positiveMasks = Collections.emptyList();
        this.negativeMasks = Collections.emptyList();
    }

    public List<double[]> getPositive() { return positive; }
    public List<double[]> getNegative() { return negative; }
    public List<double[]> getPositiveMasks() {return positiveMasks;}
    public List<double[]> getNegativeMasks() {return negativeMasks;}

    public boolean hasPositive(){
        return !positive.isEmpty() || !positiveMasks.isEmpty();
    }

    public boolean hasNegative(){
        return !negative.isEmpty() || !negativeMasks.isEmpty();
    }

    public int positiveCount(){
        return positive.size() + positiveMasks.size();
    }

    public int negativeCount(){
        return negative.size() + negativeMasks.size();
    }
}
