# Changelog
## [1.4.0] - 2026-10-21
### Added
- Classes to define and  **"concepts" for Sam3 promptable concept segmentation (PCS)** :
  - `Sam3Concept` : a concept is defined by a name, an output group and prompts: 
  one optional text prompt ("visual" by default), optional positive and/or negative visual prompts 
  (one ROI group per visual prompt).
  - GUI: `Sam3ConceptTableModel`, `Sam3GroupIdCellEditor`, `Sam3GroupOption` : table where each row is one concept.
- `Sam3ConceptDialogBase`: base abstract class for plugin dialogs (based on `JDialog` instead of `GenericDialog`):
  - Non-modal Swing dialog.
  - Lets the user define an arbitrary number of concepts, model path, detection parameters and output options.
  - Interactive ROI selection : the positive/negative visual-group choices are refreshed from the RoiManager
    every 0.5 s while the dialog is open.
- **New plugin "SAM3 PCS on Image(s)"** (`Sam3ImagePcs_Plugin`): unifies single-image text-prompt and 
 visual-prompt promptable-concept-segmentation into one plugin. 
  - Run on a single image or a whole stack.
  - Multi class/concept: each concept's prompt is encoded and resolved independently on every frame (require visual 
  prompts on every frame).
  - Macro-recordable.
  - New supporting classes:`Sam3GroupOptionImage`, `Sam3ImageDialog`, `Sam3ImageRunConfig`, `Sam3ImagePythonRunner`,
    and the `sam3-pcs-singleimage-m.py` script.
- `Sam3AdvancedParametersDialog`: modal Swing dialog to modify the rarely-tuned SAM3 detection parameters (image and video).
- Output generation for detection: `DetectionUtils.createInstanceMaskPerClassStack`: generate a 4D hyperstack 
  for image stacks (one time-point per processed frame, one channel per concept).
- Classes to define **"objects" for promptable visual segmentation (PVS)** :
  - `PvsObject`: an object is defined by a name, an output group, and prompts: a mandatory positive
    visual prompt (point, box and/or mask ROIs, one ROI group, possibly defined on several frames to refine
    tracking downstream) plus an optional point-only negative visual prompt (one ROI group). Is one of 2 modes:
    - `CLASS`: on a single frame, all Rois of the group are resolved as independent objects. A
      cross multiple frames, all ROIs with the same name and group are instances of the same object
    - `OBJECT`:  on a single frame, all ROIs of the group are resolved as parts of the same object.
      Across multiple frames, all ROIs of the same group are instances of the same object.
  - GUI: `PvsObjectTableModel`, `PvsGroupIdCellEditor` : table where each row is one object; the
    negative-prompt column only offers ROI groups that contain at least one point ROI.
- **New plugin "SAM PVS on Video"** (`SamPvsVideo_Plugin`): promptable visual segmentation with SAM2
  or SAM3.
  - Visual prompts only:
    - positive: point ROIs, rectangle ROIs (used as boxes) and non-rectangular area ROIs (ellipse, polygon,
      freehand, rotated/rounded rectangle, used as **masks**: their outline is sent to Python as a polygon,
      rasterized there (`polygon_to_mask`) and encoded as prompt memory directly (`encode_prompt_memory_from_mask`)). 
      Box/point parts and masks of the same object are merged (union) into one prompt mask.
    - negative: point ROIs only. Area ROIs in a negative group are ignored
  - Objects are tracked forward only, each starting on whichever frame its own first prompt appears;
    a later prompt for an already-tracked object refines it going forward without affecting earlier frames.
  - Macro-recordable.
  - New supporting classes: `PvsVideoDialog`, `PvsModelParameters`, `PvsAdvancedParametersDialog`,
    `PvsVideoRunConfig`, `PvsVideoPythonRunner`, `PvsVideoResultsConsumer`, and the `sam-pvs-video.py` script.
- Dialog helpers shared by the SAM3 PCS and SAM PVS plugins, promoted out of the PCS-only package:
  `RoiGroupOption`, `RoiGroupOptionWithCoverage` (renamed from `Sam3GroupOption`/`Sam3GroupOptionImage`),
  `DialogUiSupport` (UI-scale helpers extracted from `Sam3ConceptDialogBase`), `ModelPathDialogs`
  (renamed/genericized from `Sam3Dialogs`, now takes candidate checkpoint file name(s) instead of
  being hardcoded to `sam3.pt`), `AbstractPromptDialog`, `AbstractResultsConsumer`
