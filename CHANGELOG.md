# Changelog
## [1.3.0] - current
### Added
- SAM3 plugins for pcs on video : 
  - handle visual (positive and negative) + text prompt to define the same concept
  - interactive frame selection & ROI selection

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