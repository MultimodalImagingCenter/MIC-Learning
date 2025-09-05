package fr.curie.tiling;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Mask;

import java.util.List;

public class TiledDetectedObjects extends DetectedObjects {
    private static final long serialVersionUID = 1L;
    private List<TileParameter> tileParameters;


    public TiledDetectedObjects(List<String> classNames, List<Double> probabilities, List<BoundingBox> boundingBoxes,
                                List<TileParameter> tileParameters) {
        super(classNames, probabilities, boundingBoxes);
        this.topK = Integer.MAX_VALUE;
        this.tileParameters = tileParameters;
    }

    public <T extends Classification> T item(int index) {
        return (T) (new TiledDetectedObject(
                (DetectedObject) super.item(index),
                (TileParameter) tileParameters.get(index)));
    }


    public static class TiledDetectedObject extends Classification {
        private BoundingBox boundingBox;
        private TileParameter tileParameter;

        public TiledDetectedObject(String className, double probability, BoundingBox boundingBox,
                                   TileParameter tileParameter) {
            super(className, probability);
            this.boundingBox = boundingBox;
            this.tileParameter = tileParameter;
        }

        public TiledDetectedObject(String className, double probability, BoundingBox boundingBox,
                                   int x_offset, int y_offset,
                                   int tile_width, int tile_height) {
            super(className, probability);
            this.boundingBox = boundingBox;
            this.tileParameter = new TileParameter(x_offset, y_offset, tile_width, tile_height);
        }

        public TiledDetectedObject(String className, double probability, BoundingBox boundingBox) {
            super(className, probability);
            this.boundingBox = boundingBox;
            this.tileParameter = null;
        }

        public TiledDetectedObject(DetectedObject detectedObject,
                                   TileParameter tileParameter) {
            super(detectedObject.getClassName(), detectedObject.getProbability());
            this.boundingBox = detectedObject.getBoundingBox();
            this.tileParameter = tileParameter;
        }

        public BoundingBox getBoundingBox() {return this.boundingBox;}
        public TileParameter getTileParameter() {return this.tileParameter;}
        public int getXOffset() {return this.tileParameter.x_offset;}
        public int getYOffset() {return this.tileParameter.y_offset;}
        public int getTileHeight() {return this.tileParameter.tile_height;}
        public int getTileWidth() {return this.tileParameter.tile_width;}

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

            if (this.tileParameter != null){
                if (this.tileParameter.validTile()) {
                    sb.append(" \"tileParameters\": ");
                    sb.append(" \"offset\": \"x_offset\": ").append(this.getXOffset())
                            .append(" \"y_offset\": ").append(this.getYOffset());
                    sb.append(", \"tile dimensions\": \"width\": ").append(this.getTileWidth())
                            .append(" \"height\": ").append(this.getTileHeight());
                }
            }

            sb.append('}');
            return sb.toString();
        }
    }
}