- `RoiPromptExtractor.groupsWithUsablePointRoi`: identifies which ROI groups contain at least one
  usable point ROI - used to filter PVS's negative-prompt group picker down to point-only groups.

### Changed
- **Plugin "SAM3 PCS on Video"** (`Sam3VideoPcs_Plugin`): now accept multiple concept as prompts:
  - Multi class/concept: each concept's prompt is encoded only on the prompt frame (same prompt frame for all concepts).
  - New supporting class : `Sam3VideoDialog` (extends `Sam3ConceptDialogBase`).
- Output generation: "Create instance mask per class" is now available for stacks.
- `DialogHelpBar` now attaches to any AWT `Window` (Swing `JDialog` included), not only `GenericDialog`.
- Advanced parameters dialogs (for image and video) are now a separate class (instead of method from `Sam3Dialogs`).
- Plugins menu: the "SAM3 PCS with Text Prompts" and "SAM3 PCS with Visual Prompts" entries are
  replaced by a single "SAM3 PCS on Image(s)".
- `RoiPromptExtractor.groupRoisByFrame`: ROIs with no Z/T position (position 0) are treated as
  belonging to frame 1 (avoid ignoring ROIs on single frame images)
- SAM3 PCS dialogs (`Sam3ConceptDialogBase`, `Sam3ImageDialog`, `Sam3VideoDialog`,
  `Sam3AdvancedParametersDialog`, `Sam3GroupIdCellEditor`) now use the shared `RoiGroupOption`,
  `DialogUiSupport` and `ModelPathDialogs` classes instead of `sampcs`-local ones (no behavior change).
- `RoiPromptExtractor.isUsableRoiType`: the usable ROIs now include all "area" ROIs, except for composite ROIs.
- `RoiPromptExtractor`: multi-points ROIs whose points lie on several positions (several frames) are split
  into one per position (`splitMultiPositionPoints`).
- `PromptRois` is a top-level class (was nested in `RoiPromptExtractor`), with polygon mask lists
  (`getPositiveMasks`/`getNegativeMasks`) and helpers `hasPositive`, `hasNegative`, `positiveCount`, `negativeCount`
- `RoiPromptExtractor.buildPromptRois` takes a `maskRoisAsMasks` flag: `true` for PVS (non-rectangular area ROIs
  sent as polygon masks), `false` for PCS (sent as their bounding box, the SAM3 detector takes no mask).
- SAM3 PCS plugins (image and video): any area ROI (rectangle, ellipse, polygon, freehand) is used through its
  bounding box. Visual prompts are now sent to Python as one map per concept.

### Fixed
- `RoiPromptExtractor`: when getting the position (frame in stack) of a point ROI, falls back to the position 
  of its first point (instead of 0)
- python scripts: persist result arrays in a queue while Java retrieve them (prevent Python from deleting them 
  before Java has had time to retrieve them)
- `sam3-pcs-video.py`: new detections get their own detection score (was read with the wrong index)
- `DetectionUtils.createSemanticMaskStack`: no longer skip empty frames

### Removed
- `Sam3TextPromptPcsMultiImg_Plugin.java` and associated classes (`Sam3TextPromptPcsMultiImgPythonRunner`, 
 `Sam3TextPromptPcsMultiImgRunConfig`) and associated python script (`sam3-detection-multiimage-textprompt-m.py`): 
 replaced by unified single image plugin.
- `Sam3VisualPromptSingleImgPcs_Plugin.java` and associated classes (`Sam3VisualPromptSingleImgPythonRunner`, 
 `Sam3VisualPromptSingleImgRunConfig`, `SingleImagePcsResultParser`) and associated python script 
 (`sam3-detection-image-geomprompt-m.py`): replaced by unified single image plugin.
- `sam3-detection-image.toml` (unused Pixi environment file).
- `Sam3CrossImagePcs_Plugin.java`, associated classes and associated script (unsatisfying results).
- `Sam3GroupOption.java`, `Sam3GroupOptionImage.java`, `Sam3Dialogs.java` : superseded by the shared
  `RoiGroupOption`, `RoiGroupOptionWithCoverage`, `DialogUiSupport` and `ModelPathDialogs` classes.

## [1.3.0] - 2026-08-24
### Added
- SAM3 plugins for pcs on video : 
  - handle visual (positive and negative) prompts + text prompt to define one concept/class
  - interactive frame selection & ROI selection
  - bidirectional detection and segmentation
  - Macro-recordable
- SAM3 plugin for pcs cross image : visual prompt encoded on 1 reference image, detection run on a different target image

