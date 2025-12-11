package fr.curie.miclearning.tools;

import ai.djl.modality.Classifications;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;

public class ClassificationUtils {

    public static ResultsTable addResultToTable(ImagePlus imp, Classifications classifications) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null) {
            rt = new ResultsTable();
        }

        Classifications.Classification item = classifications.best();
        rt.incrementCounter();
        rt.addValue("image", imp.getTitle());
        rt.addValue("Class predicted", item.getClassName());
        rt.addValue("Probability", item.getProbability());

        return rt;
    }

    public static ResultsTable addSliceResultToTable(ImagePlus imp, int sliceNumber, String sliceLabel, Classifications classifications, ResultsTable rt) {
        if (rt == null){
            IJ.log("result Table is null");
            return null;
        }
        if (classifications == null){
            IJ.log("classification is null");
            return rt;
        }
        rt.incrementCounter();

        Classifications.Classification item = classifications.best();
        rt.addValue("image", imp.getTitle());
        rt.addValue("slice", sliceNumber);
        rt.addValue("slice name", sliceLabel);
        rt.addValue("Class predicted", item.getClassName());
        rt.addValue("Probability", item.getProbability());

        return rt;
    }

}
