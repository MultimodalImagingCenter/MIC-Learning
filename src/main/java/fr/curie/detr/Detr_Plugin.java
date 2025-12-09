package fr.curie.detr;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.detr.DjlModelLoaderNew;
import fr.curie.detr.ModelConfig;
import fr.curie.detr.configurators.DetrConfigurator;
import fr.curie.detr.configurators.DetrDomvConfigurator;
import fr.curie.detr.configurators.TranslatorConfigurator;
import fr.curie.detr.dialogs.ModelDialogs;
import fr.curie.detr.SegmentationUtils;
import fr.curie.yolo.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import ij.process.ShortProcessor;
import ij.text.TextWindow;

import static fr.curie.detr.DetrUtils.formatTime;
import static fr.curie.detr.DetrUtils.getClassIdMap;
import static fr.curie.detr.dialogs.ModelDialogs.addInitialDialogFields;
import static fr.curie.detr.SegmentationUtils.*;
import static ij.IJ.*;
import static ij.plugin.frame.RoiManager.getRoiManager;


public class Detr_Plugin implements ExtendedPlugInFilter {
    protected static ImagePlus imp;
    protected PlugInFilterRunner pfr;

    private static Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS;

    private final String[] engineChoices = {"", "PyTorch"};
    private final String[] deviceChoices = {"cpu", "gpu"};

    protected static OutputOptions options;
    ZooModel<ImagePlus, DetectedObjects> model;
    ModelConfig config;
    Map<String, Integer> classIdMap;
    Integer stackSize;
    private AtomicInteger passCounter = new AtomicInteger(0);
    private int setupParam;
    // Map to store result summary data
    Map<String, Integer> summary = new HashMap<>();
    // Store run times
    double totalRunTimeSeconds = 0;
    double preproTimeSeconds = 0;
    long runStartTime = 0;
    String archiveLogTitle = null;
    String dir = null;
    boolean processSingleSlice;
    String preProcessmacroName;
    boolean applyMacroCondition;

    // Run mode (lnp=0, domv=1)
    public static Integer mode=0;
    public final Map<String, Integer> modeMap = new HashMap<String, Integer>() {{
        put("lnp", 0);
        put("domv", 1);
    }};

    // Parallelization for now not activated : slower (+ current slice issue in implementation)
    private final int flags = DOES_8G | DOES_RGB | DOES_16; //| NO_CHANGES; // | PARALLELIZE_STACKS;

    @Override
    public int showDialog(ImagePlus imagePlus, String s, PlugInFilterRunner plugInFilterRunner) {
        // Move any previous log to archive window
        String logText = IJ.getLog();
        if (logText != null && !logText.trim().isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            archiveLogTitle = "Log Archive - " + sdf.format(new Date());
            // Create new TextWindow with archived log
            new TextWindow(archiveLogTitle, "", logText, 400, 300);
        }
        // Clear the default log window
        IJ.log("\\Clear");

        imp = imagePlus;
        pfr = plugInFilterRunner;

        // Clean old model, if not closed correctly
        if (model != null) {
            model.close();
        }

        // Set configurator available depending on mode
        Map<String, TranslatorConfigurator> tempMap = new LinkedHashMap<>();
        switch (mode) {
            case 0:
                tempMap.put("Detr LNP Object Detection", new DetrConfigurator());
                break;
            case 1:
                tempMap.put("Detr dOMV Object Detection", new DetrDomvConfigurator());
                break;
            default:
                tempMap.put("Detr Object Detection", new DetrDomvConfigurator());
                break;
        }
        KNOWN_CONFIGURATORS = Collections.unmodifiableMap(tempMap);

        // --- 1. Prompt user for model repository + Preferences for output ---
        GenericDialog gd = new GenericDialog("Model Directory + Configurations");
        addInitialDialogFields(gd, getModeName()+"_plugin");
        gd.addMessage("__________");
        DetrUtils.addDetrOutputDialog(gd, mode);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return DONE; // User canceled
        }

