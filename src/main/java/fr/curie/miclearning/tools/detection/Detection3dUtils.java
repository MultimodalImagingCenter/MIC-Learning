package fr.curie.miclearning.tools.detection;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fr.curie.miclearning.tools.detection.DetectionUtils.setGlasbeyLut;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class Detection3dUtils {
    public enum GroupingMethod { BY_OBJECT, BY_CLASS } // in Roi Manager, are Roi group id defined
    // - by object (in each class, each object is associated to 1 id) (default)
    // - or by class (all objects of the same class have the same id)
    public static void addTrackedRoisToManager(RoiManager manager, MultiFrameDataManager mfdManager, boolean addBb, boolean addShape, GroupingMethod groupingMethod) {
        if (!addBb && !addShape) {return;}
        if (manager == null || mfdManager == null || mfdManager.getDetectionsByFrame().isEmpty()) {
            IJ.log("Error with manager or no detection found. Roi won't be added to roiManager");
            return;
        }

        addTrackedRoisToManager(manager, mfdManager.getDetectionsByFrame(), addBb, addShape, groupingMethod);
        IJ.log("Rois added to RoiManager");
    }

    public static void addTrackedRoisToManager(RoiManager manager, MultiFrameDataManager mfdManager, boolean addBb, boolean addShape) {
        addTrackedRoisToManager(manager, mfdManager,  addBb, addShape, GroupingMethod.BY_OBJECT);
    }

    public static void addTrackedRoisToManager(RoiManager manager, Map<Integer, List<ProcessedDetection>> detectionsByFrame, boolean addBb, boolean addShape, GroupingMethod groupingMethod) {
        if (!addBb && !addShape) {return;}

        for (Map.Entry<Integer, List<ProcessedDetection>> entry : detectionsByFrame.entrySet()) {
            int frame = entry.getKey();
            List<ProcessedDetection> detections  = entry.getValue();
            if (detections.isEmpty()) {continue;}
            for (ProcessedDetection det : detections) {
                int frameIndex = frame+1;// 1-based
                if (addBb  && det.getBoundingBoxRoi() != null) {
                    Roi roi = (Roi) det.getBoundingBoxRoi().clone();
                    roi.setPosition(frameIndex);
                    roi.setGroup(getGroupId(det, groupingMethod));
                    roi.setName(frameIndex + "_" + det.getRoiName());
                    manager.addRoi(roi);
                }

                if (addShape && det.getShapeRoi() != null) {
                    Roi roi = (Roi) det.getShapeRoi().clone();
                    roi.setPosition(frameIndex); // 1-based
                    roi.setGroup(getGroupId(det, groupingMethod));
                    roi.setName(frameIndex + "_" + det.getRoiName());
                    manager.addRoi(roi);
                }
            }
        }
    }

    private static int getGroupId(ProcessedDetection detection, GroupingMethod groupingMethod){
        switch (groupingMethod) {
            case BY_CLASS:
                return detection.getGroupId() % 256; // groupId has to be int between 0 and 255
            case BY_OBJECT:
                return detection.getId() % 256;
            default:
                return 0;
        }
    }

    // ID continuity from one frame to the other (same object on multiple frames)
    public static ImagePlus createInstanceMaskStackWithFixedIds(ImagePlus imp, MultiFrameDataManager mfdManager) {
        int totalFrames = mfdManager.getFrameNumber();
        int width = imp.getWidth();
        int height = imp.getHeight();

        ImageStack originalStack = imp.getStack();
        ImageStack maskStack = new ImageStack(width, height);
        Map<Integer, List<ProcessedDetection>> videoDetectionsRegistry = mfdManager.getDetectionsByFrame();
        for (int f = mfdManager.getFirstFrame(); f <= mfdManager.getLastFrame(); f++) {
            List<ProcessedDetection> detections = videoDetectionsRegistry.getOrDefault(f, new ArrayList<>());
            ImageProcessor sliceProcessor = createAndFillProcessorWithFixedIds(detections, width, height);
            String sliceLabel = originalStack.getSliceLabel(f+1);
            maskStack.addSlice(sliceLabel, sliceProcessor);
        }

        if (maskStack.getSize() == 0) {
            IJ.log("Could not create any slices for the mask stack.");
            return null;
        }

        ImagePlus stackImp = new ImagePlus(imp.getTitle() +  " - instance segmentation", maskStack);

        IJ.log("Instance masks stack created.");
        stackImp.setDisplayRange(0, Math.max(255.0, stackImp.getStatistics().max));
        setGlasbeyLut(stackImp);
        return stackImp;
    }

    private static ImageProcessor createAndFillProcessorWithFixedIds(List<ProcessedDetection> detections, int width, int height) {
        int numInstances = detections.size();
        ImageProcessor processor;

        // If no detections, return an empty ByteProcessor
        if (numInstances == 0) {
            return new ShortProcessor(width, height); // ByteProcessor would be lighter, but if processor are used to create stack, they have to all be same type
        }

        processor = new ShortProcessor(width, height);

        // Fill the processor with instance IDs (globally unique across every concept/class - see
        // the shared object-id counter on the python side - so this is safe with multiple classes too)
        for (ProcessedDetection det : detections) {
            if (det.getShapeRoi() != null) {
                processor.setColor(det.getId()+1); // IDs start from 0 and 0 would fill with black...
                processor.fill(det.getShapeRoi());
            } else {
                IJ.log("Warning: Found detection without a Shape ROI during processor filling. Skipping this instance.");
            }
        }
        processor.setThreshold(0, 0, ImageProcessor.NONE);
        return processor;
    }

    /**
     * Stack version of {@link #createInstanceMaskStackWithFixedIds}, split by class: builds a 4D
     * hyperstack with one time-point per processed frame and, within each time-point, one channel
     * per class (from {@code classIdMap}, ordered by group id then name). Each channel is that
     * class's instances, with the same "ID continuity from one frame to the other" as the plain
     * instance mask (a tracked object keeps the same pixel value in every frame/channel it appears
     * in); a class with no detection on a frame gets an empty channel.
     *
     * @param imp        source image
     * @param mfdManager registry of tracked detections, by frame and by object id
     * @param classIdMap class name -> output group id; defines the channel slices and their order
     * @return a hyperstack ImagePlus (channels = nClasses, slices = 1, frames = nFrames), or
     *         {@code null} when there is nothing to build
     */
    public static ImagePlus createInstanceMaskPerClassStackWithFixedIds(
            ImagePlus imp, MultiFrameDataManager mfdManager, Map<String, Integer> classIdMap) {

        if (classIdMap == null || classIdMap.isEmpty()) {
            IJ.log("No class ID map provided. Cannot create instance-mask-per-class hyperstack.");
            return null;
        }
        Map<Integer, List<ProcessedDetection>> detectionsByFrame = mfdManager.getDetectionsByFrame();
        if (detectionsByFrame.isEmpty()) {
            IJ.log("No detections available. Cannot create instance-mask-per-class hyperstack.");
            return null;
        }

        int width = imp.getWidth();
        int height = imp.getHeight();

        // classes (name, groupId) ordered by groupId then name - defines the C axis
        List<Map.Entry<String, Integer>> classes = new ArrayList<>(classIdMap.entrySet());
        classes.sort(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()));

        ImageStack originalStack = imp.getStack();
        ImageStack stack = new ImageStack(width, height);

        // hyperstack order is czt (channel, then slice, then frame): for each frame, append one
        // channel per class, in the same frame order createInstanceMaskStackWithFixedIds uses.
        int nFramesBuilt = 0;
        for (int f = mfdManager.getFirstFrame(); f <= mfdManager.getLastFrame(); f++) {
            List<ProcessedDetection> detections = detectionsByFrame.getOrDefault(f, Collections.emptyList());
            Map<String, List<ProcessedDetection>> byClassName = new HashMap<>();
            for (ProcessedDetection det : detections) {
                if (det.getShapeRoi() == null) continue;
                byClassName.computeIfAbsent(det.getClassName(), k -> new ArrayList<>()).add(det);
            }

            String frameLabel = originalStack.getSliceLabel(f + 1);
            for (Map.Entry<String, Integer> cls : classes) {
                List<ProcessedDetection> classDetections = byClassName.getOrDefault(cls.getKey(), Collections.emptyList());
                ImageProcessor processor = createAndFillProcessorWithFixedIds(classDetections, width, height);
                stack.addSlice(cls.getKey() + " - " + frameLabel + " (" + classDetections.size() + " instances)", processor);
            }
            nFramesBuilt++;
        }

        if (stack.getSize() == 0) {
            IJ.log("Could not create any slices for the instance-mask-per-class hyperstack.");
            return null;
        }

        ImagePlus result = new ImagePlus(imp.getTitle() + " - instance mask per class", stack);
        result.setDimensions(classes.size(), 1, nFramesBuilt);
        result.setOpenAsHyperStack(true);
        IJ.log("Instance-mask-per-class hyperstack created (" + classes.size() + " class(es) x " + nFramesBuilt + " frame(s)).");
        result.setDisplayRange(0, Math.max(255.0, result.getStatistics().max));
        setGlasbeyLut(result);
        return result;
    }

}