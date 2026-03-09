package fr.curie.miclearning.plugin.utilityplugin;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;

import static ij.WindowManager.getImage;
import static ij.WindowManager.getImageTitles;

public class ConfusionMatrix_Segmentation implements PlugIn{

    // Output Option Labels
    private final String[] outputOptions = new String[]{
            "ResultsTable for each image",
            "Total_ResultsTable",
            "Heatmap for each image",
            "Total_heatmap"
    };
    //Default states for output checkboxes
    private final boolean[] outputDefaults = {
            false,
            true,
            true,
            false,
    };

    @Override
    public void run(String s) {
        // 1. get the two images to compare + output options
        String[] titles = getImageTitles();

        if (titles.length == 0){
            IJ.error("run", "No images are open.");
        }

        // Create the Generic Dialog instance
        // Dialog components
        GenericDialog gd = new GenericDialog("Choose Ground truth mask and predicted mask");
        // images to compare
        gd.addChoice("Ground_truth mask:", titles, titles[0]);
        gd.addChoice("predicted mask:", titles, titles.length>1 ? titles[1] : titles[0]);
        // number of classes
        gd.addNumericField("number of classes", 2);
        // output options
        gd.addMessage("--- Output Options ---");
        gd.addCheckboxGroup(outputOptions.length, 1, outputOptions, outputDefaults);

        // Show Dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }

        // retrieve chosen images
        int gtImageIndex = gd.getNextChoiceIndex();
        int predImageIndex = gd.getNextChoiceIndex();
        ImagePlus gtImage = getImage(titles[gtImageIndex]);
        ImagePlus predImage = getImage(titles[predImageIndex]);
        if (gtImage == null || predImage == null) {
            IJ.error("Invalid selection or missing Image.");
            return;
        }

        int gtStackSize = gtImage.getStackSize();
        int predStackSize = predImage.getStackSize();

        ImageStack gtStack = gtImage.getStack();
        ImageStack predStack = predImage.getStack();

        // retrieve number of classes
        int numClasses = (int) gd.getNextNumber();

        // Retrieve Output Choices
        boolean outputStackTable = gd.getNextBoolean();
        boolean outputTotalTable = gd.getNextBoolean();
        boolean outputStackMap = gd.getNextBoolean();
        boolean outputTotalMap = gd.getNextBoolean();

        // 2. Check errors
        // stacks must be the same size
        if (gtStackSize != predStackSize){
            IJ.error("Stacks must be the same size. " + gtImage.getTitle() +" image has " + gtStackSize +
                    " slice(s) and " + predImage.getTitle() + " image has " + predStackSize + " slice(s).");
            return;
        }
        // rgb images are not accepted
        if(gtImage.getBitDepth() == 24 || predImage.getBitDepth() == 24){
            IJ.error("RGB images are not accepted");
            return;
        }
        // all images in a stack must be same dimension and ame bi depth, so only need to check once
        if (gtImage.getWidth() != predImage.getWidth() || gtImage.getHeight() != predImage.getHeight()) {
            IJ.error("Image dimensions must match. " + gtImage.getTitle() +" image has dimensions " + gtImage.getWidth() + " x " + gtImage.getHeight() +
                    " and " + predImage.getTitle() + " image has dimensions " + predImage.getWidth() + " x " + predImage.getHeight());
            return;
        }
        if (gtImage.getBitDepth() != predImage.getBitDepth()){
            IJ.error("Image bit depth must match. " + gtImage.getTitle() +" image has bit depth " + gtImage.getBitDepth() +
                    " and " + predImage.getTitle() + " image has bit depth " + predImage.getBitDepth());
            return;
        }

        // 3. Compare images
        List<int[][]> confusionMatrix = new ArrayList<>(gtStackSize);
        for (int i = 1; i <= gtStackSize; i++){
            confusionMatrix.add(compareImages(gtStack.getProcessor(i), predStack.getProcessor(i), numClasses));
        }

        // compute total matrix
        int[][] totalMatrix = new int[numClasses][numClasses];
        if(outputTotalTable || outputTotalMap) {
            for (int[][] matrix : confusionMatrix) {
                for (int i = 0; i < numClasses; i++) {
                    for (int j = 0; j < numClasses; j++) {
                        totalMatrix[i][j] += matrix[i][j];
                    }
                }
            }
        }

        // 4. Output

        // 4.1 Result table
        if (outputStackTable || outputTotalTable) {
            ResultsTable rt = ResultsTable.getResultsTable();
            rt.reset(); // reset ?
            if (outputStackTable){
                for (int i = 0; i < gtStackSize; i++) {
                    addMatrixToTable(rt, confusionMatrix.get(i), numClasses, i+"");
                }
            }
            if (outputTotalTable) {
                addMatrixToTable(rt, totalMatrix, numClasses, "sum");
            }
            rt.show("Results");
        }

        // 4.2 Heatmap
        if (outputStackMap) {
            ImageStack matrixStack = new ImageStack();
            for (int i = 0; i < gtStackSize; i++) {
                matrixStack.addSlice(heatMapFromMatrix(confusionMatrix.get(i)));
            }
            ImagePlus matrixImp = new ImagePlus("Heatmaps");
            matrixImp.setStack(matrixStack);
            matrixImp.show();
        }

        if (outputTotalMap) {
            FloatProcessor totalMatrixIp = heatMapFromMatrix(totalMatrix);
            ImagePlus totalMatrixImp = new ImagePlus("Total heatmap", totalMatrixIp);
            totalMatrixImp.show();
        }
    }

