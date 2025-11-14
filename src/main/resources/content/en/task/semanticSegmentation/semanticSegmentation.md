## Principle
Segmentation can be defined as a **pixel-wise classification** task: each pixel in the image is assigned a label. 

In the case of **semantic segmentation**, each pixel is assigned to a given class (for example, "nucleus," "cytoplasm," 
"membrane," or "background"). 

The result is a **semantic mask**, which is an image where each class is represented by a specific intensity value 
(with the background typically set to 0). Therefore, this approach makes it possible to distinguish between different 
types of objects, but it does not separate individual instances of the same class: two touching or overlapping objects 
of the same class will be fused together in the mask.

![semantic_segmentation exemple](/contentImages/semantic_segmentation.png){width=400}

## Applications in biology
An example application is the segmentation of axons and their myelin sheaths in electron microscopy images for studying the microstructure of nerve tissue.