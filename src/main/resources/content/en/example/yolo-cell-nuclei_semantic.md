# Model and data

## Data
The training data consists of subset of the Cellpose and Cellpose2 datasets :
fluorescently labelled cytoplasm with or without an extra nuclear channel,
brightfield microscopy,
membrane-labelled cells.

## Model
This YOLO model has been trained to differentiate between 2 classes:
*   nucleus : The nuclear region of a cell
*   cell : The cytoplasm region of a cell (the area of the cell excluding the nucleus).

## Expected image
An RGB microscopy image with fluorescently labelled cytoplasm and nucleus :
- Channel 1 (Red): Must contain the nucleus signal.
- Channel 2 (Green): Must contain the cytoplasm signal.

The diameter of the cells  should be between 4% and 20% of the image dimensions.

# Processing

## Pre-processing
These pre-processing steps are applied to each image before prediction:
1.  Resizing the image to the size expected by the model: 640x640.
2. Normalizing the pixel values.

## Post-processing
These post-processing steps are applied to the raw prediction output:
1.  Selecting objects with a confidence score above a threshold (you can modify this threshold by modifying `threshold` in parameters).
2.  Applying Non-Maximal Suppression (NMS) to remove redundant detections = those whose score is above a threshold (`nms_threshold` parameter).
3.  Recovering the mask and class for each object, 

## Result
The final output is an **semantic mask**, where each class is represented by a unique pixel value.
For clearer visualization, each value is assigned a distinct color (by applying a Look-Up Table (LUT)).