import appose

# ============================================================
# Communicate with Java (via Appose)
# ============================================================
appose_mode = 'task' in globals()  # 'task' is a global injected by Appose when running as a background task


def log_to_java(msg):
    """Send a progress/log message back to the Java side (or print, when run standalone for debugging)."""
    if appose_mode:
        task.update(message=str(msg))
    else:
        print(msg)


log_to_java("importing packages...")

import torch
import cv2
import numpy as np
from collections import defaultdict
from muggled_sam.make_sam import make_sam_from_state_dict

# ============================================================
# Small geometry helpers
# ============================================================

def xywh_to_norm_x1y1x2y2(box, img_height, img_width):
    """Convert a [x, y, w, h] pixel box to [(x1, y1), (x2, y2)] corner points normalized to [0, 1]."""
    x1, y1, w, h = box
    x2, y2 = x1 + w, y1 + h
    norm_x1 = x1 / img_width
    norm_y1 = y1 / img_height
    norm_x2 = x2 / img_width
    norm_y2 = y2 / img_height
    return [(norm_x1, norm_y1), (norm_x2, norm_y2)]


def norm_xy(point, img_height, img_width):
    """Convert an (x, y) pixel point to normalized [0, 1] coordinates."""
    x1, y1 = point
    return (x1 / img_width, y1 / img_height)

# ============================================================
# 1. Build SAM3 model
# ============================================================
device, dtype = "cpu", torch.float32
if torch.cuda.is_available():
    device, dtype = "cuda", torch.bfloat16
log_to_java(f"loading model, using device: {device}...")

# model paths received from java
model_path = modelPath

# Load and set up detector model
sam_core = make_sam_from_state_dict(model_path)
detect_model = sam_core.get_detector_context()
detect_model.to(device=device, dtype=dtype)

# ============================================================
# 2. Prepare inputs
# ============================================================
# -- 2.1 pre process input images
# --- 2.1.1 reference image
# input image is saved in shared memory as appose.NDArray
# wrap the input image from appose.NDArray to numpy.ndarray
log_to_java("pre processing images...")
ref_img_input = refImage
narr = ref_img_input.ndarray()

# fix dimension order (from (B,H,W,C) rgb to (B,H,W,C) bgr)
# TODO add format image check
ref_img_bgr = narr[..., ::-1]
_,h_ref_img, w_ref_img = ref_img_bgr.shape[:3]
ref_img_bgr = ref_img_bgr[0]

# array need to be saved in a contiguous memory block
ref_img_bgr = np.ascontiguousarray(ref_img_bgr).astype(np.uint8)

# --- 2.1.2 target images
target_img_input = targetImages
narr = target_img_input.ndarray()

# fix dimension order (from (B,H,W,C) rgb to (B,H,W,C) bgr)
# TODO add format image check
target_image_bgr = narr[..., ::-1]
n_frames_target, h_target_img, w_target_img = target_image_bgr.shape[:3]

# array need to be saved in a contiguous memory block
target_image_bgr = np.ascontiguousarray(target_image_bgr).astype(np.uint8)

# -- 2.2 format inputs
# - 2.2.1 text inputs
text_prompt = textPrompt

# - 2.2.2 visual inputs
positive_rois = positiveRois
negative_rois = negativeRois

pos_box_xy1xy2_norm_list = []
neg_box_xy1xy2_norm_list = []
pos_point_xy_norm_list = []
neg_point_xy_norm_list = []

# positive_rois received format: [[x,y,w,h], [x,y], ...] (absolute values)
# expected format:
#  one list for boxes : [[(x1, y1), (x2, y2)], ...] (normalized values)
#  one list for points : [(x, y), ... ] (normalized values)
if len(positive_rois) > 0:
    for prompt in positive_rois:
        if len(prompt) == 4:  # box
            pos_box_xy1xy2_norm_list.append(xywh_to_norm_x1y1x2y2(prompt, h_ref_img, w_ref_img))
        elif len(prompt) == 2:  # point
            pos_point_xy_norm_list.append(norm_xy(prompt, h_ref_img, w_ref_img))
        else:
            log_to_java(f"unknown prompt size: prompt {prompt}")

# negative_rois received format: [[x,y,w,h], [x,y], ...] (absolute values)
# expected format:
#  one list for boxes : [[(x1, y1), (x2, y2)], ...] (normalized values)
#  one list for points : [(x, y), ... ] (normalized values)
if len(negative_rois) > 0:
    for prompt in negative_rois:
        if len(prompt) == 4:  # box
            neg_box_xy1xy2_norm_list.append(xywh_to_norm_x1y1x2y2(prompt, h_ref_img, w_ref_img))
        elif len(prompt) == 2:  # point
            neg_point_xy_norm_list.append(norm_xy(prompt, h_ref_img, w_ref_img))
        else:
            log_to_java(f"unknown prompt size: prompt {prompt}")

