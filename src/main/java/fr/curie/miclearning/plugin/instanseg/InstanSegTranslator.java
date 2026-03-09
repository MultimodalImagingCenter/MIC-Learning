package fr.curie.miclearning.plugin.instanseg;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Pipeline;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.Arrays;

public class InstanSegTranslator implements Translator<Image, Image> {

    private final Pipeline pipeline;
    private static final Logger logger = LoggerFactory.getLogger(InstanSegTranslator.class);

    public InstanSegTranslator() {
        pipeline = new Pipeline();
        pipeline.add(new Resize(256, 256))
                .add(new ToTensor())   
                ;

    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager());

        System.out.println("Shape: " + Arrays.toString(array.getShape().getShape()));
        System.out.println("Dtype: " + array.getDataType());

        if (array.getDataType() == DataType.UINT8) {
            byte[] data = array.toByteArray();
            byte[] data2 = Arrays.copyOf(data,data.length);
            Arrays.sort(data2);
            System.out.println(data2[0]);
            System.out.println(data2[data2.length-1]);
            System.out.println(data2.length);
            int limit = Math.min(20, data.length);
            System.out.print("Premiers pixels (uint8): ");
            for (int i = 0; i < limit; i++) {
                System.out.print((data[i] & 0xFF) + " ");
            }
            System.out.println();
        } else if (array.getDataType() == DataType.FLOAT32) {
            float[] data = array.toFloatArray();
            int limit = Math.min(20, data.length);
            System.out.print("Premiers pixels (float32): ");
            for (int i = 0; i < limit; i++) {
                System.out.print(data[i] + " ");
            }
            System.out.println();
        } else {
            System.out.println("Type non géré pour affichage direct.");
        }



        return pipeline.transform(new NDList(array));
    }


    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray result = list.get(0); 
        result = result.squeeze();       

        System.out.println("Output min: " + result.min().getFloat());
        System.out.println("Output max: " + result.max().getFloat());

        NDArray mask = result.gt(0.01f).toType(DataType.UINT8, false);
        byte[] pixels = mask.toByteArray();

        BufferedImage bfImage = new BufferedImage(256, 256, BufferedImage.TYPE_BYTE_BINARY);
        bfImage.getRaster().setDataElements(0, 0, 256, 256, pixels);

        return ImageFactory.getInstance().fromImage(bfImage);
    }

}
