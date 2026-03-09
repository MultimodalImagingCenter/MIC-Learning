package fr.curie.miclearning.tools;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import ai.djl.modality.cv.output.Mask;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.*;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ij.IJ.runMacro;
import static ij.gui.Roi.getGroupName;
import static ij.gui.Roi.setGroupName;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class ImageJUtils {

    private static final Logger log = LoggerFactory.getLogger(ImageJUtils.class);

    private ImageJUtils() {
    }

    /**
     * Loads an image file into an ImageJ ImagePlus object.
     *
     * @param imagePath Path to the image file.
     * @param title     Title for the ImagePlus object.
     * @return An Optional containing the ImagePlus, or empty if loading fails.
     */
    public static ImagePlus loadImageJImage(Path imagePath, String title) {
        log.info("Loading ImageJ image from: {}", imagePath);
        if (!Files.exists(imagePath)) {
            log.error("Image file not found at path: {}", imagePath);
            return null;
        }
        try {
            ImagePlus imp = IJ.openImage(imagePath.toString());
            if (imp != null) {
                log.info("ImagePlus created successfully");
                imp.setTitle(title);
                return imp;
            } else {
                return null;
            }
        } catch (Exception e) {
            log.error("Unexpected error loading ImageJ image from path: {}", imagePath, e);
            return null;
        }
    }

    /**
     * Loads the content of an ImageJ macro file.
     *
     * @param macroPath Path to the .ijm macro file.
     * @return An Optional containing the macro content as a String, or empty if loading fails.
     */
    public static String loadMacro(Path macroPath) {
        log.info("Loading macro from: {}", macroPath);
        if (!Files.exists(macroPath)) {
            log.error("Macro file not found at path: {}", macroPath);
            return null;
        }
        try {
            String macro = FileUtils.readFileToString(macroPath.toFile(), StandardCharsets.UTF_8);
            log.info("Macro loaded successfully.");
            return macro;
        } catch (IOException e) {
            log.error("Failed to read macro file: {}", macroPath, e);
            return null;
        }
    }

    /**
     * Shows an ImageJ image and runs a macro on it.
     * This method relies on ImageJ's GUI context (WindowManager)
     * and modifies the active image or creates new ones.
     * Useful only to run a macro outside of ImageJ
     *
     * @param impToShow    The ImagePlus object to display before running the macro.
     * @param macroContent The macro script to run.
     */
    public static void runImageJMacro(ImagePlus impToShow, String macroContent) {
        log.info("Running ImageJ macro for image '{}'", impToShow.getTitle());

        // Display image -> needed for ImageJ runMacro to find the image by default
        impToShow.show();

        // Check if ImageJ recognizes an active image
        if (WindowManager.getCurrentImage() == null) {
            log.warn("ImageJ's WindowManager does not report a current image after showing '{}'. Macro might still work if it selects images explicitly.", impToShow.getTitle());
        } else if (WindowManager.getCurrentImage() != impToShow) {
            log.warn("The active image in ImageJ ('{}') is different from the one just shown ('{}'). Macro might operate on the wrong image.", WindowManager.getCurrentImage().getTitle(), impToShow.getTitle());
        } else {
            log.debug("Image '{}' appears active in ImageJ.", WindowManager.getCurrentImage().getTitle());
        }

        // Run the macro
        log.debug("Executing macro content...");
        runMacro(macroContent);
        log.info("Finished running macro");
    }

    /**
     * Converts an ImagePlus to a float32 NDArray in HWC format.
     * - If the image is COLOR_RGB, it's treated as a single 3-channel image.
     * - If it's a grayscale stack, each slice becomes a channel.
     * - If it's a single grayscale image, it's treated as a 1-channel image.
     *
     * @param imp     The input ImagePlus.
     * @param manager The NDManager for creating the NDArray.
     * @return An NDArray in HWC layout.
     */
    public static NDArray ImagePlusToNDArray(ImagePlus imp, NDManager manager) {
        int width = imp.getWidth();
        int height = imp.getHeight();

        // Case 1: Handle COLOR_RGB image. Only the first slice is used.
        if (imp.getType() == ImagePlus.COLOR_RGB) {
            return fromColorRgb(imp.getProcessor(), width, height, manager);
        }

        // Case 2: Handle all other types (GRAY8, GRAY16, GRAY32) as stacks.
        return fromGrayscaleStack(imp, width, height, manager);
    }

    /**
     * Converts a grayscale stack (or single-slice image) into an HWC NDArray.
     */
    private static NDArray fromGrayscaleStack(ImagePlus imp, int width, int height, NDManager manager) {
        ImageStack stack = imp.getStack();
        int numChannels = stack.getSize();
        int pixelCountPerChannel = width * height;

        // 1. Extract pixel data from all slices into a temporary 2D float array.
        float[][] channelData = new float[numChannels][pixelCountPerChannel];
        for (int c = 0; c < numChannels; c++) {
            ImageProcessor sliceProcessor = stack.getProcessor(c + 1); // ImageJ stacks are 1-indexed
            channelData[c] = pixelsAsFloatTab(sliceProcessor, imp.getType());
        }

        // Step 2: Interleave the channel data into a single FloatBuffer in HWC order.
        FloatBuffer buffer = FloatBuffer.allocate(numChannels * pixelCountPerChannel);
        for (int i = 0; i < pixelCountPerChannel; i++) { // For each pixel position
            for (int c = 0; c < numChannels; c++) {      // For each channel
                buffer.put(channelData[c][i]);
            }
        }
        buffer.rewind();

        // Step 3: Create the final NDArray with the HWC shape.
        Shape shape = new Shape(height, width, numChannels);
        return manager.create(buffer, shape, DataType.FLOAT32);
    }

    /**
     * Converts a grayscale image processor into an HWC NDArray with 3 channels, to be converted to DJL Image
     */
    private static NDArray fromGrayscale(ImageProcessor ip, int width, int height, NDManager manager) {
        int pixelCount = width * height;
        // extract raw data
        float[] g = pixelsAsFloatTab(ip, ip.getBitDepth());

        // Create a buffer and copy the processor
        FloatBuffer buffer = FloatBuffer.allocate(3 * pixelCount);
        // interleave data into a single HWC byte array
        for (int i = 0; i < pixelCount; i++) {
            buffer.put(g[i]);
            buffer.put(g[i]);
            buffer.put(g[i]);
        }
        buffer.rewind();

        Shape shape = new Shape(height, width, 3); // (H, W, 3)
        return manager.create(buffer, shape, DataType.FLOAT32);
    }

    /**
     * Helper function to extract pixels from any supported grayscale processor as a float array.
     */
    private static float[] pixelsAsFloatTab(ImageProcessor ip, int imageType) {
        switch (imageType) {
            case 8:
            case ImagePlus.GRAY8:
                byte[] pixels8 = (byte[]) ip.getPixels();
                float[] floatPixels8 = new float[pixels8.length];
                for (int i = 0; i < pixels8.length; i++) {
                    floatPixels8[i] = Byte.toUnsignedInt(pixels8[i]);
                }
                return floatPixels8;

            case 16 :
            case ImagePlus.GRAY16:
                short[] pixels16 = (short[]) ip.getPixels();
                float[] floatPixels16 = new float[pixels16.length];
                for (int i = 0; i < pixels16.length; i++) {
                    floatPixels16[i] = Short.toUnsignedInt(pixels16[i]);
                }
                return floatPixels16;

            case 32 :
            case ImagePlus.GRAY32:
                return (float[]) ip.getPixels();

            default:
                throw new IllegalArgumentException("Unsupported ImagePlus type for stack processing: " + imageType);
        }
    }

    /**
     * Converts a ColorProcessor to an HWC NDArray.
     * This uses your confirmed working solution.
     */
    private static NDArray fromColorRgb(ImageProcessor ip, int width, int height, NDManager manager) {
        int pixelCount = width * height;
        byte[] r = new byte[pixelCount];
        byte[] g = new byte[pixelCount];
        byte[] b = new byte[pixelCount];
        // extract raw data from each channel
        ((ColorProcessor) ip).getRGB(r, g, b);

        // Create a single buffer for all 3 channels
        FloatBuffer buffer = FloatBuffer.allocate(3 * pixelCount);
        // interleave data into a single HWC byte array
        for (int i = 0; i < pixelCount; i++) {
            buffer.put(Byte.toUnsignedInt(r[i]));
            buffer.put(Byte.toUnsignedInt(g[i]));
            buffer.put(Byte.toUnsignedInt(b[i]));
        }
        buffer.rewind();

        Shape shape = new Shape(height, width, 3); // (H, W, 3)
        return manager.create(buffer, shape, DataType.FLOAT32);
    }

    public static Image imagePlusToDjlImage(ImagePlus imp, NDManager manager){
        return imageProcessorToDjlImage(imp.getProcessor(), manager);
    }

    public static Image imagePlusToDjlImage(ImagePlus imp){
        return imageProcessorToDjlImage(imp.getProcessor());
    }


    public static Image imageProcessorToDjlImage(ImageProcessor ip, NDManager manager) {
        int width = ip.getWidth();
        int height = ip.getHeight();
        NDArray ndArray;
        if (ip instanceof ColorProcessor) {
            ndArray = fromColorRgb(ip, width, height, manager);
        } else {
            ndArray = fromGrayscale(ip, width, height, manager);
        }

        Image djlImage = ImageFactory.getInstance().fromNDArray(ndArray);
        log.debug("Successfully converted processor to DJL Image");
        return djlImage;
    }

    public static Image imageProcessorToDjlImage(ImageProcessor ip) {
        NDManager manager = NDManager.newBaseManager();
        return imageProcessorToDjlImage(ip, manager);
    }

    public static ImagePlus NDArray2ImageStack(NDArray ndarray){
        return NDArray2ImageStack(ndarray, "result stack");
    }

    public static ImagePlus NDArray2ImageStack(NDArray ndarray, String title) {
        try (NDArray outputs = get3DTensor(ndarray)) {

            Shape shape = outputs.getShape();
            long[] dims = shape.getShape();

            int channels;
            int height;
            int width;
            boolean isCHW;

            //  determine tensor format (CHW vs. HWC)
            //  Assuming that the channel dimension is the smallest of the three.
            if (dims[0] < dims[1] && dims[0] < dims[2]) {
                isCHW = true;
                channels = (int) dims[0];
                height = (int) dims[1];
                width = (int) dims[2];
            } else {
                isCHW = false;
                height = (int) dims[0];
                width = (int) dims[1];
                channels = (int) dims[2];
            }
            IJ.log("Interpreted as " + (isCHW ? "CHW" : "HWC") + " with " + channels + " channels.");
            ImageStack stack = new ImageStack(width, height);

            // Iterate through each channel, create a processor, and add it to the stack.
            for (int c = 0; c < channels; c++) {
                try (
                        // Step 1: Slice the tensor to get the channel view.
                        NDArray channelData = isCHW ? outputs.get(c) : outputs.get("...," + c);
                        NDArray contiguousChannelData = channelData.duplicate()
                ) {

                    // Convert the 2D channel data into a 1D float array for ImageJ.
                    float[] pixels = contiguousChannelData.toFloatArray();

                    FloatProcessor fp = new FloatProcessor(width, height);
                    fp.setPixels(pixels);

                    // Add the processor to the stack
                    stack.addSlice("Channel " + (c + 1), fp);

                }
            }
            return new ImagePlus(title, stack);
        }
    }

    /**
     * Helper function to handle 3D and 4D tensors, returning a 3D tensor view.
     */
    private static NDArray get3DTensor(NDArray rawOutputs) {
        long[] dims = rawOutputs.getShape().getShape();
        if (dims.length == 4) {
            if (dims[0] != 1) {
                throw new IllegalArgumentException("Unsupported batch size. Expected 1, got " + dims[0]);
            }
            // Squeeze out the batch dimension. This returns a view, not a copy
            // The original rawOutputs still holds the memory.
            return rawOutputs.squeeze(0);
        } else if (dims.length == 3) {
            // Return the original array itself.
            return rawOutputs;
        } else {
            throw new IllegalArgumentException("Unsupported NDArray shape. Expected 3 or 4 dimensions, got " + dims.length);
        }
    }


    public static Roi createRoiFromBB(BoundingBox box, double imageWidth, double imageHeight) {
        Rectangle rectangle = box.getBounds();
        int x = (int) (rectangle.getX() * imageWidth);
        int y = (int) (rectangle.getY() * imageHeight);
        int width = (int) (rectangle.getWidth() * imageWidth);
        int height = (int) (rectangle.getHeight() * imageHeight);

        Roi roi = new Roi(x, y, width, height);
        return roi;
    }

    public static Roi createRoiFromMask(Mask mask, int imageWidth, int imageHeight) {
        float[][] probDist = mask.getProbDist();
        int maskHeight = probDist.length;
        int maskWidth = probDist[0].length;
        Rectangle rect = mask.getBounds();

        // Calculate absolute pixel coordinates for the bounding box
        int boxX = (int) (rect.getX() * imageWidth);
        int boxY = (int) (rect.getY() * imageHeight);
        int boxWidth = (int) (rect.getWidth() * imageWidth);
        int boxHeight = (int) (rect.getHeight() * imageHeight);

        // Create the temp mask processor
        ByteProcessor processor = new ByteProcessor(imageWidth, imageHeight); // Default 0

        // Iterate through target pixels of the detection mask
        for (int targetY = boxY; targetY < boxY + boxHeight; targetY++) {
            for (int targetX = boxX; targetX < boxX + boxWidth; targetX++) {

                // Map target coordinates to detection mask coordinates
                float X_f = (float) targetX / imageWidth * maskWidth;
                float Y_f = (float) targetY / imageHeight * maskHeight;

                int j = (int) X_f;
                int k = (int) Y_f;

                // Boundary check for calculated small indices
                if (j >= 0 && j < imageWidth && k >= 0 && k < imageHeight) {
                    // Check probability
                    if (probDist[k][j] > 0.5f) { // Threshold
                        // Write mask
                        processor.putPixelValue(targetX, targetY, 255);
                    }
                }
            }
        }

        // create ROI from instance mask
        processor.setThreshold(128, 255, ImageProcessor.BLACK_AND_WHITE_LUT);
        ThresholdToSelection t2s = new ThresholdToSelection();
        Roi roi = t2s.convert(processor);

        return roi;
    }

    /**
     * Creates
     *
     * @param imp       The original ImagePlus the detection was run on.
     * @param detection The DetectedObjects result from the YOLO model.
     */
    public static List<Roi> roiFromDetection(ImagePlus imp, DetectedObjects detection) {
        if (imp == null || detection == null) {
            IJ.log("Error: Input ImagePlus or DetectedObjects is null.");
            return null;
        }

        ImageProcessor ip = imp.getProcessor();
        int imageWidth = ip.getWidth();
        int imageHeight = ip.getHeight();

        // open ROI manager
        RoiManager roiManager = getRoiManager();
        roiManager.reset(); // Delete previous ROIs

        // Check if any object detected
        List<DetectedObjects.DetectedObject> list = detection.items();
        if (list == null || list.isEmpty()) {
            IJ.log("No objects were detected.");
            return null;
        }

        // count the total number of instances
        int instanceId = 0;
        // Map to count the occurrences of each class
        Map<String, Integer> classCounts = new HashMap<>();
        // Map to associate each class with a ROI group number
        Map<String, Integer> classGroup = new HashMap<>();
        int classMax = 1;

        // create list of shapeROI
        List<Roi> shapeRois = new ArrayList<>();

        // Iterate through all DetectedObject
        for (DetectedObjects.DetectedObject result : list) {
            String className = result.getClassName();
            BoundingBox box = result.getBoundingBox();

            if (className == null || className.trim().isEmpty() || box == null) {
                IJ.log("Detection ignored: empty class name or missing BoundingBox.");
                continue;
            }

            // Increment the instance counter and the counter for this class
            instanceId++;
            int countForClass = classCounts.getOrDefault(className, 0) + 1;
            classCounts.put(className, countForClass);

            // Find group number, or create one
            int groupNumber = classGroup.getOrDefault(className, classMax);
            if (classMax == groupNumber) {
                classGroup.put(className, groupNumber);
                setGroupName(groupNumber, className);
                classMax++;
            }

            String roiName = className + "_" + countForClass + "_" + String.format("%.5f", result.getProbability());

            // --- Bounding Box ROI
            Roi rectRoi = createRoiFromBB(box, imageWidth, imageHeight);
            // set name and group
            rectRoi.setName(roiName);
            rectRoi.setGroup(groupNumber);

            // add ROI to imp and ROI manager
            imp.setRoi(rectRoi);
            roiManager.addRoi(rectRoi);


            // --- Shape ROI
            if (box instanceof Mask) {
                Mask mask = (Mask) box;
                float[][] probDist = mask.getProbDist();

                if (probDist == null || probDist.length == 0 || probDist[0].length == 0) {
                    IJ.log("Instance " + instanceId + " ('" + className + "') has empty mask data. Skipping.");
                    continue;
                }

                // Get Mask for the detection
                Roi shapeRoi = createRoiFromMask(mask, imageWidth, imageHeight);
                if (shapeRoi == null) {
                    IJ.log("Warning: No shape ROI generated for " + roiName + " (empty mask after thresholding?). Skipping.");
                    continue; // Skip to the next detection
                }
                // set ROI name and group + add to list
                shapeRoi.setName(roiName);
                shapeRoi.setGroup(groupNumber);
                shapeRois.add(shapeRoi);

            }

            IJ.log("ROIs created for " + roiName);
        }
        return shapeRois;
    }

    public static void resetRMandRT(){
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt != null) {
            rt.reset();
        }

        RoiManager rm = RoiManager.getRoiManager();
        if (rm != null) {
            rm.reset();
        }
    }

    public static void deleteRois(Roi[] roiList, RoiManager rm) {
        if (rm == null || roiList == null) return;
        Roi[] allRois = rm.getRoisAsArray();
        ArrayList<Integer> indicesList = new ArrayList<>();

        // 1. Find the indices of the ROIs provided in the roiList
        for (Roi target : roiList) {
            for (int i = 0; i < allRois.length; i++) {
                if (allRois[i].equals(target)) {
                    indicesList.add(i);
                    break;
                }
            }
        }
        // 2. Convert to primitive array for RoiManager
        if (!indicesList.isEmpty()) {
            int[] indicesArray = indicesList.stream().mapToInt(i -> i).toArray();

            // 3. Select the ROIs and execute the delete command
            rm.setSelectedIndexes(indicesArray);
            rm.runCommand("Delete");
        }

    }

    public static ImagePlus stackMaskFromRoi(ImagePlus imp, List<Roi> shapeRois){
        ImageProcessor ip = imp.getProcessor();
        int imageWidth = ip.getWidth();
        int imageHeight = ip.getHeight();
        // create stack for all masks
        ImageStack maskStack = new ImageStack();

        for (Roi roi : shapeRois){
            // create processor for this mask
            ByteProcessor processor = new ByteProcessor(imageWidth, imageHeight);
            processor.setColor(255); // all shapes are same value

            // fill the shape
            processor.fill(roi);

            // add processor to stack
            maskStack.addSlice(roi.getName(),processor);
        }
        if (maskStack.getSize() > 0) {
            ImagePlus stackedImage = new ImagePlus("Instance Mask Stack (" + shapeRois.size() + " instances)", maskStack);
            IJ.log("Mask stack created.");
            return stackedImage;
        } else {
            IJ.log("No masks were added to the stack.");
            return null;
        }
    }

    public static ImagePlus stackMaskFromDetection2(ImagePlus imp, DetectedObjects detection){
        List<Roi> shapeRois = roiFromDetection(imp, detection);
        return (shapeRois != null)? stackMaskFromRoi(imp, shapeRois) : null;
    }

    public static ImagePlus instanceMaskFromRoi(ImagePlus imp, List<Roi> shapeRois){
        ImageProcessor ip = imp.getProcessor();
        int imageWidth = ip.getWidth();
        int imageHeight = ip.getHeight();
        // create ImageProcessor for all instances
        // For now, only consider number max instances = 255
        // TODO handle cases with more instances
        ByteProcessor processor = new ByteProcessor(imageWidth, imageHeight);
        //counter to increment fill color
        int instanceId = 0;

        for (Roi roi : shapeRois) {

            instanceId++;
            processor.setColor(instanceId);

            // fill the shape
            processor.fill(roi);
        }

        return new ImagePlus("Instances Mask (" + instanceId + " instances)", processor);
    }

    public static ImagePlus instanceMaskFromDetection2(ImagePlus imp, DetectedObjects detection){
        List<Roi> shapeRois = roiFromDetection(imp, detection);
        return (shapeRois != null)? instanceMaskFromRoi(imp, shapeRois) : null;
    }

    public static ImagePlus semanticMaskFromRoi(ImagePlus imp, List<Roi> shapeRois){
        ImageProcessor ip = imp.getProcessor();
        int imageWidth = ip.getWidth();
        int imageHeight = ip.getHeight();
        // create ImageProcessor for all instances
        // For now, only consider number max classes = 255
        // TODO handle cases with more classes
        ByteProcessor processor = new ByteProcessor(imageWidth, imageHeight);
        //counter to increment fill color

        for (Roi roi : shapeRois) {
            // Group is already an incremental variable
            int groupId = roi.getGroup();
            processor.setColor(groupId);

            // fill the shape
            processor.fill(roi);
        }
        return new ImagePlus("Semantic Mask (" + shapeRois.size() + " instances)", processor);
    }

    public static ImagePlus semanticMaskFromDetection(ImagePlus imp, DetectedObjects detection){
        List<Roi> shapeRois = roiFromDetection(imp, detection);
        return (shapeRois != null)? semanticMaskFromRoi(imp, shapeRois) : null;
    }

    public static ImagePlus instanceMaskPerClassesFromRoi(ImagePlus imp, List<Roi> shapeRois){
        ImageProcessor ip = imp.getProcessor();
        int imageWidth = ip.getWidth();
        int imageHeight = ip.getHeight();
        // create ImageProcessor for all instances
        // For now, only consider number max instances = 255
        // TODO handle cases with more instances

        // create stack for all masks
        ImageStack classStack = new ImageStack();
        // Map to store the slice of each class
        Map<Integer, Integer> classSliceId = new HashMap<>();
        // Map to count the occurrences of each class
        Map<Integer, Integer> classCounts = new HashMap<>();

        for (Roi roi : shapeRois) {
            int groupId = roi.getGroup();

            // If processor for group not created, create it and add to stack
            if (!classSliceId.containsKey(groupId)){
                String className = getGroupName(groupId);
                classSliceId.put(groupId, classStack.size()+1);
                ByteProcessor processor = new ByteProcessor(imageWidth, imageHeight);
                // add processor to stack
                classStack.addSlice(className, processor);
            }

            // get processor for group
            ImageProcessor processor = classStack.getProcessor(groupId);

            // Increment  the counter for this class
            int countForClass = classCounts.getOrDefault(groupId, 0) + 1;
            classCounts.put(groupId, countForClass);
            processor.setColor(countForClass);

            // fill the shape
            processor.fill(roi);
        }
        return new ImagePlus("Instances Mask for each class (" + classStack.size() + "classes)", classStack);
    }

    public static ImagePlus instanceMaskPerClassesFromDetection(ImagePlus imp, DetectedObjects detection){
        List<Roi> shapeRois = roiFromDetection(imp, detection);
        return (shapeRois != null)? instanceMaskPerClassesFromRoi(imp, shapeRois) : null;
    }
}

