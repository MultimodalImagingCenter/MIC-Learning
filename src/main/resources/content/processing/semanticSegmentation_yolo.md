# Model and data

## Data
The training data consists of electron microscopy images of lipid vesicles.

## Model
This YOLO model has been trained to detect and classify 3 types of vesicles:
*   those with intact membranes, which are round in shape
*   those with broken membranes, which are arch-shaped
*   and those with a "blackberry" shape, which correspond to a cluster of arches

## Expected image
A grayscale image depicting lipid vesicles. The diameter of the vesicles should be between 2% and 15% of the image dimensions.

# Processing

## Pre-processing
These pre-processing steps are applied to each image before prediction:
1.  Resizing the image to the size expected by the model: 1024x1024.
2.  Duplicating the grayscale channel to create a 3-channel image.
3.  Normalizing the pixel values.

## Post-processing
These post-processing steps are applied to the raw prediction output:
1.  Selecting objects with a confidence score above a threshold (you can modify this threshold by modifying `threshold` in parameters).
2.  Applying Non-Maximal Suppression (NMS) to remove redundant detections = those whose score is above a threshold (`nms_threshold` parameter).
3.  Recovering the mask and class for each object, 

## Result
The final output is an **semantic mask**, where each class is represented by a unique pixel value.
For clearer visualization, each value is assigned a distinct color (by applying a Look-Up Table (LUT)).