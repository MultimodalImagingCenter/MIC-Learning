package fr.curie.miclearning.tools.detection;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Mask;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.repository.zoo.ZooModel;
import fr.curie.miclearning.prediction.model.ModelConfig;
import fr.curie.miclearning.tools.tiling.TileParameter;
import fr.curie.miclearning.tools.tiling.TiledDetectedObjects;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static fr.curie.miclearning.tools.detection.ProcessedDetection.ROI_MASK_PREFIX;
import static ij.plugin.LutLoader.openLut;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class DetectionUtils {

    private static final float MASK_THRESHOLD = 0.5f;
    private static final int MASK_FOREGROUND_COLOR = 255;

    // --- User Output Selection ---
    public static class OutputOptions {
        public boolean addToRoiManagerBB = true;
        public boolean addToRoiManagerShapes = false;
        public boolean createStackMask = false;
        public boolean createInstanceMask = false;
        public boolean createSemanticMask = false;
        public boolean createInstanceMaskPerClass = false;
        public boolean showDetectionResultTables = false;
        public boolean deletePreviousRoi = false;
        public boolean deletePreviousRT = false; // delete previous result table
    }

    // --- DetectedObject processing ---

    public static List<ProcessedDetection> processDetections(
            ImagePlus imp,
            DetectedObjects detection) {
        return processDetections(imp, detection, null);
    }
    /**
     * Processes DJL detections to create ROIs and associated metadata.
     * Uses a provided map to assign group IDs to classes if available,
     * otherwise generates group IDs dynamically.
     *
     * @param imp              The source ImagePlus.
     * @param detection        The DetectedObjects from DJL.
     * @param externalClassIdMap Optional map of Class Name -> Group ID. If null or empty,
     *                         group IDs will be generated dynamically starting from 1.
     *                         If provided, classes not found in the map will be assigned group ID 0
     * @return A list of ProcessedDetection objects, or empty list if none or on error.
     */
    public static List<ProcessedDetection> processDetections(
            ImagePlus imp,
            DetectedObjects detection,
            Map<String, Integer> externalClassIdMap) {

        if (imp == null || detection == null) {
            IJ.log("Error: Input ImagePlus or DetectedObjects is null.");
            return Collections.emptyList();
        }
        if (detection.getNumberOfObjects() == 0) {
            IJ.log("No objects were detected.");
            return Collections.emptyList();
        }

        // IJ.log("Processing detections...");

        if (detection instanceof TiledDetectedObjects){
            return processFromTiledDetection(imp, detection, externalClassIdMap);
        } else {
            return processFromNonTiledDetection(imp, detection, externalClassIdMap);
        }
    }


    private static List<ProcessedDetection> processFromNonTiledDetection(
            ImagePlus imp,
            DetectedObjects detections,
            Map<String, Integer> externalClassIdMap){
        int imageWidth = imp.getWidth();
        int imageHeight = imp.getHeight();

        // List to store processed results
        List<ProcessedDetection> processedResults = new ArrayList<>();

        // Map to count the occurrences of each class for ROI naming
        Map<String, Integer> classCounts = new HashMap<>();

        // Determine if using external map or dynamic generation
        boolean useExternalMap = externalClassIdMap != null && !externalClassIdMap.isEmpty();
        Map<String, Integer> dynamicClassIdMap = null;
        AtomicInteger nextGroupId;

        if (useExternalMap) {
            nextGroupId = null;
            //IJ.log("Using provided external class ID map for ROI groups.");
        } else {
            IJ.log("No external class ID map used.");
            dynamicClassIdMap = new HashMap<>();
            nextGroupId = new AtomicInteger(1);
        }

        // if detections have list of scores for all classes
        boolean returnAllScores = detections instanceof DetailedDetectedObjects;

        List<DetectedObjects.DetectedObject> items = detections.items();
        for (DetectedObjects.DetectedObject item : items) {
            // --- Get metadata ---
            String className = item.getClassName();
            BoundingBox box = item.getBoundingBox();
            double probability = item.getProbability();

            if (className == null || className.trim().isEmpty()) {
                IJ.log("Warning: Detection ignored: empty class name.");
                continue;
            }

            if (box == null) {
                IJ.log("Warning: Detection ignored: missing BoundingBox.");
                continue;
            }

            // --- Manage Class Counts and Groups ---
            //  Manage Class Counts
            int countForClass = classCounts.getOrDefault(className, 0) + 1;
            classCounts.put(className, countForClass);

            // Assign Group ID
            int groupId;
            if (useExternalMap) {
                Integer idFromMap = externalClassIdMap.get(className);
                if (idFromMap != null) {
                    groupId = idFromMap;
                } else {
                    // Class detected but not found in the provided map
                    IJ.log("Warning: Class '" + className + "' not found in the provided class ID map. Assigning default group ID 0.");
                    groupId = 0; // 0 will lead to no group in Roi Manager
                }
            } else {
                // Generate dynamically using the internal map and counter
                groupId = dynamicClassIdMap.computeIfAbsent(className, k -> nextGroupId.getAndIncrement());
            }

            // --- Create Bounding Box ROI ---
            Roi boundingBoxRoi = createRoiFromBBRect(box, imageWidth, imageHeight);
            if (boundingBoxRoi == null) {
                IJ.log("Warning: Could not create Bounding Box ROI for " + String.format("%s_%d_%.5f", className, countForClass, probability) + ". Skipping detection.");
                continue;
            }

            boundingBoxRoi.setGroup(groupId);
            //imp.setRoi(boundingBoxRoi); // Optional: for visual feedback during processing (+ fun to watch)

            // --- Create Shape ROI (if mask available) ---
            Roi shapeRoi = null;
            if (box instanceof Mask || box instanceof MaskByte) {
                shapeRoi = box instanceof Mask ? createRoiFromBBMask((Mask) box, imageWidth, imageHeight) : createRoiFromBBMask((MaskByte) box, imageWidth, imageHeight);

                if (shapeRoi != null) {
                    shapeRoi.setGroup(groupId);
                    //imp.setRoi(shapeRoi); // Optional: for visual feedback
                    //IJ.log("    " + roiName + " (Group " + groupId + ")");
                } else {
                    IJ.log("Warning: No shape ROI generated (mask empty?). Bounding box (Group " + groupId + ") will still be used.");
                }
            } else {
                //IJ.log("    " + roiName + " (Group " + groupId + ")");
            }

            ProcessedDetection processedDetection = new ProcessedDetection(
                    className, probability, groupId, boundingBoxRoi, shapeRoi, countForClass);

            if (returnAllScores) {
                DetailedDetectedObjects.DetailedDetectedObject detailedItem = (DetailedDetectedObjects.DetailedDetectedObject) item;
                if(detailedItem.getAllScore()!=null) {
                    processedDetection.setAllScore(detailedItem.getAllScore());
                }
            }

            processedResults.add(processedDetection);
        }
        return processedResults;
    }

    private static List<ProcessedDetection> processFromTiledDetection(
            ImagePlus imp,
            DetectedObjects detection,
            Map<String, Integer> externalClassIdMap){
        int imageWidth = imp.getWidth();
        int imageHeight = imp.getHeight();

        // List to store processed results
        List<ProcessedDetection> processedResults = new ArrayList<>();

        // Map to count the occurrences of each class for ROI naming
        Map<String, Integer> classCounts = new HashMap<>();

        // Determine if using external map or dynamic generation
        boolean useExternalMap = externalClassIdMap != null && !externalClassIdMap.isEmpty();
        Map<String, Integer> dynamicClassIdMap = null;
        AtomicInteger nextGroupId;

        if (useExternalMap) {
            nextGroupId = null;
            //IJ.log("Using provided external class ID map for ROI groups.");
        } else {
            IJ.log("No external class ID map used.");
            dynamicClassIdMap = new HashMap<>();
            nextGroupId = new AtomicInteger(1);
        }


        List<TiledDetectedObjects.TiledDetectedObject> items = detection.items();
        for (TiledDetectedObjects.TiledDetectedObject item : items) {
            // --- Get metadata ---
            String className = item.getClassName();
            BoundingBox box = item.getBoundingBox();
            double probability = item.getProbability();
            TileParameter tileParameter = item.getTileParameter();

            if (className == null || className.trim().isEmpty() || box == null) {
                IJ.log("Warning: Detection ignored: empty class name or missing BoundingBox.");
                continue;
            }

            // --- Manage Class Counts and Groups ---
            //  Manage Class Counts
            int countForClass = classCounts.getOrDefault(className, 0) + 1;
            classCounts.put(className, countForClass);

            // Assign Group ID
            int groupId;
            if (useExternalMap) {
                Integer idFromMap = externalClassIdMap.get(className);
                if (idFromMap != null) {
                    groupId = idFromMap;
                } else {
                    // Class detected but not found in the provided map
                    IJ.log("Warning: Class '" + className + "' not found in the provided class ID map. Assigning default group ID 0.");
                    groupId = 0; // 0 will lead to no group in Roi Manager
                }
            } else {
                // Generate dynamically using the internal map and counter
                groupId = dynamicClassIdMap.computeIfAbsent(className, k -> nextGroupId.getAndIncrement());
            }


            // --- Create Bounding Box ROI ---
            Roi boundingBoxRoi = createRoiFromBBRect(box, imageWidth, imageHeight, tileParameter);
            if (boundingBoxRoi == null) {
                IJ.log("Warning: Could not create Bounding Box ROI for " + String.format("%s_%d_%.5f", className, countForClass, probability) + ". Skipping detection.");
                continue;
            }
            boundingBoxRoi.setGroup(groupId);
            //imp.setRoi(boundingBoxRoi); // Optional: for visual feedback during processing (+ fun to watch)

            // --- Create Shape ROI (if mask available) ---
            Roi shapeRoi = null;
            if (box instanceof Mask || box instanceof MaskByte) {
                shapeRoi = box instanceof Mask ? createRoiFromBBMask((Mask) box, imageWidth, imageHeight) : createRoiFromBBMask((MaskByte) box, imageWidth, imageHeight);
                if (shapeRoi != null) {
                    shapeRoi.setGroup(groupId);
                    //imp.setRoi(shapeRoi); // Optional: for visual feedback
                    //IJ.log("    " + roiName + " (Group " + groupId + ")");
                } else {
                    IJ.log("Warning: No shape ROI generated (mask empty?). Bounding box (Group " + groupId + ") will still be used.");
                }
            } else {
                //IJ.log("    " + roiName + " (Group " + groupId + ")");
            }

            processedResults.add(new ProcessedDetection(
                    className, probability, groupId, boundingBoxRoi, shapeRoi, countForClass
            ));
        }
        return processedResults;
    }


    // --- ROI Creation Helpers ---
    private static Roi createRoiFromBBRect(BoundingBox box, int imageWidth, int imageHeight) {
        return createRoiFromBBRect(box, imageWidth, imageHeight, null);
    }

    private static Roi createRoiFromBBRect(BoundingBox box, int imageWidth, int imageHeight, TileParameter tileParameter) {
        Rectangle rectangle = box.getBounds();
        int x_offset = 0;
        int y_offset = 0;
        int tile_width = imageWidth;
        int tile_height = imageHeight;
        if (tileParameter != null) {
            if (tileParameter.validTile()) {
                x_offset = tileParameter.x_offset;
                y_offset = tileParameter.y_offset;
                tile_width = tileParameter.tile_width;
                tile_height = tileParameter.tile_height;
            }
        }

        // // Calculate coordinates
        float x = (float) (x_offset + (rectangle.getX() * tile_width));
        float y = (float) (y_offset + (rectangle.getY() * tile_height));
        float width = (float) (rectangle.getWidth() * tile_width);
        float height = (float) (rectangle.getHeight() * tile_height);

        // Ensure coordinates/dimensions are within image bounds
        if (x <0 || y<0 || x+width >imageWidth || y+height>imageHeight){
            //IJ.log("Warning: Bounding box ROI outside of image bounds. The bounding box will be cropped.");
            // if not inside image bounds, crop bounding box
            if (x <0) x=0;
            if (y<0) y=0;
            if (x+width >imageWidth) width = imageWidth-x;
            if (y+height>imageHeight) height = imageHeight-y;
        }

        if (width == 0 || height == 0) {
            IJ.log("Warning: Bounding box ROI has zero width or height");
            return null;
        }
        return new Roi(x, y, width, height);
    }

    private static Roi createRoiFromBBMask(Mask mask, int imageWidth, int imageHeight) {
        return createRoiFromBBMask(mask, imageWidth, imageHeight, null);
    }

    private static Roi createRoiFromBBMask(MaskByte mask, int imageWidth, int imageHeight) {
        return createRoiFromBBMask(mask, imageWidth, imageHeight, null);
    }

    /**
     * Create a shape ROI from a DJL Mask (= class of DJL Bounding box)
     * Only keep the inside of the bounding box
     *
     * @param mask          The mask object (mask has the same dimensions as the tile)
     * @param imageWidth    Width of the source ImagePlus
     * @param imageHeight   Height of the source ImagePlus
     * @return A ShapeRoi or null if probability distribution is missing
     */
    private static Roi createRoiFromBBMask(Mask mask, int imageWidth, int imageHeight, TileParameter tileParameter) {
        float[][] probDist = mask.getProbDist(); // initial mask
        if (probDist == null || probDist.length == 0 || probDist[0].length == 0) {
            IJ.log("Warning: Mask probability distribution is null or empty.");
            return null;
        }

        int maskHeight = probDist.length;
        int maskWidth = probDist[0].length;
        Rectangle rect =  mask.getBounds();

        int x_offset = 0;
        int y_offset = 0;
        int tile_width = imageWidth;
        int tile_height = imageHeight;
        if (tileParameter != null) {
            if (tileParameter.validTile()) {
                x_offset = tileParameter.x_offset;
                y_offset = tileParameter.y_offset;
                tile_width = tileParameter.tile_width;
                tile_height = tileParameter.tile_height;
            }
        }

        // Calculate pixel coordinates within the tile for the bounding box
        int boxX = (int) Math.floor(rect.getX() * tile_width);
        int boxY = (int) Math.floor(rect.getY() * tile_height);
        int boxWidth = (int) Math.ceil(rect.getWidth() * tile_width);
        int boxHeight = (int) Math.ceil(rect.getHeight() * tile_height);

        // Create a temporary mask processor covering the bounding box
        // (same scale as the total image)
        ByteProcessor boxProcessor = new ByteProcessor(boxWidth, boxHeight); // Initialized to 0

        // Scaling factors
        // (tile is same scale as image, and mask cover the hole tile)
        final float scaleX = (float) maskWidth / tile_width;
        final float scaleY = (float) maskHeight / tile_height;


        boolean pixelSet = false; // Track if any pixel passes the threshold

        // Iterate through target pixels within the bounding box
        for (int boxTargetY = 0; boxTargetY < boxHeight; boxTargetY++) {
            for (int boxTargetX = 0; boxTargetX < boxHeight; boxTargetX++) {

                // map to target tile coordinates
                int tileTargetY = boxTargetY + boxY;
                int tileTargetX = boxTargetX + boxX;
                // Map target tiles coordinates back to the mask's coordinates
                int maskJ = (int) Math.floor(tileTargetX * scaleX);
                int maskK = (int) Math.floor(tileTargetY * scaleY);

                // Boundary check for the mask indices
                if (maskJ >= 0 && maskJ < maskWidth && maskK >= 0 && maskK < maskHeight) {
                    // Check probability threshold
                    if (probDist[maskK][maskJ] > MASK_THRESHOLD) {
                        // make the pixel white
                        boxProcessor.putPixel(boxTargetX, boxTargetY, MASK_FOREGROUND_COLOR);
                        pixelSet = true;
                    }
                }
            }
        }

        if (!pixelSet) {
            IJ.log("Info: No pixels passed the threshold " + MASK_THRESHOLD);
            return null;
        }

        // Create ROI from the thresholded mask (inside the box coordinates)
        boxProcessor.setThreshold(128, 255, ImageProcessor.BLACK_AND_WHITE_LUT);
        ThresholdToSelection t2s = new ThresholdToSelection();
        Roi roi = t2s.convert(boxProcessor);

        // put the roi in the total image coordinates
        if (roi != null) {
            roi.setLocation(x_offset + boxX + roi.getBounds().getX(), y_offset + boxY + roi.getBounds().getY());
        }

        return roi;
    }

    /**
     * Create a shape ROI from a DJL Mask (= class of DJL Bounding box)
     * Only keep the inside of the bounding box
     *
     * @param mask          The mask object (mask has the same dimensions as the tile)
     * @param imageWidth    Width of the source ImagePlus
     * @param imageHeight   Height of the source ImagePlus
     * @return A ShapeRoi or null if probability distribution is missing
     */
    private static Roi createRoiFromBBMask(MaskByte mask, int imageWidth, int imageHeight, TileParameter tileParameter) {
        byte[][] probDist = mask.getMask(); // initial mask
        if (probDist == null || probDist.length == 0 || probDist[0].length == 0) {
            IJ.log("Warning: Mask probability distribution is null or empty.");
            return null;
        }

        int maskHeight = probDist.length;
        int maskWidth = probDist[0].length;
        Rectangle rect =  mask.getBounds();

        int x_offset = 0;
        int y_offset = 0;
        int tile_width = imageWidth;
        int tile_height = imageHeight;
        if (tileParameter != null) {
            if (tileParameter.validTile()) {
                x_offset = tileParameter.x_offset;
                y_offset = tileParameter.y_offset;
                tile_width = tileParameter.tile_width;
                tile_height = tileParameter.tile_height;
            }
        }

        // Calculate pixel coordinates within the tile for the bounding box
        int boxX = (int) Math.floor(rect.getX() * tile_width);
        int boxY = (int) Math.floor(rect.getY() * tile_height);
        int boxWidth = (int) Math.ceil(rect.getWidth() * tile_width);
        int boxHeight = (int) Math.ceil(rect.getHeight() * tile_height);

        // Create a temporary mask processor covering the bounding box
        // (same scale as the total image)
        ByteProcessor boxProcessor = new ByteProcessor(boxWidth, boxHeight); // Initialized to 0

        // Scaling factors
        // (tile is same scale as image, and mask cover the hole tile)
        final float scaleX = (float) maskWidth / tile_width;
        final float scaleY = (float) maskHeight / tile_height;

        boolean pixelSet = false; // Track if any pixel passes the threshold

        // Iterate through target pixels within the bounding box
        for (int boxTargetY = 0; boxTargetY < boxHeight; boxTargetY++) {
            for (int boxTargetX = 0; boxTargetX < boxWidth; boxTargetX++) {

                // map to target tile coordinates
                int tileTargetY = boxTargetY + boxY;
                int tileTargetX = boxTargetX + boxX;
                // Map target tiles coordinates back to the mask's coordinates
                int maskJ = (int) Math.floor(tileTargetX * scaleX);
                int maskK = (int) Math.floor(tileTargetY * scaleY);

                // Boundary check for the mask indices
                if (maskJ >= 0 && maskJ < maskWidth && maskK >= 0 && maskK < maskHeight) {
                    // Check probability threshold
                    if (probDist[maskK][maskJ] == 1) {
                        // make the pixel white
                        boxProcessor.putPixel(boxTargetX, boxTargetY, MASK_FOREGROUND_COLOR);
                        pixelSet = true;
                    }
                }
            }
        }

        if (!pixelSet) {
            IJ.log("Info: All masks pixel were 0");
            return null;
        }

        // Create ROI from the thresholded mask (inside the box coordinates)
        boxProcessor.setThreshold(128, 255, ImageProcessor.BLACK_AND_WHITE_LUT);
        ThresholdToSelection t2s = new ThresholdToSelection();
        Roi roi = t2s.convert(boxProcessor);

        // put the roi in the total image coordinates
        if (roi != null) {
            roi.setLocation(x_offset + boxX + roi.getBounds().getX(), y_offset + boxY + roi.getBounds().getY());
        }

        return roi;
    }


    // --- Masks conversions
    public static List<ProcessedDetection> detectionFromStackMask(ImagePlus imp) {
        return detectionFromStackMask(imp, null);
    }

    /**
     * Creates ProcessedDetection objects from an instance mask stack.
     * Each slice should contain one instance mask, where the pixel value indicates the group ID.
     * If a classIdMap (ClassName -> GroupID) is provided, it's used to assign class names.
     *
     * @param imp          The source ImagePlus stack where each slice is an instance mask.
     * @param classIdMap   Optional map of Class Name -> Group ID.
     * @return A list of ProcessedDetection objects, or empty list on error or if no detections.
     */
    public static List<ProcessedDetection> detectionFromStackMask(
            ImagePlus imp,
            Map<String, Integer> classIdMap) {

        if (imp == null) {
            IJ.log("Error: Input ImagePlus is null.");
            return Collections.emptyList();
        }
        // Process the class ID map using the helper method
        Map<Integer, String> reverseClassIdMap = createReverseClassIdMap(classIdMap);
        boolean useExternalMap = reverseClassIdMap != null;

        // List to store all the detections
        List<ProcessedDetection> processedResults = new ArrayList<>();
        // Map to count the occurrences per group
        Map<Integer, Integer> groupCounter = new HashMap<>();
        int stackSize = imp.getStackSize();
        ImageStack maskStack = imp.getStack();
        ThresholdToSelection t2s = new ThresholdToSelection();

        // Iterate through slices
        for (int sliceIndex = 1; sliceIndex <= stackSize; sliceIndex++) {
            ImageProcessor processor = maskStack.getProcessor(sliceIndex);
            ImageStatistics stats = processor.getStats();

            // Determine Group ID: Assuming the max pixel value represents the object's ID
            int groupId = (int) stats.max;
            if (groupId == 0) {
                IJ.log("Skipping slice " + sliceIndex + ": Max pixel value is 0 (likely empty or background).");
                continue;
            }

            // Determine Class Name using the map (if available)
            String className = null;
            if (useExternalMap) {
                className = reverseClassIdMap.get(groupId);
                if (className == null) {
                    // Detected a groupId that wasn't a value in the provided map
                    IJ.log("Warning: Detected Group ID " + groupId + " on slice " + sliceIndex +
                            " which was not found in the provided classIdMap. ClassName will be null.");
                }
            }

            // Increment the instance counter for this group
            int countForGroup = groupCounter.getOrDefault(groupId, 0) + 1;
            groupCounter.put(groupId, countForGroup);

            // Use className in the name if available, otherwise just use groupId
            String baseName = (className != null) ? className : "ID" + groupId;

            // Create Shape ROI
            processor.setThreshold(groupId, groupId, ImageProcessor.NO_LUT_UPDATE);
            Roi roi = t2s.convert(processor);
            processor.resetThreshold();

            if (roi == null) {
                IJ.log("Warning: ThresholdToSelection returned null ROI for groupId  slice " + sliceIndex  + ". Possibly no pixels matched threshold or tiny region.");
                continue;
            }

            roi.setGroup(groupId);
            //imp.setRoi(roi); // Optional: for visual feedback (+ fun to watch)
            IJ.log("Shape ROI created for " + String.format("%s_%d", baseName, countForGroup) + " (Group " + groupId + ")");
            processedResults.add(new ProcessedDetection(
                    className, null, groupId, null, roi, countForGroup
            ));
        }

        if (processedResults.isEmpty()) {
            IJ.log("No valid instances were detected in the provided stack.");
        } else {
            IJ.log("Processed " + processedResults.size() + " instances from the stack.");
        }

        return processedResults;
    }


    public static List<ProcessedDetection> detectionFromInstancePerClasses(ImagePlus imp) {
        return detectionFromInstancePerClasses(imp, null);
    }

    /**
     * Creates ProcessedDetection objects from a stack where each slice is a instance mask for a class
     * Assumes Group ID corresponds to the slice number.
     *
     * @param imp          The source ImagePlus stack.
     * @param classIdMap   Optional map of Class Name -> Group ID (slice number).
     * @return A list of ProcessedDetection objects, or empty list on error or if no detections.
     */
    public static List<ProcessedDetection> detectionFromInstancePerClasses(ImagePlus imp, Map<String, Integer> classIdMap) {
        if (imp == null ) {
            IJ.log("Error: Input ImagePlus is null.");
            return Collections.emptyList();
        }

        // Process the class ID map
        Map<Integer, String> reverseClassIdMap = createReverseClassIdMap(classIdMap);
        boolean useExternalMap = reverseClassIdMap != null;

        // List to store all the detections
        List<ProcessedDetection> processedResults = new ArrayList<>();
        int stackSize = imp.getStackSize(); // number of classes
        ImageStack maskStack = imp.getStack();
        ThresholdToSelection t2s = new ThresholdToSelection();

        // iterate through all classes
        for (int sliceIndex = 1; sliceIndex <= stackSize; sliceIndex++){
            int groupId = sliceIndex;
            ImageProcessor processor = maskStack.getProcessor(sliceIndex);
            ImageStatistics stats = processor.getStats();
            // get number of instances (should be pixel max value if instances are numbered from 1 to max)
            int instanceMax = (int) processor.getStats().max;

            // Determine Class Name using the map (if available)
            String className = null;
            if (useExternalMap) {
                className = reverseClassIdMap.get(groupId);
                if (className == null) {
                    IJ.log("Warning: Detected Group ID " + groupId + "(slice " + sliceIndex +
                            ") which was not found in the provided classIdMap. ClassName will be null.");
                }
            }
            // Use className in the name if available, otherwise just use groupId
            String baseName = (className != null) ? className : "ID" + groupId;

            // iterate through all instances of the class
            for (int instanceId = 1; instanceId <= instanceMax; instanceId++){

                // Set Threshold to keep only one instance
                processor.setThreshold(instanceId, instanceId, ImageProcessor.NO_LUT_UPDATE);
                // Create Shape ROI
                Roi roi = t2s.convert(processor);

                if (roi == null) {
                    IJ.log("Warning: ThresholdToSelection returned null ROI for groupId " + groupId + ", instance " + instanceId + ". Possibly no pixels matched threshold or tiny region.");
                    processor.resetThreshold();
                    continue;
                }
                processor.resetThreshold();

                roi.setGroup(groupId);
                //imp.setRoi(roi); // Optional: for visual feedback
                IJ.log("Shape ROI created for " + String.format("%s_%d", className, instanceId) + " (Group " + groupId + ")");

                // add detection to list
                processedResults.add(new ProcessedDetection(
                        className, null, groupId, null, roi, instanceId
                ));
            }
        }
        if (processedResults.isEmpty()) {
            IJ.log("detectionFromInstancePerClasses: No valid instances were detected across all classes.");
        } else {
            IJ.log("detectionFromInstancePerClasses: Finished processing. Found " + processedResults.size() + " instances total.");
        }
        return processedResults;
    }


    public static List<ProcessedDetection> detectionFromInstanceAndSemantic(ImagePlus instanceImp, ImagePlus semanticImp) {
        return detectionFromInstanceAndSemantic(instanceImp, semanticImp, null);
    }

    /**
     * Creates ProcessedDetection objects from instance mask and semantic masks.
     * Instances are identified from instanceImp, and their class (group ID) is determined
     * from the corresponding area in semanticImp.
     *
     * @param instanceImp  ImagePlus containing instance segmentation (pixel value = instance ID).
     * @param semanticImp  ImagePlus containing semantic segmentation (pixel value = class/group ID).
     * @param classIdMap   Optional map of Class Name -> Group ID.
     * @return A list of ProcessedDetection objects, or empty list on error or if no detections.
     */
    public static List<ProcessedDetection> detectionFromInstanceAndSemantic(ImagePlus instanceImp, ImagePlus semanticImp, Map<String, Integer> classIdMap) {

        if (instanceImp == null || semanticImp == null) {
            IJ.log("Error: One of the input ImagePlus is null.");
            return Collections.emptyList();
        }
        // dimension check
        if (instanceImp.getWidth() != semanticImp.getWidth() ||
                instanceImp.getHeight() != semanticImp.getHeight()) {
            IJ.error("detectionFromInstanceAndSemantic: Instance and Semantic images must have the same dimensions.");
            return Collections.emptyList();
        }
        // check if stack/composite image
        if (instanceImp.getNDimensions() > 2 ||
                semanticImp.getNDimensions() > 2) {
            IJ.log("Warning : at least one of the images have more then one slice/channel/frame. Only the first slice/channel/frame will be used");
        }

        // Process the class ID map
        Map<Integer, String> reverseClassIdMap = createReverseClassIdMap(classIdMap);
        boolean useExternalMap = reverseClassIdMap != null;

        // List to store all the detections
        List<ProcessedDetection> processedResults = new ArrayList<>();
        // Map to count the occurrences of each class
        Map<Integer, Integer> groupCounter = new HashMap<>();

        ImageProcessor instanceProcessor = instanceImp.getProcessor();
        ImageProcessor semanticProcessor = semanticImp.getProcessor();
        ThresholdToSelection t2s = new ThresholdToSelection();

        // get number of instances (should be max pixel value of instance mask if instances are numbered from 1 to max)
        int instanceMax = (int) instanceProcessor.getStats().max;
        IJ.log("number of instances detected : " + instanceMax);

        // iterate through all instances
        for (int instanceId = 1; instanceId <= instanceMax; instanceId++){
            // Set Threshold to keep only one instance
            instanceProcessor.setThreshold(instanceId, instanceId, ImageProcessor.NO_LUT_UPDATE);
            // Create Shape ROI
            Roi roi = t2s.convert(instanceProcessor);

            if (roi == null) {
                IJ.log("Warning: ThresholdToSelection returned null ROI for instance " + instanceId + ". Possibly no pixels matched threshold or tiny region.");
                instanceProcessor.resetThreshold();
                continue;
            }
            instanceProcessor.resetThreshold();

            // Find instance class/groupID
            semanticProcessor.setRoi(roi);
            ImageStatistics semanticStats = semanticProcessor.getStats();
            semanticProcessor.resetRoi();
            // Use the mode (most frequent pixel value) within the ROI on the semantic map as the Group ID
            int groupId = (int) semanticStats.mode;

            if (groupId == 0) {
                // This instance falls entirely on background in the semantic map, or mode calculation failed.
                // not skipping, in case 0 is actually a valid class.
                IJ.log("Warning: Instance ID " + instanceId + " corresponds to mode 0 (background?) in the semantic mask. This class won't have a group ID in the ROI manager + won't appear in created masks");
                //continue;
            }

            // Determine Class Name using the map (if available)
            String className = null;
            if (useExternalMap) {
                className = reverseClassIdMap.get(groupId);
                if (className == null) {
                    IJ.log("Warning: Detected Group ID " + groupId + " on instance " + instanceId +
                            " which was not found in the provided classIdMap. ClassName will be null.");
                }
            }

            // Increment the counter for this class
            int countForGroup = groupCounter.getOrDefault(groupId, 0) + 1;
            groupCounter.put(groupId, countForGroup);

            // Use className in the name if available, otherwise just use groupId
            String baseName = (className != null) ? className : "ID" + groupId;

            roi.setGroup(groupId);
            //semanticImp.setRoi(roi); // Optional: for visual feedback
            IJ.log("Shape ROI created for " + String.format("%s_%d", baseName, countForGroup) + " (Group " + groupId + ")");

            // add detection to list
            processedResults.add(new ProcessedDetection(
                    className, null, groupId, null, roi, countForGroup
            ));

        }

        if (processedResults.isEmpty()) {
            IJ.log("detectionFromInstanceAndSemantic: No valid instances with corresponding semantic groups were detected.");
        } else {
            IJ.log("detectionFromInstanceAndSemantic: Finished processing. Found " + processedResults.size() + " instances.");
        }

        return processedResults;
    }

    /**
     * Creates a reverse map (GroupID -> ClassName) from the provided map (ClassName -> GroupID).
     *
     * @param classIdMap Original map (ClassName -> GroupID).
     * @return A map from GroupID to ClassName, or null if the input map is null/empty or unusable.
     */
    public static Map<Integer, String> createReverseClassIdMap(Map<String, Integer> classIdMap) {
        if (classIdMap == null || classIdMap.isEmpty()) {
            IJ.log("No class ID map used");
            return null;
        }

        //IJ.log("Using provided class ID map to determine class names.");
        Map<Integer, String> reverseMap = new HashMap<>();

        for (Map.Entry<String, Integer> entry : classIdMap.entrySet()) {
            String className = entry.getKey();
            Integer groupId = entry.getValue();

            if (groupId != null && className != null && !className.trim().isEmpty()) {
                // Check for duplicate Group IDs in the input map
                if (reverseMap.containsKey(groupId)) {
                    IJ.log("Warning: Duplicate Group ID " + groupId + " found in classIdMap. ");
                } else {
                    reverseMap.put(groupId, className);
                }
            } else {
                IJ.log(String.format(
                        "Warning: Skipping entry in classIdMap due to null/empty value (ClassName: %s, GroupID: %s).",
                        className, groupId
                ));
            }
        }

        if (reverseMap.isEmpty()) {
            IJ.log("Warning: Provided classIdMap resulted in an empty reverse map after processing. Class names will not be assigned.");
            return null;
        }

        return reverseMap;
    }


    // --- Output Generation Methods ---

    /**
     * Adds selected ROIs to the ImageJ ROI Manager.
     *
     * @param manager          The RoiManager instance.
     * @param processedResults The list of processed detections.
     * @param addBB            True to add bounding box ROIs.
     * @param addShape         True to add shape ROIs.
     */
    public static void addRoisToManager(RoiManager manager, List<ProcessedDetection> processedResults, boolean addBB, boolean addShape) {
        if (manager == null || processedResults.isEmpty()) return;
        // iterate twice through the Rois, to add first the bounding boxes, then the masks
        if (addBB){
            for (ProcessedDetection result : processedResults) {
                if (result.getBoundingBoxRoi() != null) {
                    manager.addRoi((Roi) result.getBoundingBoxRoi().clone());
                }
            }
            IJ.log("Bounding Box ROIs created");
        }
        if (addShape){
            for (ProcessedDetection result : processedResults) {
                if (result.hasShapeRoi()) {
                    manager.addRoi((Roi) result.getShapeRoi().clone());
                }
            }
            IJ.log("Shape ROIs created");
        }
        manager.runCommand("Show All"); // Make ROIs visible
    }


    /**
     * Creates a stack of binary masks, one slice per instance (shape).
     * The pixel value  is the group ID
     *
     * @param imp              The source ImagePlus (for dimensions).
     * @param processedResults The list of processed detections.
     * @return An ImagePlus containing the mask stack, or null if no shapes.
     */
    public static ImagePlus createStackMask(ImagePlus imp, List<ProcessedDetection> processedResults) {
        // Filter for detections that actually have a shape ROI
        List<ProcessedDetection> shapeDetections = processedResults.stream()
                .filter(ProcessedDetection::hasShapeRoi)
                .collect(Collectors.toList());

        if (shapeDetections.isEmpty()) {
            IJ.log("No shape ROIs available to create a stack mask.");
            return null;
        }

        int imageWidth = imp.getWidth();
        int imageHeight = imp.getHeight();
        ImageStack maskStack = new ImageStack(imageWidth, imageHeight);

        for (ProcessedDetection result : shapeDetections) {
            ShortProcessor processor = new ShortProcessor(imageWidth, imageHeight); // even if max_group can only be 255, using shortProcessor for uniformity
            Roi roi = result.getShapeRoi();
            int groupId = result.getGroupId();
            processor.setColor(groupId);
            processor.fill(roi);
            maskStack.addSlice(roi.getName(), processor);
        }

        ImagePlus impMask = new ImagePlus("Stack Mask", maskStack);
        IJ.log("Stack of binary masks created.");
        impMask.setDisplayRange(0, 255);
        setGlasbeyLut(impMask);
        return impMask;
    }


    /**
     * Creates a single-slice instance mask where each instance (shape) has a unique pixel value.
     *
     * @param imp              The source ImagePlus (for dimensions).
     * @param processedResults The list of processed detections.
     * @return An ImagePlus containing the instance mask, or null if no shapes.
     */
    public static ImagePlus createInstanceMask(ImagePlus imp, List<ProcessedDetection> processedResults) {
        // Filter for detections that actually have a shape ROI
        List<ProcessedDetection> shapeDetections = processedResults.stream()
                .filter(ProcessedDetection::hasShapeRoi)
                .collect(Collectors.toList());

        if (shapeDetections.isEmpty()) {
            IJ.log("No shape ROIs available to create an instance mask.");
            return null;
        }

        int imageWidth = imp.getWidth();
        int imageHeight = imp.getHeight();
        int numInstances = shapeDetections.size();

        ImageProcessor processor = createAndFillProcessor(shapeDetections, imageWidth, imageHeight);

        ImagePlus instanceImage = new ImagePlus("Instance Mask", processor);
        IJ.log("Instance mask created.");
        instanceImage.setDisplayRange(0, 255);
        setGlasbeyLut(instanceImage);
        return instanceImage;
    }


    /**
     * Creates a single-slice semantic mask where each pixel is colored by its class ID (group ID).
     *
     * @param imp              The source ImagePlus (for dimensions).
     * @param processedResults The list of processed detections.
     * @return An ImagePlus containing the semantic mask, or null if no shapes.
     */
    public static ImagePlus createSemanticMask(ImagePlus imp, List<ProcessedDetection> processedResults) {
        // Filter for detections that actually have a shape ROI
        List<ProcessedDetection> shapeDetections = processedResults.stream()
                .filter(ProcessedDetection::hasShapeRoi)
                .collect(Collectors.toList());

        if (shapeDetections.isEmpty()) {
            IJ.log("No shape ROIs available to create a semantic mask.");
            return null;
        }

        int imageWidth = imp.getWidth();
        int imageHeight = imp.getHeight();
        int maxGroupId = processedResults.stream().mapToInt(ProcessedDetection::getGroupId).max().orElse(0);

        // Check if we exceed 255 classes and need a ShortProcessor
        ImageProcessor processor;
        processor = new ShortProcessor(imageWidth, imageHeight);

        // order detections to have all detection of the same class painted at the same level
        shapeDetections.sort(Comparator.comparingInt(ProcessedDetection::getGroupId));

        for (ProcessedDetection result : shapeDetections) {
            Roi roi = result.getShapeRoi();
            int groupId = result.getGroupId();
            processor.setColor(groupId);
            processor.fill(roi);
        }

        ImagePlus semanticImage = new ImagePlus("Semantic Mask", processor);
        IJ.log("Semantic mask created.");
        semanticImage.setDisplayRange(0, 255);
        setGlasbeyLut(semanticImage);
        return semanticImage;
    }


    /**
     * Creates a stack where each slice represents a class, and instances within that class
     * have unique pixel values
     *
     * @param imp              The source ImagePlus (for dimensions).
     * @param processedResults The list of processed detections.
     * @return An ImagePlus containing the instance-per-class stack, or null if no shapes.
     */
    public static ImagePlus createInstanceMaskPerClass(ImagePlus imp, List<ProcessedDetection> processedResults) {
        return createInstanceMaskPerClass(imp, processedResults, null);
    }


    /**
     * Creates a stack where each slice represents a class. If a classIdMap is provided,
     * a slice is created for each class in the map in the order of their IDs,
     * showing detected instances or an empty slice if none were found.
     * Instances within a class slice have unique pixel values.
     *
     * @param imp              The source ImagePlus (for dimensions).
     * @param processedResults The list of processed detections.
     * @param classIdMap       Optional map of Class Name -> Group ID. Defines the slices
     *                         and their order if provided and not empty.
     * @return An ImagePlus containing the instance-per-class stack, or null if no shapes detected.
     */
    public static ImagePlus createInstanceMaskPerClass(
            ImagePlus imp,
            List<ProcessedDetection> processedResults,
            Map<String, Integer> classIdMap) {

        // Filter for detections that actually have a shape ROI
        List<ProcessedDetection> shapeDetections = processedResults.stream()
                .filter(ProcessedDetection::hasShapeRoi)
                .collect(Collectors.toList());

        if (shapeDetections.isEmpty() && (classIdMap == null || classIdMap.isEmpty())) {
            IJ.log("No shape ROIs available and no class map provided. Cannot create instance mask stack.");
            return null;
        }

        int imageWidth = imp.getWidth();
        int imageHeight = imp.getHeight();

        // Group detections by their Group ID
        Map<Integer, List<ProcessedDetection>> detectionsByGroup = new HashMap<>();
        for (ProcessedDetection result : shapeDetections) {
            detectionsByGroup.computeIfAbsent(result.getGroupId(), k -> new ArrayList<>()).add(result);
        }

        ImageStack classStack = new ImageStack(imageWidth, imageHeight);
        boolean useExternalMap = classIdMap != null && !classIdMap.isEmpty();

        if (useExternalMap) {
            IJ.log("Using provided class ID map to define stack slices.");
            // Sort classes by Group ID
            List<Map.Entry<String, Integer>> sortedClasses = new ArrayList<>(classIdMap.entrySet()); // convert to list for easier sorting
            sortedClasses.sort(Map.Entry.comparingByValue());

            for (Map.Entry<String, Integer> entry : sortedClasses) {
                String className = entry.getKey();
                int groupId = entry.getValue();

                // Get the actual detections for this group ID (if any)
                List<ProcessedDetection> groupDetections = detectionsByGroup.getOrDefault(groupId, Collections.emptyList());

                int numInstancesInClass = groupDetections.size();

                // Create the processor and fill it
                ImageProcessor processor = createAndFillProcessor(groupDetections, imageWidth, imageHeight);

                // Add the slice to the stack
                classStack.addSlice(className + " (" + numInstancesInClass + " instances)", processor);
            }

        } else {
            IJ.log("No class ID map used.");
            if (detectionsByGroup.isEmpty()) {
                IJ.log("No shape ROIs were detected. Cannot create instance mask stack.");
                return null;
            }

            // Store GroupID -> ClassName
            Map<Integer, String> groupNameMap = new HashMap<>();
            for (ProcessedDetection result : shapeDetections) {
                groupNameMap.putIfAbsent(result.getGroupId(), result.getClassName());
            }

            // Create slices, sorted by Group ID
            List<Integer> sortedDetectedGroupIds = new ArrayList<>(detectionsByGroup.keySet());
            Collections.sort(sortedDetectedGroupIds);

            for (int groupId : sortedDetectedGroupIds) {
                List<ProcessedDetection> groupDetections = detectionsByGroup.get(groupId);
                if (groupDetections == null || groupDetections.isEmpty()) continue;

                int numInstancesInClass = groupDetections.size();
                String className = groupNameMap.getOrDefault(groupId, "UnknownClass_ID" + groupId);
                // Create the processor and fill it
                ImageProcessor processor = createAndFillProcessor(groupDetections, imageWidth, imageHeight);

                classStack.addSlice(className + " (" + numInstancesInClass + " instances)", processor);
            }
        }

        if (classStack.getSize() == 0) {
            IJ.log("Could not create any slices for the instance-per-class mask stack (this might happen if the map was empty and no detections were found).");
            return null;
        }

        ImagePlus classStackImage = new ImagePlus("Instance Mask per Class", classStack);
        IJ.log("Instance mask per class stack created");
        classStackImage.setDisplayRange(0, 255);
        setGlasbeyLut(classStackImage); // Apply LUT
        return classStackImage;
    }

    /**
     * Create an appropriate ImageProcessor (Byte or Short)
     * and fill it with instance masks.
     * Returns an empty ByteProcessor if the list of detections is empty.
     *
     * @param detections List of detections for a single class group.
     * @param width           Image width.
     * @param height          Image height.
     * @return ImageProcessor filled with instance masks, or an empty ByteProcessor.
     */
    private static ImageProcessor createAndFillProcessor(List<ProcessedDetection> detections, int width, int height) {
        int numInstances = detections.size();
        ImageProcessor processor;

        // If no detections, return an empty ByteProcessor
        if (numInstances == 0) {
            return new ByteProcessor(width, height);
        }

        processor = new ShortProcessor(width, height);

        // Fill the processor with instance IDs
        int instanceId = 0;
        for (ProcessedDetection result : detections) {
            if (result.getShapeRoi() != null) {
                instanceId++;
                processor.setColor(instanceId);
                processor.fill(result.getShapeRoi());
            } else {
                IJ.log("Warning: Found detection without a Shape ROI during processor filling. Skipping this instance.");
            }
        }
        processor.setThreshold(0, 0, ImageProcessor.NONE);
        return processor;
    }

    /**
     * Generates the selected output visualizations (masks, ROIs).
     * @param processedDetections List of processed detections.
     * @param options User choices for output types.
     */
    public static void generateOutputs(ImagePlus imp, List<ProcessedDetection> processedDetections, OutputOptions options , Map<String, Integer> classIdMap) {
        // Add to ROI Manager
        if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
            RoiManager roiManager = getRoiManager();
            DetectionUtils.addRoisToManager(roiManager, processedDetections, options.addToRoiManagerBB, options.addToRoiManagerShapes);
            roiManager.setVisible(true);
        }

        if (options.showDetectionResultTables){
            // not implemented for now
        }

        // Create Stack Mask
        if (options.createStackMask) {
            ImagePlus stackMask = DetectionUtils.createStackMask(imp, processedDetections);
            if (stackMask != null) stackMask.show();
        }

        // Create Instance Mask
        if (options.createInstanceMask) {
            ImagePlus instanceMask = DetectionUtils.createInstanceMask(imp, processedDetections);
            if (instanceMask != null) instanceMask.show();
        }

        // Create Semantic Mask
        if (options.createSemanticMask) {
            ImagePlus semanticMask = DetectionUtils.createSemanticMask(imp, processedDetections);
            if (semanticMask != null) semanticMask.show();
        }

        // Create Instance Mask Per Class
        if (options.createInstanceMaskPerClass) {
            ImagePlus instanceMaskPerClass = DetectionUtils.createInstanceMaskPerClass(imp, processedDetections, classIdMap);
            if (instanceMaskPerClass != null) instanceMaskPerClass.show();
        }
    }

    public static void setGlasbeyLut(ImagePlus imp){
        // Set glasbey LUT
        Path pathLut = Paths.get(IJ.getDirectory("imagej"),"luts","glasbey_on_dark.lut");
        if (Files.notExists(pathLut)){
            IJ.log("Unable to find Glasbey_on_dark LUT file");
            return;
        }
        LUT lut  = openLut(pathLut.toString());
        imp.setLut(lut);
    }

    public static Map<String, Integer> loadClassIDsFromFile(String filepath) {
        // TODO : check the file has the expected format (not 300 lines long, no "=" or "{"...).
        Map<String, Integer> classIdMap = new HashMap<>();
        File file = new File(filepath);

        try (Scanner s = new Scanner(file)) {
            int classId = 1;
            while (s.hasNextLine()) {
                String line = s.nextLine().trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    classIdMap.put(line, classId++);
                }
            }
            return classIdMap;
        } catch (FileNotFoundException e) {
            IJ.log("Warning : FileNotFoundException - unable to find class file: " + filepath);
            return null;
        }
    }

    /**
     * Loads class names from a file within the model's directory.
     * @param model The loaded ZooModel (to access artifacts).
     * @param filename The name of the file (e.g., "synset.txt", "labels.txt").
     * @return A map of Class Name -> Class ID, or null if loading fails.
     */
    public static Map<String, Integer> loadClassIDsFromModel(ZooModel<?, ?> model, String filename) {
        //IJ.log("Attempting to load class names from artifact: " + filename);
        if (filename == null || filename.trim().isEmpty()) {
            IJ.log("No class name file provided.");
            return null;
        }
        Map<String, Integer> classIdMap = new HashMap<>();
        try (InputStream is = model.getArtifactAsStream(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            int classId = 1;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    classIdMap.put(line, classId++);
                }
            }
            if (classIdMap.isEmpty()) {
                IJ.log("Warning: Class name file was empty: " + filename);
                return null;
            }
            return classIdMap;
        } catch (IOException e) {
            IJ.log("Error reading class name file artifact '" + filename + "': " + e.getMessage());

            return null;
        } catch (NullPointerException e) {
            IJ.log("Class name file artifact not found within the model archive/directory: " + filename);
            return null;
        }
    }

    public static Map<String, Integer> getClassIdMap(ModelConfig modelConfig, ZooModel<ImagePlus, DetectedObjects> model) {
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
        return classIdMap;
    }


}