package fr.curie.miclearning.plugin.utilityplugin;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

public class SimilarityMetrics_Plugin implements PlugIn  {

    public void run(String s) {
        GenericDialog gd=new GenericDialog("similarity measures");
        String[] imgs= WindowManager.getImageTitles();
        int[] ids=WindowManager.getIDList();
        gd.addChoice("first image", imgs,imgs[0]);
        gd.addChoice("second image", imgs, imgs[1]);
        gd.addCheckbox("2D",true);

        gd.showDialog();
        if (gd.wasCanceled()) return;
        ImagePlus img1=WindowManager.getImage(ids[gd.getNextChoiceIndex()]);
        ImagePlus img2=WindowManager.getImage(ids[gd.getNextChoiceIndex()]);
        boolean is2D = gd.getNextBoolean();

        // Basic validations
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            IJ.error("Image dimensions must match.");
            return;
        }
        if (!is2D && img1.getNSlices() != img2.getNSlices()) {
            IJ.error("For 3D processing, number of slices must match.");
            return;
        }

        double maxI = getMaxI(img1);
        if(maxI == 0) {return;}

        double[] metrics;
        if(img1.getNSlices()<2 || is2D) {
            metrics=computeMetrics2D(img1,img2,maxI);
        }else{
            metrics=computeMetrics3D(img1, img2,maxI);
        }