    private int[][] compareImages(ImageProcessor gtIp, ImageProcessor predIp, int numClasses){
        // create empty confusion matrix
        int[][] confusionMatrix = new int[numClasses][numClasses];

        // fill confusion matrix
        for (int i=0; i<gtIp.getPixelCount(); i++ ){
            int trueValue = gtIp.get(i);
            int predValue = predIp.get(i);
            if (trueValue >= 0 && trueValue < numClasses && predValue >= 0 && predValue < numClasses) {
                confusionMatrix[trueValue][predValue]++;
            }
        }
        return confusionMatrix;     
    }

    private void printConfusionMatrix(int[][] confusionMatrix, int numClasses){
        for (int i = 0; i < numClasses; i++) System.out.printf("%5d", i);
        System.out.println();
        System.out.print("----");
        for (int i = 0; i < numClasses; i++) System.out.print("-----");
        System.out.println();
        for (int i = 0; i < numClasses; i++) {
            System.out.printf("%2d |", i);
            for (int j = 0; j < numClasses; j++) System.out.printf("%5d", confusionMatrix[i][j]);
            System.out.println();
        }
    }

    private void addMatrixToTable(ResultsTable rt, int[][] confusionMatrix, int numClasses, String slice){
        int sum = 0;
        int[] colSum = new int[confusionMatrix.length];
        for (int i = 0; i < numClasses; i++) {
            int rowSum = 0;
            rt.incrementCounter();
            rt.addValue("slice", slice);
            rt.addValue("prediction", i+"");
            for (int j = 0; j < numClasses; j++) {
                rt.addValue(j + "", confusionMatrix[j][i]);
                colSum[j] += confusionMatrix[j][i];
                rowSum += confusionMatrix[j][i];
                sum += confusionMatrix[j][i];
            }
            rt.addValue("total", rowSum+"");
        }
        rt.incrementCounter();
        rt.addValue("slice", slice);
        rt.addValue("prediction", "total");
        for (int j = 0; j < numClasses; j++) rt.addValue(j + "", colSum[j]);
        rt.addValue("total", sum);

        //add empty row
        rt.incrementCounter();
        rt.addValue("slice", "");
        rt.addValue("prediction", "");
        for (int j = 0; j < numClasses; j++) rt.addValue(j + "", "");
        rt.addValue("total", "");
    }

    private FloatProcessor heatMapFromMatrix(int[][] confusionMatrix){
        return new FloatProcessor(confusionMatrix);
    }
}
