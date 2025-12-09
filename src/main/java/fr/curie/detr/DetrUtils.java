package fr.curie.detr;

import ai.djl.Model;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.repository.zoo.ZooModel;
import fr.curie.detr.ModelConfig;
import fr.curie.detr.SegmentationUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

import static fr.curie.detr.SegmentationUtils.loadClassIDsFromModel;

public class DetrUtils {

    // --- Detr Dialog methods ---
//    public static SegmentationUtils.OutputOptions askUserForDetrOutputs(Integer mode) {
//        GenericDialog gd = new GenericDialog("Detr Segmentation Outputs");
//        addDetrOutputDialog(gd, mode);
//        gd.showDialog();
//        if (gd.wasCanceled()) {
//            return null; // User canceled
//        }
//
//        return getDetrOutputAnswer(gd);
//    }

    public static void addDetrOutputDialog(GenericDialog gd, Integer mode) {
        gd.addMessage("Configure inputs/outputs of the run :");
        // gd.addCheckbox("Add_Bounding_Boxes to ROI Manager", true);
        //noinspection SwitchStatementWithTooFewBranches
        switch (mode) {
            case 1:
                gd.addNumericField("Pixel Size (nm)", 0.25, 3, 6, "nm/px");
                break;
        }
        gd.addCheckbox("Apply Preprocessing Macro", true);
        gd.addCheckbox("Show detection result tables", true);
        gd.addCheckbox("Save result data", true);
        gd.addCheckbox("Clear previous ROIs/Result tables/Log", true);

    }

    public static SegmentationUtils.OutputOptions getDetrOutputAnswer(GenericDialog gd, Integer mode) {
        SegmentationUtils.OutputOptions options = new SegmentationUtils.OutputOptions();
        // options.addToRoiManagerBB = gd.getNextBoolean();
        // IJ.log("Add Bounding Boxes to ROI Manager : "+options.addToRoiManagerBB);

        //noinspection SwitchStatementWithTooFewBranches
        switch (mode) {
            case 1:
                options.pixelSize = gd.getNextNumber();
                IJ.log("Pixel Size : "+options.pixelSize + "nm/px");
                break;
        }
        options.applyPreproMacro = gd.getNextBoolean();
        IJ.log("Apply Preprocessing Macro : "+options.applyPreproMacro);
        options.showDetectionResultTables = gd.getNextBoolean();
        IJ.log("Show detection result tables : "+options.showDetectionResultTables);
        options.saveResultsData = gd.getNextBoolean();
        IJ.log("Save result data : "+options.saveResultsData);
        options.clearResults = gd.getNextBoolean();
        IJ.log("Clear previous ROIs/Result tables/Log : "+options.clearResults);
        IJ.log("===========================================\n");
        return options;
    }

    public static Map<String, Integer> getClassIdMap(ZooModel<ImagePlus, DetectedObjects> model, ModelConfig config){
        Map<String, Integer> classIdMap = null;
        if (config.getSynsetFilePath() != null && Files.exists(config.getSynsetFilePath())) {
            classIdMap = loadClassIDsFromModel(model, config.getSynsetFilePath().getFileName().toString());
            if (classIdMap == null) {
                IJ.log("Failed to load class IDs from: " + config.getSynsetFilePath() + ". Using default numeration.");
            } else {
                //IJ.log("Successfully loaded class IDs from: "+ config.getSynsetFilePath().getFileName().toString());
            }
        } else if (config.getSynsetFileName() != null) {
            classIdMap = loadClassIDsFromModel(model, config.getSynsetFileName());
            if (classIdMap == null) {
                IJ.log("Failed to load class IDs using name: " + config.getSynsetFileName() + " from serving.properties. Using default numeration.");
            } else {
                IJ.log("Successfully loaded class IDs using name: " + config.getSynsetFileName());
            }
        } else {
            IJ.log("No synset/labels file specified in serving.properties.");
        }
        if (classIdMap != null){
            IJ.log("===========================================");
            IJ.log(" --- Synset Class IDs ---");
            IJ.log("------------------------------------------------------------------------------");
            for (Map.Entry<String, Integer> entry : classIdMap.entrySet()) {
                IJ.log(entry.getKey() + ": " + entry.getValue());
            }
            IJ.log("===========================================\n");
        }
        return classIdMap;
    }

    public static void applyMacro(Model model, ImagePlus imp, String macroName) throws IOException {

        String macroContent = model.getArtifact(macroName, is -> {
            try {
                return IOUtils.toString(is, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        IJ.log("Running macro : " + macroName);
        IJ.log("------------------------------------------------------------------------------");
        IJ.log(macroContent);
        IJ.log("===========================================");

        URL macroFile = model.getArtifact(macroName);
        try {
            File file = new File(macroFile.toURI());
            IJ.runMacroFile(file.getPath());
        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    // Helper method to format seconds as "Xmin Ys" or just "Ys"
    public static String formatTime(double seconds) {
        Duration duration = Duration.ofSeconds((long) seconds);
        long minutes = duration.toMinutes();
        long secs = duration.minusMinutes(minutes).getSeconds();
        //noinspection SpellCheckingInspection
        return (minutes > 0) ? String.format("%dmin %ds", minutes, secs) : String.format("%.1fs", seconds);
    }

}