# -- 2.3 Parameters for detection/tracking (received directly from java)
detection_score_threshold = detectionScoreThreshold  # minimum probability to register a new detection
mask_threshold = maskThreshold
# max length of the longest side, for detection
# (then divided by a ~3.5 factor internally to get the final mask side length)
max_side_length = maxSideLength
# weather to force coordinates encodings to only make use of the associated image data at the given point/box, and not the coordinates themselves
include_coordinate_encodings = includeCoordinateEncoding
imgenc_config_dict = {"max_side_length": max_side_length, "use_square_sizing": True}

# -- 2.4 Results array
# store results in arrays
total_detection = 0  # number of detections across all frames
all_masks = []
all_boxes = []
all_scores = []

# ============================================================
# 3. Make predictions
# ============================================================
# -- 3.1 encode prompts on the reference image
log_to_java("encoding prompt(s)...")
enc_ref_img = detect_model.encode_image(ref_img_bgr, **imgenc_config_dict)
enc_ref_exemplars = detect_model.encode_exemplars(
    enc_ref_img,
    text_prompt,
    pos_box_xy1xy2_norm_list,
    pos_point_xy_norm_list,
    neg_box_xy1xy2_norm_list,
    neg_point_xy_norm_list,
    include_coordinate_encodings=include_coordinate_encodings,
)

log_to_java("running prediction...")

for frame_idx, img in enumerate(target_image_bgr) :
    if n_frames_target >1:
        log_to_java(f"   Slice {frame_idx + 1}")
    # -- 3.2 encode target image data
    enc_targ_img = detect_model.encode_image(img, **imgenc_config_dict)

    # -- 3.3 make prediction
    mask_preds, box_preds, detection_scores, presence_score = detect_model.generate_detections(enc_targ_img, enc_ref_exemplars)
    filtered_masks, filtered_boxes, filtered_scores, presence_score = detect_model.filter_detections(mask_preds, box_preds, detection_scores, presence_score, detection_score_threshold)

    # ============================================================
    # 4. Process result and send them to java
    # ============================================================

    n_det = filtered_masks.shape[0] #number of detections for this frame, for this prompt
    log_to_java("      number of objects detected: {} - presence score: {}".format(n_det, *presence_score.tolist()))

    # -- 4.1 if no object : send empty results
    if n_det == 0:
        all_masks.append([])
        all_boxes.append([])
        all_scores.append([])
        task.update(
            message=f"   frame {frame_idx + 1} - no object detected",
            current=frame_idx + 1,
            maximum=n_frames_target,
            info={
                "frame_idx": frame_idx,
                "n_results": 0
            }
        )
    # -- 4.2 if at least 1 object
    else :
        # --- 4.2.1 process boxes = convert boxes format + wrap into shared-memory appose.NDArrays
        # Extract coordinates from muggled SAM output: [[[x1, y1], [x2, y2]], [...]]
        x1 = filtered_boxes[:, 0, 0]
        y1 = filtered_boxes[:, 0, 1]
        x2 = filtered_boxes[:, 1, 0]
        y2 = filtered_boxes[:, 1, 1]

        # already normalized
        # combine into [[x1, y1, w, h], [...]]
        boxes_xywh_norm_list = torch.stack(
            [x1, y1, x2 - x1,  y2 - y1],
            dim=-1)

        boxes_np = np.array([[float(v) for v in box] for box in boxes_xywh_norm_list], dtype='float64')
        shared_boxes = appose.NDArray("float64", boxes_np.shape)
        shared_boxes.ndarray()[:] = boxes_np
        all_boxes.append(shared_boxes)

        # --- 4.2.2 process masks
        masks_uint8_list = (filtered_masks > mask_threshold).byte().cpu().numpy()
        masks_uint8_resized = [cv2.resize(mask, (w_target_img, h_target_img), interpolation=cv2.INTER_NEAREST)
                               for mask in masks_uint8_list]
        masks_np = np.array(masks_uint8_resized, dtype='uint8')
        shared_masks = appose.NDArray("uint8", masks_np.shape)
        shared_masks.ndarray()[:] = masks_np
        all_masks.append(shared_masks)

        # --- 4.2.3 process score
        scores_np = np.array([float(s) for s in filtered_scores], dtype='float64')
        shared_scores = appose.NDArray("float64", scores_np.shape)
        shared_scores.ndarray()[:] = scores_np
        all_scores.append(shared_scores)

        # send results to java via task update
        task.update(
            message=f"    frame {frame_idx + 1} - number of object detected: {n_det}",
            current=frame_idx + 1,
            maximum=n_frames_target,
            info={
                "frame_idx": frame_idx ,
                "n_results": n_det,
                "scores": all_scores[frame_idx],
                "boxes": all_boxes[frame_idx],
                "masks": all_masks[frame_idx]
            }
        )


