# Mic learning
**MIC-Learning** is an ImageJ/Fiji plugin designed to run deep learning models on microscopy images. Developed by the **Multimodal Imaging Center (MIC) at Curie Institute**, it provides a user-friendly interface to apply AI architectures directly within your image analysis workflow.

Built on top of the [Deep Java Library (DJL v0.34.0)](https://www.google.com/url?sa=E&q=https%3A%2F%2Fdjl.ai%2F) and [Appose (v0.11.0)](https://www.google.com/url?sa=E&q=https%3A%2F%2Fgithub.com%2Fappose%2Fappose), it supports a wide range of model formats and architectures.

## Getting started
### Prerequisites
* **ImageJ or Fiji:** Download it [here](https://imagej.net/software/fiji/downloads).
* **Java 8:** Standard for Fiji, bundled with all Fiji Stable versions (except portable one)
    * *Note: If you wish to use TensorFlow models, you must run ImageJ/Fiji with Java 11.*

### Installation
#### From Fiji Update Site
1. In Fiji, go to `Help>Update...` then to `Manage Update Sites` in the window that opens.
2. Use the search bar to find the plugin named `MIC-learning` and check the left side box next to the plug-in name.
3. Click on `Apply and Close` and then on `Apply Changes`.
4. Restart Fiji. You will find the plugin under `Plugins > MIC-Learning`.

#### Build from source
To build the repository from source you will need [Maven](https://maven.apache.org) and Java JDK 8 install on your system and available in your PATH.
1. **Download and Build:** Clone the repository and compile the plugin using Maven:
   ```bash
   mvn package -f pom.xml
   ```
2. **Install the JAR:** Locate the `target` folder. Copy the **shaded** JAR file (`MIC-Learning-x.x.x-shaded.jar` or `MIC-Learning-x.x.x-all.jar`) into the `plugins` folder of your ImageJ/Fiji directory.
3. **Launch:** Restart ImageJ/Fiji. You will find the plugin under `Plugins > MIC-Learning`.

### Models weights and networks
This plugin does not bundle models. You can obtain compatible models from:
1. **MIC-Learner:** Use the module "Model Download" of our companion plug-in, [MIC-Learner](https://github.com/MultimodalImagingCenter/MIC-Learner), to get pre-configured models and sample data.
2. **Zenodo:** Download models specifically developed for this plugin [here](https://zenodo.org/records/20138094).
3. **BioImage Model Zoo:** e.g., [Mitochondria Segmentation](https://zenodo.org/records/6406804) or [Nuclei Segmentation](https://zenodo.org/records/6647674).
4. **Official Deep Java Library Models (TorchScript):**
    - [YOLO11 Detection](https://mlrepo.djl.ai/model/cv/object_detection/ai/djl/pytorch/yolo11n/0.0.1/yolo11n.zip)
    - [YOLO11 Segmentation](https://mlrepo.djl.ai/model/cv/instance_segmentation/ai/djl/pytorch/yolo11n-seg/0.0.1/yolo11n-seg.zip)
    - [SAM2 (Hiera-Tiny)](https://mlrepo.djl.ai/model/cv/mask_generation/ai/djl/pytorch/sam2-hiera-tiny/0.0.1/sam2-hiera-tiny.zip)
5. **[HuggingFace](https://huggingface.co/)**:
    - [SAM3](https://huggingface.co/facebook/sam3)

#### Important Requirements for plug-ins using DJL
Those requirements apply to the plug-ins in section [Inference](#inference).
* **Format:** Currently supports **TorchScript** (.pt) by default. PyTorch (.pth) files are not supported. Tensorflow models are supported, but they require to run ImageJ/Fiji with Java 11.
* **Configuration:** Every model requires a `serving.properties` file (DJL format) containing the engine, translator, and input dimensions, and other optional setting.
    * *If missing, the plugin will prompt a **Manual Configuration** window to generate one.*
* **Class Names:** If your model have outputs classes, provide a `synset.txt` file (one name per line, Darknet format).

## Usage

### Inference
Located under `Plugins > MIC-Learning > Inference`.

These plug-ins allow you to run predictions on your images using pre-trained models. Choose the module that matches your model's architecture and your specific analysis goal:

* **Classification:** Whole-image categorization : processes an input image and returns a list of probabilities for each predefined class.
* **U-Net Models:** Take an input image and generate one or more output images (such as probability maps or binary masks).
* **YOLO Models:**  Performs object detection and instance segmentation, returning bounding boxes and masks for individual objects.
* **DETR Models:** Performs object detection returning bounding boxes for individual objects.
* **SAM2 Segmentation:** Interactive segmentation : objects are identified based on user-provided prompts, such as points or bounding boxes.

**General Workflow:**
1. Select the Model Path.
2. Select/Create the Configuration file.
3. Adjust specific parameters (output formats, thresholds) and run.

### Inference with SAM3 model
Located under `Plugins > MIC-Learning > SAM`.
These plug-ins allow you to run Promptable Concept Segmentation (PCS) using Metas SAM3 model. Choose the module that matches your image(s) and your prompt(s) format:
* **Single image(s) - Text prompt(s):** Prompt is one or more short text phrase(s), each one defining one class. If the image is a stack, detection and segmentation are performed independently on every frame of the stack.
* **Single image - Visual prompt(s):** Prompt is composed of ROI(s) (boxes or points or combination of both), defining one or more class(es). One group (ROI group) can be used as negative prompt. Does not work on stacks.
* **Cross-image(s) - Visual prompt:** Prompt encoded on 1 reference image, detection is run on a different target image(s). Prompt is composed of ROI(s) (boxes or points or combination of both), defining one class. One group (ROI group) can be used as negative prompt.
* **Video:** Prompt is composed of ROI(s) (boxes or points or combination of both) and/or one short text phrase, defining one class. One group (ROI group) can be used as negative prompt. Objects are detected and tracked along the video.

### Tools
Located under `Plugins > MIC-Learning > Tools`.

* **Mask Conversion:** Convert between ROIs, instance masks, and semantic masks.
* **Confusion Matrix for Masks:** Pixel-wise comparison of two semantic masks.
* **Measure Similarity:** Calculate RMSE, Correlation, PSNR, and SSIM between images.
* **Visualize Image Encoding:** View how an image is encoded (currently supports SAM2 models).

## License
Distributed under Curie Institute License. See `LICENSE` for more information.