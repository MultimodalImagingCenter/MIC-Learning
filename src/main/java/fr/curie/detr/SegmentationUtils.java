package fr.curie.detr;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Mask;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.repository.zoo.ZooModel;
import fr.curie.detr.DetailedDetectedObjects;
import fr.curie.tools.tiling.TileParameter;
import fr.curie.tools.tiling.TiledDetectedObjects;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.*;
import fr.curie.yolo.ProcessedDetection;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static ij.IJ.error;
import static ij.plugin.LutLoader.openLut;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class SegmentationUtils {
    
    private static final float MASK_THRESHOLD = 0.5f;
    private static final int MASK_FOREGROUND_COLOR = 255;
    private static final int MAX_BYTE_VALUE = 255; 

    // --- User Output Selection ---
    public static class OutputOptions {
        public boolean addToRoiManagerBB = true;
        public boolean addToRoiManagerShapes = false;
        public boolean createStackMask = false;
        public boolean createInstanceMask = false;
        public boolean createSemanticMask = false;
        public boolean createInstanceMaskPerClass = false;
        public boolean showDetectionResultTables = false;
        public boolean saveResultsData = true;
        public boolean applyPreproMacro = true;
        public boolean clearResults = true;
        public double pixelSize;
    }

    // --- ROI names prefix ---
    private static final String ROI_MASK_PREFIX = "mask_";
    private static final String ROI_BB_PREFIX = "";

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

        IJ.log("Processing detections");

        if (detection instanceof TiledDetectedObjects) {
            return processFromTiledDetection(imp, detection, externalClassIdMap);
        } else {
            return processFromNonTiledDetection(imp, detection, externalClassIdMap);
        }
    }

    private static List<ProcessedDetection> processFromNonTiledDetection(
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
            IJ.log("Using provided external class ID map for ROI groups.");
        } else {
            IJ.log("No external class ID map used.");
            dynamicClassIdMap = new HashMap<>();
            nextGroupId = new AtomicInteger(1);
        }

        boolean returnAllScores = detection instanceof DetailedDetectedObjects;
        List<DetailedDetectedObjects.DetailedDetectedObject> items = detection.items();
        for (DetailedDetectedObjects.DetailedDetectedObject item : items) {
            // --- Get metadata ---
            String className = item.getClassName();
            BoundingBox box = item.getBoundingBox();
            double probability = item.getProbability();

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

            String roiName = String.format("%s_%d_%.5f", className, countForClass, probability);

            // --- Create Bounding Box ROI ---
            Roi boundingBoxRoi = createRoiFromBB(box, imageWidth, imageHeight);
            if (boundingBoxRoi == null) {
                IJ.log("Warning: Could not create Bounding Box ROI for " + roiName + ". Skipping detection.");
                continue;
            }
            boundingBoxRoi.setName(ROI_BB_PREFIX + roiName);
            boundingBoxRoi.setGroup(groupId);
            //imp.setRoi(boundingBoxRoi); // Optional: for visual feedback during processing (+ fun to watch)

            // --- Create Shape ROI (if mask available) ---
            Roi shapeRoi = null;
            if (box instanceof Mask) {
                Mask mask = (Mask) box;
                shapeRoi = createRoiFromBBMask(mask, imageWidth, imageHeight);
                if (shapeRoi != null) {
                    shapeRoi.setName(ROI_MASK_PREFIX + roiName);
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
                    className, probability, groupId, roiName, boundingBoxRoi, shapeRoi);
            if (returnAllScores && item.getAllScore()!=null) {
                processedDetection.setAllScore(item.getAllScore());
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
            IJ.log("Using provided external class ID map for ROI groups.");
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

            String roiName = String.format("%s_%d_%.5f", className, countForClass, probability);

            // --- Create Bounding Box ROI ---
            Roi boundingBoxRoi = createRoiFromBB(box, imageWidth, imageHeight, tileParameter);
            if (boundingBoxRoi == null) {
                IJ.log("Warning: Could not create Bounding Box ROI for " + roiName + ". Skipping detection.");
                continue;
            }
            boundingBoxRoi.setName(ROI_BB_PREFIX + roiName);
            boundingBoxRoi.setGroup(groupId);
            //imp.setRoi(boundingBoxRoi); // Optional: for visual feedback during processing (+ fun to watch)

            // --- Create Shape ROI (if mask available) ---
            Roi shapeRoi = null;
            if (box instanceof Mask) {
                Mask mask = (Mask) box;
                shapeRoi = createRoiFromBBMask(mask, imageWidth, imageHeight, tileParameter);
                if (shapeRoi != null) {
                    shapeRoi.setName(ROI_MASK_PREFIX + roiName);
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
                    className, probability, groupId, roiName, boundingBoxRoi, shapeRoi
            ));
        }
        return processedResults;
    }


    // --- ROI Creation Helpers ---
    private static Roi createRoiFromBB(BoundingBox box, int imageWidth, int imageHeight) {
        return createRoiFromBB(box, imageWidth, imageHeight, null);
    }

    private static Roi createRoiFromBB(BoundingBox box, int imageWidth, int imageHeight, TileParameter tileParameter) {
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
        int x = (int) (x_offset + (rectangle.getX() * tile_width));
        int y = (int) (y_offset + (rectangle.getY() * tile_height));
        int width = (int) (rectangle.getWidth() * tile_width);
        int height = (int) (rectangle.getHeight() * tile_height);

        // Ensure coordinates/dimensions are within image bounds
        if (x <0 || y<0 || x+width >imageWidth || y+height>imageHeight){
            IJ.log("Warning: Bounding box ROI outside of image bounds");
            return null;
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

    /**
     * Create a shape ROI from a DJL Mask (= type of Bounding box)
     * Only keep the inside of the bounding box
     *
     * @param mask          The mask object
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
        Rectangle rect = mask.getBounds();
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
        int boxX = (int) (rect.getX() * tile_width);
        int boxY = (int) (rect.getY() * tile_height);
        int boxWidth = (int) (rect.getWidth() * tile_width);
        int boxHeight = (int) (rect.getHeight() * tile_height);


        // Create a temporary mask processor covering the entire image
        ByteProcessor processor = new ByteProcessor(imageWidth, imageHeight); // Initialized to 0

        // Scaling factors
        final float scaleX = (float) maskWidth / tile_width;
        final float scaleY = (float) maskHeight / tile_height;

        boolean pixelSet = false; // Track if any pixel passes the threshold

        // Iterate through target pixels within the bounding box
        for (int tileTargetY = boxY; tileTargetY < boxY + boxHeight; tileTargetY++) {
            for (int tileTargetX = boxX; tileTargetX < boxX + boxWidth; tileTargetX++) {

                // Map target tiles coordinates back to the mask's coordinates
                int maskJ = (int) Math.floor(tileTargetX * scaleX);
                int maskK = (int) Math.floor(tileTargetY * scaleY);

                // Boundary check for the mask indices
                if (maskJ >= 0 && maskJ < maskWidth && maskK >= 0 && maskK < maskHeight) {
                    // Check probability threshold
                    if (probDist[maskK][maskJ] > MASK_THRESHOLD) {
                        //find coordinate in full image
                        int imageTargetX = x_offset + tileTargetX;
                        int imageTargetY = y_offset + tileTargetY;
                        processor.putPixel(imageTargetX, imageTargetY, MASK_FOREGROUND_COLOR);
                        pixelSet = true;
                    }
                }
            }
        }

        if (!pixelSet) {
            IJ.log("Info: No pixels passed the threshold" + MASK_THRESHOLD);
            return null;
        }

        // Create ROI from the thresholded mask
        processor.setThreshold(128, 255, ImageProcessor.BLACK_AND_WHITE_LUT);
        ThresholdToSelection t2s = new ThresholdToSelection();
        Roi roi = t2s.convert(processor);

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
            String roiName = String.format("%s_%d", baseName, countForGroup);

            // Create Shape ROI
            processor.setThreshold(groupId, groupId, ImageProcessor.NO_LUT_UPDATE);
            Roi roi = t2s.convert(processor);
            processor.resetThreshold();

            if (roi == null) {
                IJ.log("Warning: ThresholdToSelection returned null ROI for groupId  slice " + sliceIndex  + ". Possibly no pixels matched threshold or tiny region.");
                continue;
            }

            roi.setName(ROI_MASK_PREFIX + roiName);
            roi.setGroup(groupId);
            //imp.setRoi(roi); // Optional: for visual feedback (+ fun to watch)
            IJ.log("Shape ROI created for " + roiName + " (Group " + groupId + ")");
            processedResults.add(new ProcessedDetection(
                    className, null, groupId, roiName, null, roi
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
                String roiName = String.format("%s_%d", baseName, instanceId);

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

                roi.setName(ROI_MASK_PREFIX + roiName);
                roi.setGroup(groupId);
                //imp.setRoi(roi); // Optional: for visual feedback
                IJ.log("Shape ROI created for " + roiName + " (Group " + groupId + ")");

                // add detection to list
                processedResults.add(new ProcessedDetection(
                        className, null, groupId, roiName, null, roi
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
            error("detectionFromInstanceAndSemantic: Instance and Semantic images must have the same dimensions.");
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
            String roiName = String.format("%s_%d", baseName, countForGroup);

            roi.setName(ROI_MASK_PREFIX + roiName);
            roi.setGroup(groupId);
            //semanticImp.setRoi(roi); // Optional: for visual feedback
            IJ.log("Shape ROI created for " + roiName + " (Group " + groupId + ")");

            // add detection to list
            processedResults.add(new ProcessedDetection(
                    className, null, groupId, roiName, null, roi
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
    private static Map<Integer, String> createReverseClassIdMap(Map<String, Integer> classIdMap) {
        if (classIdMap == null || classIdMap.isEmpty()) {
            IJ.log("No class ID map used");
            return null;
        }

        IJ.log("Using provided class ID map to determine class names.");
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
    public static void addRoisToManager(RoiManager manager, List<ProcessedDetection> processedResults, boolean addBB, boolean addShape, int sliceNb) {
        if (manager == null || processedResults.isEmpty()) return;
        // iterate twice through the Rois, to add first the bounding boxes, then the masks
        if (addBB){
            for (ProcessedDetection result : processedResults) {
                if (result.getBoundingBoxRoi() != null) {
                    Roi roi = (Roi) result.getBoundingBoxRoi().clone();
                    roi.setPosition(sliceNb);
                    manager.add(roi, sliceNb);
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
            ByteProcessor processor = new ByteProcessor(imageWidth, imageHeight);
            Roi roi = result.getShapeRoi();
            int groupId = result.getGroupId();
            processor.setColor(groupId);
            processor.fill(roi);
            maskStack.addSlice(roi.getName(), processor);
        }

        ImagePlus impMask = new ImagePlus("Stack Mask", maskStack);
        IJ.log("Stack of binary masks created.");
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
        if (maxGroupId > MAX_BYTE_VALUE) {
            IJ.log("Warning: Maximum class ID (" + maxGroupId + ") exceeds 255. Using ShortProcessor for semantic mask.");
            processor = new ShortProcessor(imageWidth, imageHeight);
            //processor = imp.getProcessor().createProcessor(imageWidth, imageHeight); // Creates ShortProcessor if needed
        // Else use Byte processor
        } else {
            processor = new ByteProcessor(imageWidth, imageHeight);
        }

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

        // Determine processor type based on instance count
        if (numInstances > MAX_BYTE_VALUE) {
            IJ.log("Warning: More than 255 instances detected (" + numInstances + "). Using ShortProcessor for instance mask.");
            processor = new ShortProcessor(width, height);
        } else {
            processor = new ByteProcessor(width, height);
        }

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
        return processor;
    }

    public static double estimateMeanDiameter(Roi roi, OutputOptions options) {
        double width = roi.getBounds().getWidth();
        double height = roi.getBounds().getHeight();
        return ((width + height) / 2.0) * options.pixelSize;
    }

    /**
     * Generates the selected output visualizations (masks, ROIs).
     * @param processedDetections List of processed detections.
     * @param options User choices for output types.
     */
    public static void generateOutputs(ImagePlus imp, int sliceNb, List<ProcessedDetection> processedDetections, OutputOptions options , Map<String, Integer> classIdMap, Integer mode) {
        // Add to ROI Manager
        int lastRoiIndex = 0;
        if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
            RoiManager roiManager = getRoiManager();
            roiManager.runCommand("Associate", "true");
            lastRoiIndex = roiManager.getCount();
            SegmentationUtils.addRoisToManager(roiManager, processedDetections, options.addToRoiManagerBB, options.addToRoiManagerShapes, sliceNb);
            roiManager.setVisible(true);
        }

        RoiManager roiManager = getRoiManager();
        // Create a map to count occurrences of each class
        Map<String, Integer> classCounts = new HashMap<>();

        // Store diameters if needed
        Map<String, List<Double>> classDiams = new HashMap<>();

        // Sort by class id (ascending order)
        List<Map.Entry<String, Integer>> sortedClasses = new ArrayList<>(classIdMap.entrySet());
        sortedClasses.sort(Map.Entry.comparingByValue());
        // Prepare detection results table
        String rtTableName = "Detr detection Results";
        ResultsTable rt = ResultsTable.getResultsTable(rtTableName);
        if (rt == null) {
            rt = new ResultsTable();
            rt.setPrecision(5);
        }
        rt.showRowIndexes(true);
        int count = rt.getCounter();
        int roiIncrement = 1;
        for(ProcessedDetection detection:processedDetections){
            rt.incrementCounter(); // Adds a new row
            if(imp.hasImageStack()){
                rt.setValue("stack", count, imp.getTitle());
                rt.setValue("image name", count, imp.getStack().getShortSliceLabel(sliceNb));
            } else {
                rt.setValue("stack", count, "");
                rt.setValue("image name", count, imp.getTitle());
            }
            rt.setValue("slice", count, sliceNb);
            String detClassName = detection.getClassName();
            Double detScore = detection.getProbability();
            rt.setValue("class", count,detClassName);
            rt.setValue("score", count, detScore);
            if(detection.hasAllScore()){
                List<Float> allScore = detection.getAllScore();
                for (Map.Entry<String, Integer> entry : sortedClasses){
                    String className = entry.getKey();
                    int index = entry.getValue();
                    rt.setValue("score " + className, count, allScore.get(index-1));
                }
            }
            // Add ROI ID
            if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
                rt.setValue("ROI ID", count, roiManager.getName(lastRoiIndex));
                lastRoiIndex++;
            }

            // Add object to classCounts (and if needed classDiam
            String className = detection.getClassName();

            //noinspection SwitchStatementWithTooFewBranches
            switch (mode) {
                case 1:
                    double diameter = estimateMeanDiameter(detection.getBoundingBoxRoi(), options);
                    rt.setValue("diam (nm)", count, diameter);
                    classDiams.computeIfAbsent(className, k -> new ArrayList<>()).add(diameter);
                    break;
            }

            classCounts.put(className, classCounts.getOrDefault(className, 0) + 1);
            count++;
        }

        String rtAllTableName = "Results";
        ResultsTable rtAll = ResultsTable.getResultsTable(rtAllTableName);
        if (rtAll == null) {
            rtAll = new ResultsTable();
        }
        int countAll = rtAll.getCounter();
        rtAll.incrementCounter();
        if(imp.hasImageStack()){
            rtAll.setValue("stack", countAll, imp.getTitle());
            rtAll.setValue("image name", countAll, imp.getStack().getShortSliceLabel(sliceNb));
        } else {
            rtAll.setValue("stack", countAll, "");
            rtAll.setValue("image name", countAll, imp.getTitle());
        }
        rtAll.setValue("slice", countAll, sliceNb);

        // Add class counts to the summary table
        for (Map.Entry<String, Integer> entry : sortedClasses) {
            String className = entry.getKey();
            // Get count from classCounts, default to 0 if not present
            int classNb = classCounts.getOrDefault(className, 0);
            rtAll.setValue("Nb " + className, countAll, classNb);

        }
        rtAll.setValue("Total objects", countAll, processedDetections.size());

        //noinspection SwitchStatementWithTooFewBranches
        switch (mode) {
            case 1:
//                // For now only save the "round" mean diameter!
//                if (classIdMap.containsKey("round") && classCounts.getOrDefault("round", 0) > 0){
//                    int totalRound = classCounts.getOrDefault("round", 0);
//                    rtAll.setValue("Round mean diam (nm)", countAll, classDiam.getOrDefault("round", 0.)/ totalRound);
//                }
//                break;

                // Save both mean and standard deviation for "round" diameters
                if (classIdMap.containsKey("round") && classCounts.getOrDefault("round", 0) > 0) {
                    List<Double> roundDiameters = classDiams.get("round");
                    if (roundDiameters != null && !roundDiameters.isEmpty()) {
                        int totalRound = roundDiameters.size();

                        // Compute mean
                        double sum = roundDiameters.stream().mapToDouble(Double::doubleValue).sum();
                        double mean = sum / totalRound;

                        // Compute standard deviation
                        double variance = 0.0;
                        double sum_of_squared = 0.0;
                        for (double d : roundDiameters) {
                            sum_of_squared += Math.pow(d, 2);
                            variance += Math.pow(d - mean, 2);
                        }
                        double stdDev = Math.sqrt(variance / totalRound);

                        // Save results
                        rtAll.setValue("Round mean diam (nm)", countAll, mean);
                        rtAll.setValue("Round diam std (nm)", countAll, stdDev);
                        rtAll.setValue("Round : sum of diam squared", countAll, sum_of_squared);
                    }
                }
                break;

        }

        // Display the result tables according to user setting
        if(options.showDetectionResultTables){
            rt.show(rtTableName);
            rtAll.show(rtAllTableName);
        }

        // Create Stack Mask
        if (options.createStackMask) {
            ImagePlus stackMask = SegmentationUtils.createStackMask(imp, processedDetections);
            if (stackMask != null) stackMask.show();
        }

        // Create Instance Mask
        if (options.createInstanceMask) {
            ImagePlus instanceMask = SegmentationUtils.createInstanceMask(imp, processedDetections);
            if (instanceMask != null) instanceMask.show();
        }

        // Create Semantic Mask
        if (options.createSemanticMask) {
            ImagePlus semanticMask = SegmentationUtils.createSemanticMask(imp, processedDetections);
            if (semanticMask != null) semanticMask.show();
        }

        // Create Instance Mask Per Class
        if (options.createInstanceMaskPerClass) {
            ImagePlus instanceMaskPerClass = SegmentationUtils.createInstanceMaskPerClass(imp, processedDetections, classIdMap);
            if (instanceMaskPerClass != null) instanceMaskPerClass.show();
        }
    }

    private static void setGlasbeyLut(ImagePlus imp){
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

}