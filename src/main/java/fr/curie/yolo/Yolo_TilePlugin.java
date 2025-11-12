package fr.curie.yolo;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Mask;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.modelloading.DjlModelLoader;
import fr.curie.modelloading.ModelConfig;
import fr.curie.modelloading.configurators.TranslatorConfigurator;
import fr.curie.modelloading.configurators.YoloConfigurator;
import fr.curie.modelloading.configurators.YoloSegmentationConfigurator;
import fr.curie.modelloading.dialogs.ModelDialogs;
import fr.curie.tools.tiling.TileParameter;
import fr.curie.tools.tiling.TiledDetectedObjects;
import fr.curie.tools.tiling.TilingOptions;
import fr.curie.tools.tiling.TilingDialogs;
import fr.curie.tools.DetectionUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static fr.curie.modelloading.ModelConfigManager.saveConfigToFile;
import static fr.curie.modelloading.dialogs.ModelDialogs.addInitialDialogFields;
import static fr.curie.tools.DetectionUtils.*;

/**
 * Plugin to execute Yolo models with tiling option
 * Input must be ImagePlus, output must be DetectedObject (with or without segmentation mask)
 *
 */

public class Yolo_TilePlugin implements PlugInFilter {
    protected static ImagePlus imp;

    // List of configurators available
    private static final Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS;
    static {
        Map<String, TranslatorConfigurator> tempMap = new LinkedHashMap<>();
        tempMap.put("Yolo Object Detection", new YoloConfigurator()); // for classical object detection
        tempMap.put("Yolo Object Detection + Segmentation", new YoloSegmentationConfigurator()); //for detection + segmentation
        KNOWN_CONFIGURATORS = Collections.unmodifiableMap(tempMap);
    }
    private final String[] ENGINE_CHOICES = {"", "PyTorch"};

    @Override
    public int setup(String s, ImagePlus imagePlus) {
        imp = imagePlus;
        return DOES_RGB + DOES_8G;
    }

    @Override
    public void run(ImageProcessor ip) {

        // --- 1. initial dialog box ---
        GenericDialog gd = new GenericDialog("Model Directory + Segmentation Outputs");
        // Prompt user for model repository + config info
        addInitialDialogFields(gd);
        gd.addMessage("__________");
        // ask for yolo outputs
        YoloDialogs.addYoloOutputDialog(gd);
        gd.addMessage("__________");
        // ask for Tiling preferences
        TilingDialogs.addTilingDialog(gd);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd);
        DetectionUtils.OutputOptions segmentOptions = YoloDialogs.getYoloOutputAnswer(gd);
        TilingOptions tileOptions = TilingDialogs.getTilingAnswer(gd);