### Changed
- SAM3 video pcs plugin: 
  - refactor implementation for pcs on video to use `muggled_sam`:
  - split `Sam3VideoPcs_Plugin` into separate classes (`RoiPromptExtractor`, `Sam3VideoRunConfig`, `Sam3VideoPythonRunner`, `VideoPcsResultsConsumer`) 
  - result consumption now runs on a managed `ExecutorService` thread instead of a raw `Thread`
  - add possibility to use a second model (sam2 or sam3 model) for the tracking part
- SAM3 text prompt multi-image pcs plugin (`Sam3TextPromptPcsMultiImg_Plugin`) : reworked along the same pattern as the video plugin (`Sam3TextPromptPcsMultiImgRunConfig`,
  `Sam3TextPromptPcsMultiImgPythonRunner`, `MultiImagePcsResultsConsumer`)
- SAM3 geometric-prompt single-image plugin (`Sam3VisualPromptSingleImgPcs_Plugin`): reworked along
  the same pattern (`Sam3VisualPromptSingleImgRunConfig`, `Sam3VisualPromptSingleImgPythonRunner`,
  `SingleImagePcsResultParser`).
- Appose: extracted `ApposeTaskRunner`, a generic environment/task runner shared by all
    three SAM3 plugins (streaming results via a queue, or single-shot via `task.outputs`)
- Appose: extracted `DetectionArrayParsing`, shared boxes/masks/scores/ids buffer-parsing
  used by all three plugins' result handling.

### Fixed
- SAM3 video PCS : visual prompts are now encoded only once, on prompt frame (instead of every frame)

### Removed
- `Sam3VideoTextDetection_Plugin` and `sam3-detection-video-textprompt-hg.py`
- `Sam3MultiTextDetectionHg_Plugin` and `sam3-detection-image-multitextprompt-hg.py` and `Sam3TextDetectionM_Plugin` and `sam3-detection-image-textprompt-m.py` (replaced by multi-image version).
- `Sam3BoxDetectionHg_Plugin` and `sam3-detection-image-boxprompt-hg.py`

## [1.2.0] - 2026-07-01
### Added
**SAM3 Plugins**
- Introduce `Sam3Parameters` class to handle inference configuration.
- Implement `Sam3VideoTextDetection_Plugin` :  video pcs + tracking with single-text prompt
- Implement `Sam3TextDetectionMultiImage_Plugin` : pcs text prompts across image stacks (prompt(s) encoded on each image) (replaces single-image version).
- Add support for point-based prompts (positive and negative) to single-image geometric-prompt pcs plugin

**Detection & 3D Processing**
- Introduce detection modes to support single-image, multi-image, and video stacks
- Introduce `Detection3dUtils`, `TrackedDetection`, and `MultiFrameDataManager` to manage multi-frame processing
- Introduce `GroupingMethod` (by object or by group) to define ROI IDs in `RoiManager`
- Output generation Add support for generating instance mask stacks or semantic mask stacks.

**Appose**
- Add `sam3m.toml` Pixi environment file for `muggled_sam`.
- Add shared memory transfer for `ImagePlus` with multiple frames in Appose utilities.

### Changed
- SAM3 inference: migrate from Hugging Face `transformers` to `muggled_sam` for increased flexibility and broader prediction support.
- SAM3 plugins: refactor implementation for single-image text-prompt and geometric-prompt pcs to use `muggled_sam`
- Output generation : separate `ImageProcessor` generation from `ImagePlus` generation to enable stack generation.

### Deprecated
- `Sam3MultiTextDetectionHg_Plugin` and `sam3-detection-image-multitextprompt-hg.py` (replaced by `muggled_sam` implementation).
- `Sam3BoxDetectionHg_Plugin` and `sam3-detection-image-boxprompt-hg.py` (replaced by `muggled_sam` implementation).
- `Sam3TextDetectionM_Plugin` and `sam3-detection-image-textprompt-m.py` (replaced by multi-image version).

## [1.1.0] - 2026-03-24
### Added
- SAM3 plugin: single-image detection with multi-text prompts
- SAM3 plugin: single-image detection with multi-box prompts (positive + negative boxes)
- Appose utilities: add shared memory transfer for ImagePlus objects
- DetectedObject: introduce `MaskByte` type for memory-efficient mask storage (Byte array vs Double array)
- Licensing information
- Project documentation: readme and changelog

### Changed
- ROI generation methods now support `MaskByte` DetectedObjects
- SAM3 inference: migrate from native library `facebook/sam3` to Hugging Face `transformers` (faster execution + easier choice of device cpu/cuda)

## [1.0.0] - 2026-01-09
### Added 
- First stable version