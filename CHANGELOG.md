# Changelog
## current
### Added
- new SAM3 plugin: video detection, tracking and segmentation with single-text prompt
- Detection in 3d : introduce `Detection3dUtils`, `TrackedDetection` and `MultiFrameDataManager` to manage and process detection across multiple frames
- Appose utilities: add shared memory transfer for ImagePlus with multiple frames
- SAM3: introduce `Sam3Parameters` class to handle inference configuration
- Appose environment: add `sam3m.toml` Pixi environment file for `muggled_sam`
- SAM3 single-image geometric-prompt detection plugin: add the possibility to use points as prompts (positive and negative)

### Changed
- SAM3 inference: migrate from Hugging Face `transformers` to `muggled_sam` for increased flexibility and broader prediction support
- SAM3 plugin: new implementation for single-image text-prompt and geometric-prompt detection using `muggled_sam` library

### Deprecated
- `Sam3MultiTextDetectionHg_Plugin` and `sam3-detection-image-multitextprompt-hg.py` (replaced by `muggled_sam` implementation)
- `Sam3BoxDetectionHg_Plugin` and `sam3-detection-image-boxprompt-hg.py` (replaced by `muggled_sam` implementation)

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