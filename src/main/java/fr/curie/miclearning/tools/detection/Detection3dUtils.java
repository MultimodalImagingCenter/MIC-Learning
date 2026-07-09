package fr.curie.miclearning.tools.detection;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
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
                if (addBb  && det.getBoundingBoxRoi() != null) {
                    Roi roi = (Roi) det.getBoundingBoxRoi().clone();
                    roi.setPosition(frame + 1);
                    roi.setGroup(getGroupId(det, groupingMethod));
                    roi.setName(det.getRoiName());
                    manager.addRoi(roi);
                }

                if (addShape && det.getShapeRoi() != null) {
                    Roi roi = (Roi) det.getShapeRoi().clone();
                    roi.setPosition(frame + 1); // 1-based
                    roi.setGroup(getGroupId(det, groupingMethod));
                    roi.setName(det.getRoiName());
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

        // Fill the processor with instance IDs
        // WARNING: id are unique inside a class, but not if multiple classes
        // TODO : find a solution if multiple classes, while conserving id continuity
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


    public static void generate3dOutputs(ImagePlus imp,  MultiFrameDataManager mfdManager, DetectionUtils.OutputOptions options) {
        // Add to ROI Manager
        if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
            RoiManager roiManager = getRoiManager();
            addTrackedRoisToManager(roiManager, mfdManager, options.addToRoiManagerBB, options.addToRoiManagerShapes);
            roiManager.setVisible(true);
            roiManager.runCommand("Show All"); // Make ROIs visible
        }

        if (options.createInstanceMask){
            ImagePlus stackMask = createInstanceMaskStackWithFixedIds(imp, mfdManager);
            if (stackMask != null) stackMask.show();
        }
    }
}