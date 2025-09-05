## Principle
Segmentation can be defined as a **pixel-wise classification** task: each pixel in the image is assigned a label.

In the case of **instance segmentation**, the goal is to separate the different objects present in an image, and
to delimit the exact shape of objects.
In other words, the task is to identify the pixels belonging to each object and assign a unique identifier to each
detected instance, regardless of its class.

This produces an **instance mask**, where each object is represented by a distinct value.

![instance_segmentation exemple](/contentImages/instance_segmentation.png){width=400}


## Applications in biology
- Fine morphological analysis: measure surface, perimeter, elongation or membrane irregularity
- Untangle superimposed objects: separate cells in dense cell cultures into distinct entities,
- Study cell interactions: analyze contact points between cells