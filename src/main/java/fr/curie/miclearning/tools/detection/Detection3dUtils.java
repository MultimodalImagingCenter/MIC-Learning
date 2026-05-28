package fr.curie.miclearning.tools.detection;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static fr.curie.miclearning.tools.detection.DetectionUtils.setGlasbeyLut;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class Detection3dUtils {

    public static void add3dRoisToManager(RoiManager manager, MultiFrameDataManager mfdManager, boolean addBb, boolean addShape) {
        if (!addBb && !addShape) {return;}
        if (manager == null || mfdManager == null || mfdManager.getDetectionsByFrame().isEmpty()) {
            IJ.log("Error with manager or no detection found. Roi won't be added to roiManager");
            return;
        }

        for (Map.Entry<Integer, List<ProcessedDetection>> entry : mfdManager.getDetectionsByFrame().entrySet()) {
            int frame = entry.getKey();
            List<ProcessedDetection> detections  = entry.getValue();
            for (ProcessedDetection det : detections) {
                if (addBb  && det.getBoundingBoxRoi() != null) {
                    Roi roi = (Roi) det.getBoundingBoxRoi().clone();
                    roi.setPosition(frame + 1);
                    roi.setGroup(det.getId());
                    roi.setName(det.getRoiName());
                    manager.addRoi(roi);
                }

                if (addShape && det.getShapeRoi() != null) {
                    Roi roi = (Roi) det.getShapeRoi().clone();
                    roi.setPosition(frame + 1); // 1-based
                    roi.setGroup(det.getId());
                    roi.setName(det.getRoiName());
                    manager.addRoi(roi);
                }
            }
        }
        IJ.log("Rois added to RoiManger");

    }

    public static ImagePlus createInstanceMaskStack(ImagePlus imp, MultiFrameDataManager mfdManager) {
        int totalFrames = mfdManager.getMaxFrameNumber();
        int width = imp.getWidth();
        int height = imp.getHeight();

        ImageStack maskStack = new ImageStack(width, height);
        Map<Integer, List<ProcessedDetection>> videoDetectionsRegistry = mfdManager.getDetectionsByFrame();
        for (int f = 0; f < totalFrames; f++) {
            List<ProcessedDetection> detections = videoDetectionsRegistry.getOrDefault(f, new ArrayList<>());
            ImageProcessor sliceProcessor = createAndFillProcessor(detections, width, height);
            maskStack.addSlice("Frame " + (f + 1), sliceProcessor);
        }

        if (maskStack.getSize() == 0) {
            IJ.log("Could not create any slices for the mask stack.");
            return null;
        }

        ImagePlus stackImp = new ImagePlus("Instance Segmentation Stack", maskStack);
        IJ.log("Instance masks stack created");
        setGlasbeyLut(stackImp);
        return stackImp;
    }

    private static ImageProcessor createAndFillProcessor(List<ProcessedDetection> detections, int width, int height) {
        int numInstances = detections.size();
        ImageProcessor processor;

        // If no detections, return an empty ByteProcessor
        if (numInstances == 0) {
            return new ByteProcessor(width, height);
        }

        processor = new ShortProcessor(width, height);

        // Fill the processor with instance IDs
        for (ProcessedDetection det : detections) {
            if (det.getShapeRoi() != null) {
                int colorId = det.getId() == 0 ? 255 : det.getId(); // 0 will fill processor with black... and unlikely >254 objects on 1 image
                processor.setColor(colorId);
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
            add3dRoisToManager(roiManager, mfdManager, options.addToRoiManagerBB, options.addToRoiManagerShapes);
            roiManager.setVisible(true);
            roiManager.runCommand("Show All"); // Make ROIs visible
        }

        if (options.createInstanceMask){
            ImagePlus stackMask = createInstanceMaskStack(imp, mfdManager);
            if (stackMask != null) stackMask.show();
        }
    }
}