        ResultsTable rt=ResultsTable.getResultsTable();
        if(rt==null)rt=new ResultsTable();
        rt.incrementCounter();
        rt.addValue("image1",img1.getShortTitle());
        rt.addValue("image2",img2.getShortTitle());
        rt.addValue("rmse",metrics[0]);
        rt.addValue("correlation",metrics[1]);
        rt.addValue("psnr",metrics[2]);
        rt.addValue("ssim",metrics[3]);
        rt.show("Results");
    }


    public static double correlation(float[] array1, float[] array2) {
        double avg1 = 0;
        double avg2 = 0;
        int tot = 0;
        int size = array1.length;
        for (int i = 0; i < size; i++) {
            avg1 += array1[i];
            avg2 += array2[i];
            tot++;
        }
        avg1 /= tot;
        avg2 /= tot;
        double sum1 = 0;
        double sum2 = 0;
        double sum3 = 0;
        double val1;
        double val2;
        for (int i = 0; i < size; i++) {
            val1 = (array1[i] - avg1);
            val2 = (array2[i] - avg2);
            sum1 += val1 * val2;
            sum2 += val1 * val1;
            sum3 += val2 * val2;
            tot++;
        }
        return sum1 / Math.sqrt(sum2 * sum3);
    }

    public static double correlation(ImageProcessor img1, ImageProcessor img2) {
        //System.out.println("correlation processor");
        double avg1 = 0;
        double avg2 = 0;
        int tot = 0;
        int size = img1.getWidth() * img2.getHeight();
        for (int y = 0; y < img1.getHeight(); y++) {
            for (int x = 0; x < img1.getWidth(); x++) {
                avg1 += img1.getf(x, y);
                avg2 += img2.getf(x, y);
                tot++;
            }
        }
        avg1 /= tot;
        avg2 /= tot;
        double sum1 = 0;
        double sum2 = 0;
        double sum3 = 0;
        double val1;
        double val2;
        for (int y = 0; y < img1.getHeight(); y++) {
            for (int x = 0; x < img1.getWidth(); x++) {
                val1 = (img1.getf(x, y) - avg1);
                val2 = (img2.getf(x, y) - avg2);
                sum1 += val1 * val2;
                sum2 += val1 * val1;
                sum3 += val2 * val2;
                tot++;
            }
        }
        return sum1 / Math.sqrt(sum2 * sum3);
    }

    public static double mse(float[] img1, float[] img2) {
        int tot = 0;
        int size = img1.length;
        double val;
        for (int i = 0; i < size; i++) {
            val = img1[i] - img2[i];
            tot += (int) (val * val);
        }
        return (double) tot / size;
    }

    public static double rmse(float[] img1, float[] img2) {
        return Math.sqrt(mse(img1, img2));
    }


    public static double mse(ImageProcessor img1, ImageProcessor img2) {
        //System.out.println("mse processor");
        double tot = 0;
        double size = img1.getWidth() * img1.getHeight();
        double val;
        for (int y = 0; y < img1.getHeight(); y++) {
            for (int x = 0; x < img1.getWidth(); x++) {
                val = img1.getf(x, y) - img2.getf(x, y);
                tot += val * val;
            }
        }
        return tot / size;
    }

    public static double rmse(ImageProcessor img1, ImageProcessor img2) {
        //System.out.println("rmse processor");
        return Math.sqrt(mse(img1, img2));
    }

    public static double psnr(ImageProcessor img1, ImageProcessor img2, double maxI) {
        // System.out.println("psnr processor");
        double mse_val = mse(img1, img2);
        if (mse_val == 0) { // Cas où les images sont identiques
            return Double.POSITIVE_INFINITY;
        }
        if (maxI == 0 && mse_val > 0) { // Cas où l'image de référence est noire mais pas l'image test
            return Double.NEGATIVE_INFINITY;
        }
        return 10 * Math.log10(maxI * maxI / mse_val);
    }

    public static double ssim(ImageProcessor img1, ImageProcessor img2){
        double sigma_gaussienne = 1.5;
        int kernel_width = 11;
        final double K1 = 0.01;
        final double K2 = 0.03;
        int bits_image1 = img1.getBitDepth();
        int bits_image2 = img2.getBitDepth();
        /*  check erreurs  */
        {
            if (img1.getHeight() != img2.getHeight()) {
                IJ.error("Les deux images n'ont pas la même hauteur");
            }
            if (img1.getWidth() != img2.getWidth()) {
                IJ.error("Les deux images n'ont pas la même largeur");
            }
            if (bits_image1 != bits_image2) {
                IJ.error("Les deux images n'ont pas le même nombre de bit par pixel");
            }
        }
        /*  Fin check erreurs */

        // Initialisation des constantes C1 et C2
        double C1 = (Math.pow(2, bits_image1) - 1)*K1;
        C1= C1*C1;
        double C2 = (Math.pow(2, bits_image2) - 1)*K2;
        C2=C2*C2;
        // FIN initialisation des constantes

        /* Creation de la fenêtre filtre */

        float[] poids = new float[kernel_width * kernel_width];
        double[] window = new double[kernel_width * kernel_width];
        double distance = 0;
        int center = (kernel_width / 2);
        double total = 0;
        double sigma_sq = sigma_gaussienne * sigma_gaussienne;

        for (int y = 0; y < kernel_width; y++) {
            for (int x = 0; x < kernel_width; x++) {
                distance = Math.abs(x - center) * Math.abs(x - center) + Math.abs(y - center) * Math.abs(y - center);
                int index = y * kernel_width + x;
                window[index] = Math.exp(-0.5 * distance / sigma_sq);
                total = total + window[index];
            }
        }
        //normaliser
        for (int i = 0; i < kernel_width * kernel_width; i++) {
            window[i] = window[i] / total;
            poids[i] = (float) window[i];
        }

        /* Fin de la création de la fenêtre filtre */

        /* CALCULS */

        int image_height = img1.getHeight();
        int image_width = img1.getWidth();
        int image_size = image_height*image_width;
        ImageProcessor mu1_ip = new FloatProcessor(image_width, image_height);
        ImageProcessor mu2_ip = new FloatProcessor (image_width, image_height);
        float [] array_mu1_ip = (float []) mu1_ip.getPixels(); //getPixels() => retourne une référence, array modifiée = ip modifié
        float [] array_mu2_ip = (float []) mu2_ip.getPixels();

        float [] array_ip1_copy = new float [image_size];
        float [] array_ip2_copy = new float [image_size];

        int pixel_img1 = 0;
        int pixel_img2 = 0;
        for (int i =0; i<image_size; i++) {

            if (bits_image1 == 8) {
                pixel_img1 = (0xff & img1.get(i));
                pixel_img2 = (0xff & img2.get(i));
            }
            else if (bits_image1 == 16) {
                pixel_img1 = (0xffff & img1.get(i));
                pixel_img2 = (0xffff & img2.get(i));
            }
            else if (bits_image1 == 32) {
                pixel_img1 = (img1.get(i));
                pixel_img2 = (img2.get(i));
            }
            array_mu1_ip [i] = array_ip1_copy [i] = pixel_img1;
            array_mu2_ip [i] = array_ip2_copy [i] = pixel_img2;
        }
        mu1_ip.convolve (poids, kernel_width, kernel_width);
        mu2_ip.convolve (poids, kernel_width, kernel_width);

        double [] mu1_sq = new double [image_size];
        double [] mu2_sq = new double [image_size];
        double [] mu1_mu2 = new double [image_size];

        for (int i =0; i<image_size; i++) {
            mu1_sq[i] = array_mu1_ip [i]*array_mu1_ip [i];
            mu2_sq[i] = array_mu2_ip[i]*array_mu2_ip[i];
            mu1_mu2 [i]= array_mu1_ip [i]*array_mu2_ip[i];
        }
        double [] sigma1_sq = new double [image_size];
        double [] sigma2_sq = new double [image_size];
        double [] sigma12 = new double [image_size];
        for (int i =0; i<image_size; i++) {
            sigma1_sq[i] =array_ip1_copy[i]*array_ip1_copy [i];
            sigma2_sq[i] = array_ip2_copy [i]*array_ip2_copy [i];
            sigma12 [i] = array_ip1_copy [i]* array_ip2_copy [i];
        }
        ImageProcessor tmp_1_ip = new FloatProcessor (image_width, image_height,sigma1_sq);
        ImageProcessor tmp_2_ip = new FloatProcessor (image_width, image_height,sigma2_sq);
        ImageProcessor tmp_12_ip = new FloatProcessor (image_width, image_height,sigma12);
        float [] array_tmp_1 =  (float []) tmp_1_ip.getPixels();
        float [] array_tmp_2 =  (float []) tmp_2_ip.getPixels();
        float [] array_tmp_12 =  (float []) tmp_12_ip.getPixels();
        for (int i =0; i<image_size; i++) {
            array_tmp_1[i] = (float) sigma1_sq[i];
            array_tmp_2[i] = (float) sigma2_sq[i];
            array_tmp_12[i] = (float) sigma12[i];
        }
        tmp_1_ip.convolve (poids, kernel_width,  kernel_width);
        tmp_2_ip.convolve (poids, kernel_width,  kernel_width);
        tmp_12_ip.convolve (poids, kernel_width,  kernel_width);
        for (int i =0; i<image_size; i++) {
            sigma1_sq[i] =  array_tmp_1[i] - mu1_sq[i];
            sigma2_sq[i] =  array_tmp_2[i ]- mu2_sq[i];
            sigma12[i] =  array_tmp_12[i] - mu1_mu2[i];
        }

        //Formule wikipédia
        double res=0;
        for (int i =0; i<image_size; i++) {
            double val = (( 2*mu1_mu2[i] + C1)* (2*sigma12[i] + C2)) / ((mu1_sq[i]+mu2_sq[i] + C1) * (sigma1_sq[i] + sigma2_sq[i] + C2));
            res +=  val;
        }
        res = res / image_size;
        return res;
    }

    public double[] computeMetrics2D(ImagePlus img1, ImagePlus img2, double range){
        System.out.println("compute metrics 2D");
        double rmse= rmse(img1.getProcessor(),img2.getProcessor());
        double ncc= correlation(img1.getProcessor(),img2.getProcessor());
        double psnr= psnr(img1.getProcessor(),img2.getProcessor(),range);
        double ssim = ssim(img1.getProcessor(),img2.getProcessor());
        System.out.println("rmse="+rmse);
        System.out.println("ncc="+ncc);
        System.out.println("psnr="+psnr);
        System.out.println("ssim="+ssim);
        return new double[]{rmse,ncc,psnr,ssim};
    }
    public double[] computeMetrics3D(ImagePlus img1, ImagePlus img2, double range){
        System.out.println("compute metrics in 3D");
        double mse=0;
        double rmse=0;
        double ncc=0;
        double psnr=0;
        ImageStack is1=img1.getImageStack();
        ImageStack is2=img2.getImageStack();
        for(int z=1;z<=img1.getNSlices();z++){
            mse+=mse(is1.getProcessor(z),is2.getProcessor(z));
            rmse+= rmse(is1.getProcessor(z),is2.getProcessor(z));
            ncc+= correlation(is1.getProcessor(z),is2.getProcessor(z));
            psnr+= psnr(is1.getProcessor(z),is2.getProcessor(z),range);

        }

        mse/=img1.getImageStackSize();
        rmse/=img1.getImageStackSize();
        ncc/=img1.getImageStackSize();
        psnr/=img1.getImageStackSize();
        double psnr2= 10*Math.log10(range*range/mse);
        System.out.println("rmse="+rmse);
        System.out.println("ncc="+ncc);
        System.out.println("psnr="+psnr);
        System.out.println("psnr(true)="+psnr2);

        return new double[]{rmse,ncc,psnr2};
    }



    /**
     * Determines the optimal (MAX_I) for PSNR calculation based on the reference image.
     *
     * @param refImp The reference ImagePlus.
     * @return The maximum possible signal value for this image type.
     */
    public static double getMaxI(ImagePlus refImp) {
        switch (refImp.getType()) {
            case ImagePlus.GRAY8:
            case ImagePlus.COLOR_RGB:
            case ImagePlus.COLOR_256:
                return 255.0;
            case ImagePlus.GRAY16:
            case ImagePlus.GRAY32:
                ImageStatistics stats;
                if (refImp.getNSlices() > 1) {
                    stats = refImp.getStatistics(Measurements.MIN_MAX);
                } else {
                    stats = refImp.getProcessor().getStatistics();
                }
                if(stats.max <= 0) {
                    IJ.log("Error : image max value is negative. Can't process");
                    return 0;
                }
                return stats.max;
            default:
                IJ.log("Warning: Unknown image type (" + refImp.getType() + ") for automatic range. Defaulting to 255.");
                return 255.0;
        }
    }

}
