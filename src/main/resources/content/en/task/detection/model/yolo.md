## Principle

The YOLO (*You Only Look Once* algorithms employ a **single-stage object detection** strategy:
a single neural network simultaneously computes the coordinates of bounding boxes and their corresponding class
probabilities.

The steps of this algorithm are as follows:

*   **Grid Division:** The input image is divided into a grid of cells. Each cell is responsible for detecting objects
    whose center point falls within that cell, but a CNN is applied only once to the entire image.

*   **Prediction:** For each grid cell, the network predicts a fixed number of bounding boxes, simultaneously
    outputting for each box:
*   The **coordinates** of the bounding box (e.g., center point, width, and height).
*   A **confidence score**, which reflects the probability that the box actually contains an object.
*   A set of **class probabilities**, indicating the likelihood of the object belonging to each potential class.

*   **Filtering:** First, boxes with a low confidence score are discarded.
* Finally, a **Non-Maximal Suppression (NMS)** algorithm is applied to eliminate multiple and redundant detections of
* the same object, retaining only the most relevant box for each object detected.

![yolo simplified steps](/contentImages/yolo_simplified.jpg){width=400}
*Redmon & al. “You Only Look Once: Unified, Real-Time Object Detection” (2016)*
