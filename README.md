# Mic learning
**MIC-Learning** is an ImageJ/Fiji plugin designed to run deep learning models on microscopy images. Developed by the **Multimodal Imaging Center (MIC) at Curie Institute**, it provides a user-friendly interface to apply AI architectures directly within your image analysis workflow.

Built on top of the [Deep Java Library (DJL v0.34.0)](https://www.google.com/url?sa=E&q=https%3A%2F%2Fdjl.ai%2F) and [Appose (v0.11.0)](https://www.google.com/url?sa=E&q=https%3A%2F%2Fgithub.com%2Fappose%2Fappose), it supports a wide range of model formats and architectures.

## Getting started
### Prerequisites
* **ImageJ or Fiji:** Download it [here](https://imagej.net/ij/download.html).
* **Java 8:** Standard for Fiji.
    * *Note: If you wish to use TensorFlow models, you must run ImageJ/Fiji with Java 11.*

### Installation
1. **Download and Build:** Clone the repository and compile the plugin using Maven:
   ```bash
   mvn package -f pom.xml
   ```
2. **Install the JAR:** Locate the `target` folder. Copy the **shaded** JAR file (`MIC-Learning-x.x.x-shaded.jar`) into the `plugins` folder of your ImageJ/Fiji directory.
3. **Launch:** Restart ImageJ/Fiji. You will find the plugin under `Plugins > MIC-Learning`.

### Models weights and networks

This plugin does not bundle models. You can obtain compatible models from:
1. **MIC-Learner:** Use the module "Model Download" of our companion plug-in, [MIC-Learner](https://github.com/MultimodalImagingCenter/MIC-Learner), to get pre-configured models and sample data.
2. **Zenodo:** Download models specifically developed for this plugin [here](https://zenodo.org/records/20138094).
3. **BioImage Model Zoo:** e.g., [Mitochondria Segmentation](https://zenodo.org/records/6406804) or [Nuclei Segmentation](https://zenodo.org/records/6647674).
4. **Official Deep Java Libraby Models (TorchScript):**
    - [YOLO11 Detection](https://mlrepo.djl.ai/model/cv/object_detection/ai/djl/pytorch/yolo11n/0.0.1/yolo11n.zip)
    - [YOLO11 Segmentation](https://mlrepo.djl.ai/model/cv/instance_segmentation/ai/djl/pytorch/yolo11n-seg/0.0.1/yolo11n-seg.zip)
    - [SAM2 (Hiera-Tiny)](https://mlrepo.djl.ai/model/cv/mask_generation/ai/djl/pytorch/sam2-hiera-tiny/0.0.1/sam2-hiera-tiny.zip)

#### Important Requirements
* **Format:** Currently supports **TorchScript** (.pt) by default. PyTorch (.pth) files are not supported. Tensorflow models are supported, but they require to run ImageJ/Fiji with Java 11.
* **Configuration:** Every model requires a `serving.properties` file (DJL format) containing the engine, translator, and input dimensions, and other optional setting.
    * *If missing, the plugin will prompt a **Manual Configuration** window to generate one.*
* **Class Names:** If your model have outputs classes, provide a `synset.txt` file (one name per line, Darknet format).

## Usage

### Inference
Located under `Plugins > MIC-Learning > Inference`.

These modules allow you to run predictions on your images using pre-trained models. Choose the module that matches your model's architecture and your specific analysis goal:

* **Classification:** Whole-image categorization : processes an input image and returns a list of probabilities for each predefined class.
* **U-Net Models:** Take an input image and generate one or more output images (such as probability maps or binary masks).
* **YOLO Models:**  Performs object detection and instance segmentation, returning bounding boxes and masks for individual objects.
* **DETR Models:** Performs object detection returning bounding boxes for individual objects.
* **SAM2 Segmentation:** Interactive segmentation : objects are identified based on user-provided prompts, such as points or bounding boxes.

**General Workflow:**
1. Select the Model Path.
2. Select/Create the Configuration file.
3. Adjust specific parameters (output formats, thresholds) and run.

### Tools
Located under `Plugins > MIC-Learning > Tools`.

* **Mask Conversion:** Convert between ROIs, instance masks, and semantic masks.
* **Confusion Matrix for Masks:** Pixel-wise comparison of two semantic masks.
* **Measure Similarity:** Calculate RMSE, Correlation, PSNR, and SSIM between images.
* **Visualize Image Encoding:** View how an image is encoded (currently supports SAM2 models).

## License
Distributed under Curie Institute License. See `LICENSE` for more information.