        IJ.log("\n===========================================");
        IJ.log(" --- Print Initial Detr Dialog options");
        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd);

        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return DONE;
        }
        Path modelPath = initialChoice.modelPath;

        options = DetrUtils.getDetrOutputAnswer(gd, mode);

        // --- 2. Try to Load Model ---
        DjlModelLoaderNew<ImagePlus, DetectedObjects> modelLoader =
                new DjlModelLoaderNew<>(ImagePlus.class, DetectedObjects.class, KNOWN_CONFIGURATORS, engineChoices, deviceChoices);
        DjlModelLoaderNew.LoadedModel<ImagePlus, DetectedObjects> loadedResult = modelLoader.loadModel(modelPath, initialChoice);

        if (loadedResult.isFail()) {
            if (loadedResult.isCancelled()) {
                IJ.log(" --- Model loading cancelled.");
            } else {
                IJ.log(" --- Model loading failed.");
                IJ.error("Model loading failed.");
            }
            return DONE;
        }

        model = loadedResult.getModel();
        config = loadedResult.getConfig();

        config.printConfig();

        // Load ClassIdMap
        classIdMap = getClassIdMap(model, config);

        stackSize = imp.getStackSize();
        setupParam = IJ.setupDialog(imp, flags);
        return setupParam;
    }

    @Override
    public void run(ImageProcessor ip) {
        boolean processStack = (setupParam & DOES_STACKS) > 0;
        int currentSliceNb = pfr.getSliceNumber();

        IJ.log("\n===========================================");
        IJ.log(" --- Starting Detr prediction (slice " + currentSliceNb + ")");

        // --- 1. Get ImagePlus

        // Force conversion to 8bit when ip is 16bit
        if(ip instanceof ShortProcessor){
            ip = ip.convertToByteProcessor();
            IJ.log("Force image conversion to 8bit.");
        }

        String impTitle = imp.hasImageStack() ? imp.getStack().getShortSliceLabel(currentSliceNb) : imp.getTitle();
        ImagePlus imp2 = new ImagePlus(impTitle, ip);

        // When selected only one slice, apply the macro only on the slice copy
        if (applyMacroCondition && processSingleSlice){
            imp2.show();
            IJ.log("===========================================");
            IJ.log("Running Preprocessing macro on slice : " + impTitle);
            System.out.println("Running Preprocessing macro on slice : " + impTitle);
            try {
                long preproStartTime = System.currentTimeMillis();
                DetrUtils.applyMacro(model, imp2, preProcessmacroName);
                long preproEndTime = System.currentTimeMillis();
                preproTimeSeconds = (preproEndTime - preproStartTime) / 1000.0;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // --- 2. Make prediction ---
        try (Predictor<ImagePlus, DetectedObjects> predictor = model.newPredictor()) {
            DetectedObjects detectionResult = predictor.predict(imp2);
            IJ.log(" --- Prediction done");

            // TODO: patch
            // Save configuration to config.properties if needed
            //                if (!loadedResult.needToRewriteSynset()) {
            //                    try {
            //                        Path newPropertiesFilePath = loadedResult.getNewPropertiesFilePath();
            //                        saveConfigToFile(config, newPropertiesFilePath);
            //                        IJ.log("Saved new configuration.");
            //                    } catch (IOException e) {
            //                        IJ.log("Warning: Failed to save configuration. Error: " + e.getMessage());
            //                    }
            //                }

            if (detectionResult == null) {
                IJ.log(" --- Detection failed or returned null.");
                return;
            } else if (detectionResult.getNumberOfObjects() == 0) {
                IJ.log(" --- No objects were detected");
                // return;
            }
            IJ.log(" --- Number of objects detected: " + detectionResult.getNumberOfObjects());

            // 3. Process Detections
            List<ProcessedDetection> processedDetections = SegmentationUtils.processDetections(imp2, detectionResult, classIdMap);
            if (processedDetections.isEmpty()) {
                IJ.log(" --- No valid detections were processed.");
                //return;
            }

            // 4. Generate Outputs
            IJ.log(" --- Generating output");
            generateOutputs(imp, currentSliceNb, processedDetections, options, classIdMap, mode);
            IJ.log(" --- Detr detection complete.");
            IJ.log("===========================================");
        } catch (TranslateException e) {
            IJ.handleException(e);
            IJ.log(" --- Prediction Failed : Error during prediction or translation\n Provided arguments are incompatible with model");
            IJ.error("Prediction Failed", "Error during prediction or translation:\n" + e.getMessage());
            IJ.log("===========================================");
        } catch (Exception e) { // Catch other unexpected errors during prediction/processing
            IJ.log(" --- Processing Error");
            IJ.handleException(e);
            IJ.error("Processing Error", "An unexpected error occurred:\n" + e.getMessage());
            IJ.log("===========================================");
        }

        // --- 5. Cleanup/close model ---
        if ((passCounter.incrementAndGet() >= stackSize) || !processStack) {
            // Get result tables
            String rtTableName = "Detr detection Results";
            ResultsTable rt = ResultsTable.getResultsTable(rtTableName);
            String rtAllTableName = "Results";
            ResultsTable rtAll = ResultsTable.getResultsTable(rtAllTableName);

            StringBuilder summary = logSummary(rtAll, impTitle);

            if (model != null) {
                IJ.log("\n===========================================");
                IJ.log(" --- Closing model.");
                model.close();
            }

            // Export data
            if (options.saveResultsData){
                String baseName = "Results_";
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String resultsDir = dir + baseName + timestamp + "/";

                // Check if folder exists, and if so, append a counter
                int counter = 1;
                File resultsDirFile = new File(resultsDir);
                while (resultsDirFile.exists()) {
                    resultsDir = dir + baseName + timestamp + "_" + counter + "/";
                    resultsDirFile = new File(resultsDir);
                    counter++;
                }

                // Create the unique folder
                if (resultsDirFile.mkdirs()) {
                    IJ.log("Results Directory created: " + resultsDirFile.getAbsolutePath());

                    // Save summary
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(resultsDir + "summary.txt"))) {
                        writer.write(summary.toString());
                    } catch (IOException e) {
                        IJ.log("Failed to write summary to summary.txt: " + e.getMessage());
                    }

                    // Save ROIs, Results, etc.
                    if (options.addToRoiManagerBB || options.addToRoiManagerShapes){
                        RoiManager roiManager = getRoiManager();
                        roiManager.save(resultsDir + "ROIs.zip");
                        IJ.log(" --- Saved ROIs.");
                    }

                    // Save Detection results
                    try {
                        rt.saveAs(resultsDir + "Detr_Detection_Results.csv");
                        IJ.log(" --- Saved "+rtTableName+".");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    // Save Results summary
                    try {
                        rtAll.saveAs(resultsDir + "Results.csv");
                        IJ.log(" --- Saved "+rtAllTableName+".");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    // Save log
                    selectWindow("Log");
                    saveAs("Text", resultsDir + "detailed_log.txt");

                } else {
                    IJ.log("Results Directory creation failed: " + resultsDirFile.getAbsolutePath());
                }

            }
            IJ.log("===========================================");
            IJ.log("===========================================\n");
        }

    }

    private StringBuilder logSummary(ResultsTable rtAll, String impTitle) {
        StringBuilder summary = new StringBuilder();
        double[] totalObjectsColumn = rtAll.getColumnAsDoubles(rtAll.getColumnIndex("Total objects"));
        long imagesWithMoreThan5Detections = Arrays.stream(totalObjectsColumn)
                .filter(val -> val >= 5)
                .count();
        long imagesWithLessThan5Detections = Arrays.stream(totalObjectsColumn)
                .filter(val -> val < 5)
                .count();
        long totalNbDetections = (long) Arrays.stream(totalObjectsColumn).sum();
        double totalDetections = Arrays.stream(totalObjectsColumn).sum();
        long totalImages = rtAll.getCounter();
        double detectionRatio = totalImages > 0 ? totalDetections / totalImages : 0;

        summary.append("\n================= SUMMARY =================\n");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd 'at' HH:mm:ss z")
                .withZone(ZoneId.systemDefault());
        String formattedStartDate = formatter.format(Instant.ofEpochMilli(runStartTime));
        summary.append("RUN Start : ").append(formattedStartDate).append("\n");

        // Log sample info
        summary.append("Sample path : ").append(dir).append("\n");
        if(processSingleSlice){
            summary.append("Sample name : ").append(imp.getTitle()).append("\n");;
            summary.append("Single File from Sample : ").append(impTitle).append("\n");
        }else{
            summary.append("Sample name : ").append(imp.getTitle()).append("\n");
        }

        // Log model info
        summary.append("Model path : ").append(model.getModelPath()).append("\n");
        summary.append("Model name : ").append(config.getModelName()).append("\n");

        // Log detection summary
        summary.append(rtAll.getCounter()).append(" images\n");
        summary.append(imagesWithLessThan5Detections).append(" images without objects (<5)\n");
        summary.append(imagesWithMoreThan5Detections).append(" images with more than (>=) 5 objects\n");

        switch (mode) {
            case 0:
                summary.append(String.format("%d LNPs detected (Avg: %.1f LNP/image)\n", totalNbDetections, detectionRatio));
                break;
            case 1:
                summary.append(String.format("%d dOMVs detected (Avg: %.1f dOMV/image)\n", totalNbDetections, detectionRatio));
                break;
        }


        List<Map.Entry<String, Integer>> sortedClasses = new ArrayList<>(classIdMap.entrySet());
        sortedClasses.sort(Map.Entry.comparingByValue());
        for (Map.Entry<String, Integer> entry : sortedClasses) {
            String className = entry.getKey();
            double[] classColumn = rtAll.getColumnAsDoubles(rtAll.getColumnIndex("Nb "+className));
            double totalClass = Arrays.stream(classColumn).sum();
            double classRatio = totalDetections > 0 ? totalClass / totalDetections : 0;
            String outputName = className.substring(0, 1).toUpperCase() + className.substring(1);
            summary.append(String.format(outputName + " : %.1f%%\n", classRatio * 100));

        }

        // Log the mean round diameter (if domv select and round class detected)
        //noinspection SwitchStatementWithTooFewBranches
        switch (mode) {
            case 1:
                if (classIdMap.containsKey("round") && rtAll.getColumnIndex("Round mean diam (nm)") != -1){
                    double[] roundColumn = rtAll.getColumnAsDoubles(rtAll.getColumnIndex("Nb round"));
                    double[] roundMeanDiamColumn = rtAll.getColumnAsDoubles(rtAll.getColumnIndex("Round mean diam (nm)"));
                    double[] roundSumDiamSquaredColumn = rtAll.getColumnAsDoubles(rtAll.getColumnIndex("Round : sum of diam squared"));

                    double diamSum = 0.0;
                    double diamSquaredSum = 0.0;
                    double totalRound = 0.0;

                    for (int i = 0; i < roundColumn.length; i++) {
                        double count = roundColumn[i];
                        double meanDiameter = roundMeanDiamColumn[i];
                        double diamSquaredSum_i = roundSumDiamSquaredColumn[i];
                        if (count > 0) {
                            diamSum += meanDiameter * count;
                            totalRound += count;
                            diamSquaredSum += diamSquaredSum_i;
                        }
                    }
                    if (totalRound > 0) {
                        double diamMean = diamSum / totalRound;
                        double diamStd = Math.sqrt((diamSquaredSum / totalRound)-Math.pow(diamMean, 2));

                        summary.append(String.format("Round mean diam : %.3f nm\n", diamMean));
                        summary.append(String.format("Round diam std : %.3f nm\n", diamStd));
                    } else {
                        summary.append("Round mean diam : N/A (no valid data)\n");
                    }
                } else {
                    summary.append("Round mean diam : N/A (no valid data)\n");
                }
                break;
        }

        totalRunTimeSeconds = (System.currentTimeMillis() - runStartTime) / 1000.0;
        String totalFormatted = formatTime(totalRunTimeSeconds);
        String preproFormatted = formatTime(preproTimeSeconds);
        String detectFormatted = formatTime(totalRunTimeSeconds - preproTimeSeconds);
        if (options.applyPreproMacro){
            summary.append(String.format("Done in %s (%.1fs/image) : prepro %s (%.1fs/image), detection %s (%.1fs/image)\n",
                    totalFormatted,
                    totalRunTimeSeconds/totalImages,
                    preproFormatted,
                    preproTimeSeconds/totalImages,
                    detectFormatted,
                    (totalRunTimeSeconds-preproTimeSeconds)/totalImages));
        } else {
            summary.append(String.format("Done in %s (%.1fs/image)\n",
                    totalFormatted,
                    totalRunTimeSeconds / totalImages));
        }
        summary.append("===========================================\n");

        // Log to ImageJ
        IJ.log(summary.toString());
        return summary;
    }

    @Override
    public int setup(String s, ImagePlus impSetup) {
        System.setProperty("OPT_OUT_TRACKING", "true");
        // System.setProperty("ai.djl.offline", "true");
        if (impSetup == null) {
            IJ.noImage();
            return DONE;
        }

        // Set lnp/domv mode (default back to lnp mode)
        mode = modeMap.getOrDefault(s.toLowerCase(), 0);

        return flags;
    }

    @Override
    public void setNPasses(int nPasses) {
        // Clear previous ROIs/Result tables/Log if chosen
        if (options.clearResults){
            if (archiveLogTitle != null){
                // Get the window by title
                TextWindow archiveWindow = (TextWindow) WindowManager.getWindow(archiveLogTitle);
                // Close it if found
                if (archiveWindow != null) {
                    archiveWindow.close();
                    IJ.log("Clear previous log\n");
                }
            }
            IJ.log("Clear previous ROIs/Result tables\n");
            String rtTableName = "Detr detection Results";
            ResultsTable rt = ResultsTable.getResultsTable(rtTableName);
            String rtAllTableName = "Results";
            ResultsTable rtAll = ResultsTable.getResultsTable(rtAllTableName);
            if (rt!=null){
                rt.reset();
                rt.show(rtTableName);
                IJ.selectWindow(rtTableName);
                IJ.run("Close");
            }
            if (rtAll!=null){
                rtAll.reset();
                rtAll.show(rtAllTableName);
                IJ.selectWindow(rtAllTableName);
                IJ.run("Close");
            }
            RoiManager rm = RoiManager.getInstance();
            if (rm != null) {
                rm.reset();
                rm.close();
            }
        }

        runStartTime = System.currentTimeMillis();
        this.stackSize = nPasses;
        this.passCounter = new AtomicInteger(0);
        dir = getDirectory("image");
        processSingleSlice = imp.hasImageStack() && imp.getNSlices()>1 && stackSize == 1;

        // Run pre-processing macro if configured
        preProcessmacroName = config.getArguments().get("preProcessingMacro");
        applyMacroCondition = options.applyPreproMacro && preProcessmacroName != null && !preProcessmacroName.isEmpty();
        if (applyMacroCondition && !processSingleSlice) {
            String appliedTo = "image";
            if(imp.hasImageStack()){
                appliedTo = "stack";
            }
            IJ.log("===========================================");
            IJ.log("Running Preprocessing macro on "+appliedTo+" : " + imp.getTitle());
            System.out.println("Running Preprocessing macro on "+appliedTo+" : " + imp.getTitle());
            try {
                long preproStartTime = System.currentTimeMillis();
                DetrUtils.applyMacro(model, imp, preProcessmacroName);
                long preproEndTime = System.currentTimeMillis();
                preproTimeSeconds =  (preproEndTime - preproStartTime) / 1000.0;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public String getModeName() {
        for (Map.Entry<String, Integer> entry : modeMap.entrySet()) {
            if (Objects.equals(entry.getValue(), mode)) {
                return entry.getKey();
            }
        }
        return "unknown";
    }

}
