# MIC-Learning
**MIC-Learning** is an ImageJ/Fiji plugin designed to run deep learning models on microscopy images. Developed by the **Multimodal Imaging Center (MIC) at Curie Institute**, it provides a user-friendly interface to apply AI architectures directly within your image analysis workflow.

Built on top of the [Deep Java Library (DJL v0.34.0)](https://djl.ai/) and [Appose (v0.12.0)](https://github.com/apposed/appose), it supports a wide range of model formats and architectures.

## Getting started
### Prerequisites
* **ImageJ or Fiji:** Download it [here](https://imagej.net/software/fiji/downloads).
* **Java 8:** Standard for Fiji, bundled with all Fiji Stable versions (except the portable one)
    * _Note: If you wish to use TensorFlow models, you must run ImageJ/Fiji with Java 11._

### Installation
#### From Fiji Update Site
1. In Fiji, go to `Help>Update...` then to `Manage Update Sites` in the window that opens. 
2. Use the search bar to find the plugin named `MIC-Learning` and check the left side box next to the plug-in name.
3. Click on `Apply and Close` and then on `Apply Changes`.
4. Restart Fiji. You will find the plugin under `Plugins > MIC-Learning`.

#### Build from source
To build the repository from source you will need [Maven](https://maven.apache.org) and Java JDK 8 installed on your system and available in your PATH.
1. **Download and Build:** Clone the repository and compile the plugin using Maven:
   ```bash
   mvn package -f pom.xml
   ```
2. **Install the JAR:** Locate the `target` folder. Copy the **shaded** JAR file (`Mic-Learning-x.x.x-all.jar`) into the `plugins` folder of your ImageJ/Fiji directory.
3. **Launch:** Restart ImageJ/Fiji. You will find the plugin under `Plugins > MIC-Learning`.

### Model weights and networks
This plugin does not bundle models. You can obtain compatible models from:
1. **MIC-Learner:** Use the module "Model Download" of our companion plug-in, [MIC-Learner](https://github.com/MultimodalImagingCenter/MIC-Learner), to get pre-configured models and sample data.
2. **Zenodo:** Download pre-configured models and sample data [here](https://zenodo.org/records/20138094).

For plug-ins in section [Inference](#inference):

3. **BioImage Model Zoo:** e.g. [Mitochondria Segmentation](https://zenodo.org/records/6406804) or [Nuclei Segmentation](https://zenodo.org/records/6647674).
4. Official **Deep Java Library Models** (TorchScript models):
    - [YOLO11 Detection](https://mlrepo.djl.ai/model/cv/object_detection/ai/djl/pytorch/yolo11n/0.0.1/yolo11n.zip)
    - [YOLO11 Segmentation](https://mlrepo.djl.ai/model/cv/instance_segmentation/ai/djl/pytorch/yolo11n-seg/0.0.1/yolo11n-seg.zip)
    - [SAM2 (Hiera-Tiny)](https://mlrepo.djl.ai/model/cv/mask_generation/ai/djl/pytorch/sam2-hiera-tiny/0.0.1/sam2-hiera-tiny.zip)

For plug-ins in section [SAM models](#inference-with-sam-models):

5. [Meta's **HuggingFace** repositories](https://huggingface.co/facebook):
    - [SAM3](https://huggingface.co/facebook/sam3)
    - SAM2:
        - [SAM2.1-hiera-tiny](https://huggingface.co/facebook/sam2.1-hiera-tiny/resolve/main/sam2.1_hiera_tiny.pt)
        - [SAM2.1-hiera-small](https://huggingface.co/facebook/sam2.1-hiera-small/resolve/main/sam2.1_hiera_small.pt)
        - [SAM2.1-hiera-large](https://huggingface.co/facebook/sam2.1-hiera-large/resolve/main/sam2.1_hiera_large.pt)
        - ...
6. [Meta's SAM2 **GitHub** repository](https://github.com/facebookresearch/sam2)

#### _Important Requirements for plug-ins using DJL_
Those requirements apply to the plug-ins in section [Inference](#inference).
* **Format:** Currently supports **TorchScript** by default. PyTorch files are not supported. TensorFlow models are supported, but they require running ImageJ/Fiji with Java 11.
    * _Note: TorchScript and PyTorch models often share the same `.pt` extension. Make sure you download the **TorchScript** (traced or scripted) version of a model._
* **Configuration:** Every model requires a `serving.properties` file (DJL format) containing the engine, translator, and input dimensions, and other optional settings.
    * *If missing, the plugin will prompt a **Manual Configuration** window to generate one.*
* **Class Names:** If your model has output classes, provide a `synset.txt` file (one name per line, Darknet format).

## Usage

### Inference
Located under `Plugins > MIC-Learning > Inference`.

These plug-ins are built using [Deep Java Library (DJL v0.34.0)](https://djl.ai/). They allow you to run predictions on your images using pre-trained models. Choose the module that matches your model's architecture and your specific analysis goal:

* **Classification:** Whole-image categorization: processes an input image and returns a list of probabilities for each predefined class.
* **U-Net Models:** Takes an input image and generates one or more output images (such as probability maps or binary masks).
* **InstanSeg:** Performs instance segmentation, returning masks for individual objects.
* **YOLO Models:** Performs object detection and instance segmentation, returning bounding boxes and masks for individual objects.
* **DETR Models:** Performs object detection, returning bounding boxes for individual objects.
* **SAM2 Segmentation:** Interactive segmentation: objects are identified based on user-provided prompts, such as points or bounding boxes.

**General Workflow:**
1. Select the Model Path.
2. Select/Create the Configuration file.
3. Adjust specific parameters (output formats, thresholds) and run.

_Note: models for these plug-ins must be in **TorchScript** format (see [requirements](#important-requirements-for-plug-ins-using-djl)). Pre-configured models are available through the "Model Download" module of [MIC-Learner](https://github.com/MultimodalImagingCenter/MIC-Learner)._

### Inference with SAM models
Located under `Plugins > MIC-Learning > SAM`.

These plug-ins are built using [Appose (v0.12.0)](https://github.com/apposed/appose). They allow you to run:
* **Promptable Concept Segmentation (PCS)** using Meta's SAM3 models: finds and segments **all instances** of a concept, described by a short text phrase and/or example ROIs.
* **Promptable Visual Segmentation (PVS)** using Meta's SAM2 and SAM3 models: segments **one specific object** per visual prompt (points, boxes or masks) and tracks it across frames.

Choose the plug-in that matches your image(s) and your prompt(s) format:
* **SAM3 PCS on Image(s):** Each concept is defined by a short text phrase (e.g. "nucleus") and/or a visual prompt made of example ROI(s) (boxes, points or a combination of both). If the image is a stack, detection and segmentation are performed independently on every frame of the stack.
* **SAM3 PCS on Video:** Concepts are defined as above, once, on one prompt frame. Detection is then run on the selected frames and tracking carries objects from frame to frame.
* **SAM PVS on Video:** Each object is defined by a visual prompt (points, boxes or mask ROIs, possibly on several frames). Objects are tracked along the video.

_Note: these plug-ins use the original **PyTorch** checkpoints (not TorchScript). SAM2 models are available through the "Model Download" module of [MIC-Learner](https://github.com/MultimodalImagingCenter/MIC-Learner). For SAM3: request access and download it from [HuggingFace](https://huggingface.co/facebook/sam3). The Python environment is installed automatically by Appose on first run._

### Tools
Located under `Plugins > MIC-Learning > Tools`.

* **Mask Conversion:** Convert between ROIs, instance masks, and semantic masks.
* **Confusion Matrix for Masks:** Pixel-wise comparison of two semantic masks.
* **Measure Similarity:** Calculate RMSE, Correlation, PSNR, and SSIM between images.
* **Visualize Image Encoding:** View how an image is encoded (currently supports SAM2 models).

## License
Distributed under Institut Curie Software License. See `LICENSE` for more information.