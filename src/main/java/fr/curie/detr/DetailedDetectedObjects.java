package fr.curie.detr;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Mask;

import java.util.List;


public class DetailedDetectedObjects extends DetectedObjects {
    private static final long serialVersionUID = 1L;
    private List<List<Float>> allScores;


    public DetailedDetectedObjects(List<String> classNames, List<Double> probabilities, List<BoundingBox> boundingBoxes,
                                   List<List<Float>> allScores) {
        super(classNames, probabilities, boundingBoxes);
        this.topK = Integer.MAX_VALUE;
        this.allScores = allScores;
    }

    public <T extends Classification> T item(int index) {
        return (T) (new DetailedDetectedObject(
                (DetectedObject) super.item(index),
                (List) allScores.get(index)));
    }


    public static class DetailedDetectedObject extends Classification {
        private BoundingBox boundingBox;
        private List<Float> allScore;

        public DetailedDetectedObject(String className, double probability, BoundingBox boundingBox,
                                      List<Float> allScore) {
            super(className, probability);
            this.boundingBox = boundingBox;
            this.allScore = allScore;
        }

        public DetailedDetectedObject(String className, double probability, BoundingBox boundingBox) {
            super(className, probability);
            this.boundingBox = boundingBox;
            this.allScore = null;
        }

        public DetailedDetectedObject(DetectedObject detectedObject, List<Float> allScore) {
            super(detectedObject.getClassName(), detectedObject.getProbability());
            this.boundingBox = detectedObject.getBoundingBox();
            this.allScore = allScore;
        }

        public BoundingBox getBoundingBox() {return this.boundingBox;}
        public List<Float> getAllScore() {return this.allScore;}

        public String toString() {
            double probability = this.getProbability();
            StringBuilder sb = new StringBuilder(200);
            sb.append("{\"className\": \"").append(this.getClassName()).append("\", \"probability\": ");
            if (probability < 1.0E-5) {
                sb.append(String.format("%.1e", probability));
            } else {
                probability = (double)((float)((int)(probability * (double)100000.0F)) / 100000.0F);
                sb.append(String.format("%.5f", probability));
            }

            if (this.boundingBox != null) {
                sb.append(", \"boundingBox\": x: ").append(this.boundingBox.getBounds().getX())
                        .append(" y: ").append(this.boundingBox.getBounds().getY())
                        .append(" width: ").append(this.boundingBox.getBounds().getHeight())
                        .append(" height: ").append(this.boundingBox.getBounds().getHeight());

                sb.append(", \"mask?\": ").append((this.boundingBox instanceof Mask));
            }

            if (this.allScore != null){
                sb.append(" \"allScores\": ").append(this.getAllScore());
            }

            sb.append('}');
            return sb.toString();
        }
    }
}