        // check that model path is valid
        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return;
        }
        Path modelPath = initialChoice.modelPath;


        // --- 2. Try to Load Model ---
        IJ.log("\n --- Starting YOLO prediction");
        DjlModelLoader<ImagePlus, DetectedObjects> modelLoader =
                new DjlModelLoader<>(ImagePlus.class, DetectedObjects.class, KNOWN_CONFIGURATORS, ENGINE_CHOICES);
        DjlModelLoader.LoadedModel<ImagePlus, DetectedObjects> loadedResult = modelLoader.loadModel(modelPath, initialChoice);

        if (loadedResult.isFail()) {
            if (loadedResult.isCancelled()) {
                IJ.log(" --- Model loading cancelled.");
            } else {
                IJ.log(" --- Model loading failed.");
                IJ.error("Model loading failed.");
            }
            return;
        }

        // --- 3. Get model + config ---
        try (ZooModel<ImagePlus, DetectedObjects> model = loadedResult.getModel()) {
            ModelConfig modelConfig = loadedResult.getConfig();


            // --- 4. Make prediction ---
            DetectedObjects detectionResult;
            // using tiles
            if (tileOptions.useTiling){
                // configure tile size as default model size if no size was given by the user
                if (tileOptions.defaultTileSize){
                    tileOptions.setWidthAndHeight(modelConfig.getDefaultWidth(), modelConfig.getDefaultHeight());
                }
                detectionResult = runTiledDetection(model, tileOptions, modelConfig);

            // not using tiles
            } else {
                IJ.log("Using non tiled detection");
                try (Predictor<ImagePlus, DetectedObjects> predictor = model.newPredictor()) {
                    detectionResult = predictor.predict(imp);
                } catch (TranslateException e) {
                    IJ.log(" --- Prediction Failed : Error during prediction or translation\n Provided arguments are incompatible with model");
                    IJ.error("Prediction Failed", "Error during prediction or translation:\n" + e.getMessage());
                    throw new RuntimeException(e);
                }
            }

            IJ.log(" --- Prediction done");

            // Save configuration to config.properties if needed
            if (loadedResult.needToRewriteServing()) {
                try {
                    Path newPropertiesFilePath = loadedResult.getNewPropertiesFilePath();
                    saveConfigToFile(modelConfig, newPropertiesFilePath);
                    IJ.log("Saved new configuration.");
                } catch (IOException e) {
                    IJ.log("Warning: Failed to save configuration. Error: " + e.getMessage());
                }
            }

            if (detectionResult == null ) {
                IJ.log(" --- Segmentation failed or returned null.");
                return;
            } else if (detectionResult.getNumberOfObjects() == 0){
                IJ.log(" --- No objects were detected");
                return;
            }
            IJ.log(" --- Number of objects detected: " + detectionResult.getNumberOfObjects());

            // --- 5. Process Detections
            // Load ClassIdMap
            Map<String, Integer> classIdMap = null;
            // Try with provided info
            if (modelConfig.getSynsetFilePath() != null && Files.exists(modelConfig.getSynsetFilePath())) {
                classIdMap = loadClassIDsFromModel(model, modelConfig.getSynsetFilePath().getFileName().toString());
                if (classIdMap == null) {
                    IJ.log("Failed to load class IDs from: " + modelConfig.getSynsetFilePath() );
                } else {
                    IJ.log("Successfully loaded class IDs from: "+ modelConfig.getSynsetFilePath().getFileName().toString());
                }
            } else if (modelConfig.getSynsetFileName() != null) {
                classIdMap = loadClassIDsFromModel(model, modelConfig.getSynsetFileName());
                if (classIdMap == null) {
                    IJ.log("Failed to load class IDs using name: " + modelConfig.getSynsetFileName());
                } else {
                    IJ.log("Successfully loaded class IDs using name: " + modelConfig.getSynsetFileName());
                }
            } else {
                IJ.log("No synset/labels file specified in serving.properties.");
            }

            // if no info or loading failed -> try with default name = synset.txt
            if (classIdMap == null){
                //try with default name = synset.txt
                classIdMap = loadClassIDsFromModel(model, "synset.txt");
                if (classIdMap == null) {
                    IJ.log("Failed to load class IDs from : synset.txt. Using default numeration.");
                } else {
                    IJ.log("Successfully loaded class IDs using default file name : synset.txt");
                }
            }

            // Process Detections = create ROI from DetectedObject
            List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detectionResult, classIdMap);
            if (processedDetections.isEmpty()) {
                IJ.log(" --- No valid detections were processed.");
                return;
            }

            // --- 6. Generate Outputs, based on user choices
            IJ.log(" --- Generating output... ");
            generateOutputs(imp, processedDetections, segmentOptions, classIdMap);


            IJ.log(" --- YOLO detection complete.");

        } catch (Exception e) { // Catch other unexpected errors during prediction/processing
            IJ.log(" --- Processing Error");
            IJ.error("Processing Error", "An unexpected error occurred:\n" + e.getMessage());
            IJ.handleException(e);
        }

    }

    private TiledDetectedObjects runTiledDetection(ZooModel<ImagePlus, DetectedObjects> model, TilingOptions tileOptions, ModelConfig config){

        IJ.log("Using tiled detection");
        List<Rectangle> total_boxes = new ArrayList<>();
        List<Rectangle> total_adjusted_boxes = new ArrayList<>();
        List<Double> total_scores = new ArrayList<>();
        List<String> total_classNames = new ArrayList<>();
        List<TileParameter> total_tileParameters = new ArrayList<>();

        int image_width = imp.getWidth();
        int image_height = imp.getHeight();
        int tile_width = tileOptions.tileWidth;
        int tile_height = tileOptions.tileHeight;
        double overlap = tileOptions.overlap;
        IJ.log("orginal image dimensions : w=" + image_width + " h=" + image_height);

        int x_step = (int) Math.floor(tile_width*(1-overlap));
        int y_step = (int) Math.floor(tile_height*(1-overlap));
        int x_max = Math.max(0, image_width - tile_width);
        int y_max = Math.max(0, image_height - tile_height);
        tile_width = Math.min(tile_width, image_width);
        tile_height = Math.min(tile_height, image_height);

        IJ.log("tiles dimensions : w="+ tile_width + " h=" + tile_height);

        int tiles_number=0;

        try (Predictor<ImagePlus, DetectedObjects> predictor = model.newPredictor()) {
            for (int y = 0; y < y_max+y_step; y += y_step) {
                for (int x = 0; x < x_max+x_step; x += x_step) {
                    int x_eff = Math.min(x, x_max);
                    int y_eff = Math.min(y, y_max);

                    tiles_number++;
                    Roi roi = new Roi(x_eff, y_eff, tile_width, tile_height);
                    imp.setRoi(roi);
                    ImagePlus inputTile = imp.crop("stack");

                    // make predictions
                    DetectedObjects detectionTemp = predictor.predict(inputTile);

                    for (int i = 0; i < detectionTemp.getNumberOfObjects(); i++){
                        DetectedObjects.DetectedObject item = detectionTemp.item(i);
                        total_boxes.add(item.getBoundingBox().getBounds());
                        Rectangle adjustedRectangle = adjustBounds(item.getBoundingBox().getBounds(), x_eff, y_eff, tile_width, tile_height, image_width, image_height);
                        total_adjusted_boxes.add(adjustedRectangle);
                        total_classNames.add(item.getClassName());
                        total_scores.add(item.getProbability());
                        total_tileParameters.add(new TileParameter(x_eff, y_eff, tile_width, tile_height));
                    }
                }
            }
            IJ.log("number of tiles: " + tiles_number);
            System.out.println("\ntotal number of tiles = " + tiles_number);
            System.out.println("total number of prediction = " + total_boxes.size());

            // perform nms to suppress redundant detections
            List<Integer> nms = Rectangle.nms(total_adjusted_boxes, total_scores, config.getNmsThreshold());
            System.out.println("number of prediction after nms = " + nms.size());

            // extract only the boxes, scores and class names of detections that survived nms
            List<BoundingBox> filtered_boxes = nms.stream()
                    .map(total_boxes::get)
                    .collect(Collectors.toList());
            List<Double> filtered_scores = nms.stream()
                    .map(total_scores::get)
                    .collect(Collectors.toList());
            List<String> filtered_classNames = nms.stream()
                    .map(total_classNames::get)
                    .collect(Collectors.toList());
            List<TileParameter> filtered_tileParameters = nms.stream()
                    .map(total_tileParameters::get)
                    .collect(Collectors.toList());

            return new TiledDetectedObjects(filtered_classNames, filtered_scores, filtered_boxes, filtered_tileParameters);

        } catch (TranslateException e) {
            IJ.log(" --- Prediction Failed : Error during prediction or translation\n Provided arguments are incompatible with model");
            IJ.error("Prediction Failed", "Error during prediction or translation:\n" + e.getMessage());
            IJ.handleException(e);
            throw new RuntimeException(e);
        }
    }



    private static Rectangle adjustBounds(Rectangle rectangle, int  offset_x, int offset_y, int tile_width, int tile_height, int image_width, int image_height){
        double x_adjusted =(offset_x + (rectangle.getX()* tile_width))/image_width;
        double y_adjusted = (offset_y + (rectangle.getY()* tile_height))/image_height;
        double w_adjusted = rectangle.getWidth()*tile_width/image_width;
        double h_adjusted = rectangle.getHeight()*tile_height/image_height;
        if (rectangle instanceof  Mask) {
            return new Mask(x_adjusted, y_adjusted, w_adjusted, h_adjusted, ((Mask) rectangle).getProbDist());
        } else {
            return new Rectangle(x_adjusted, y_adjusted, w_adjusted, h_adjusted);
        }
    }

    private static Mask adjustBounds(Mask mask, int  offset_x, int offset_y, int tile_width, int tile_height, int image_width, int image_height){
        double x_adjusted =(offset_x + (mask.getX()* tile_width))/image_width;
        double y_adjusted = (offset_y + (mask.getY()* tile_height))/image_height;
        double w_adjusted = mask.getWidth()*tile_width/image_width;
        double h_adjusted = mask.getHeight()*tile_height/image_height;
        return new Mask(x_adjusted, y_adjusted, w_adjusted, h_adjusted, mask.getProbDist());
    }

}
