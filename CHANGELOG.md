# Changelog
## current
### Added
- new SAM3 plugin: video detection, tracking and segmentation with single-text prompt
- Detection in 3d : introduce `Detection3dUtils`, `TrackedDetection` and `MultiFrameDataManager` to manage and process detection across multiple frames
- Appose utilities: add shared memory transfer for ImagePlus with multiple frames

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