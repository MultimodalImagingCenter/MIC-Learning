package fr.curie.miclearning.tools.appose;

import ij.ImagePlus;
import ij.process.ImageConverter;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.appose.ShmImg;
import net.imglib2.img.ImagePlusAdapter;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class ApposeUtils {
    public static <T extends NumericType<T> & NativeType<T>> Img<T> imp2Img(ImagePlus imp) {
        // Wrap the ImagePlus into an ImgLib2 Img (which implements RAI)
        // This works for 8-bit, 16-bit and 32-bit (float)
        Img<T> image = ImagePlusAdapter.wrap(imp);
        System.out.println("\nImp converted to Img of dimensions: " + image.numDimensions());
        return image;
    }

    public static <T extends NumericType<T> & NativeType<T>> ShmImg<T> imp2ShmImg(ImagePlus imp) {
        ImagePlus tempImp = imp;

        // handle RGB: Convert packed ARGB to a 3-slice stack
        if (imp.getType() == ImagePlus.COLOR_RGB) {
            tempImp = imp.duplicate();
            new ImageConverter(tempImp).convertToRGBStack();
        }

        // wrap the ImagePlus into a RAI
        RandomAccessibleInterval<T> rai = ImageJFunctions.wrap(tempImp);
        //System.out.println("\nImp converted to rai of dimensions: " + rai.numDimensions());

        // create the shared memory copy
        return ShmImg.copyOf(rai);
    }

    public static String getResourceAsString(String resourcePath) {

        try (InputStream is = ApposeUtils.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Unable to find resource : " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while reading file at " + resourcePath + ": ", e);
        }
    }
}
