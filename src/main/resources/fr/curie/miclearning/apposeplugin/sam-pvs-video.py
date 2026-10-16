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
from collections import defaultdict, deque
from muggled_sam.make_sam import make_sam_from_state_dict
from muggled_sam.demo_helpers.video_data_storage import SAMVideoMemoryBank


# ============================================================
# Small geometry helpers
# ============================================================

def xywh_to_norm_x1y1x2y2(box, img_height, img_width):
    """Convert a [x, y, w, h] pixel box to [(x1, y1), (x2, y2)] corner points normalized to [0, 1]."""
    x1, y1, w, h = box
    x2, y2 = x1 + w, y1 + h
    return [(x1 / img_width, y1 / img_height), (x2 / img_width, y2 / img_height)]


def norm_xy(point, img_height, img_width):
    """Convert an (x, y) pixel point to normalized [0, 1] coordinates."""
    x1, y1 = point
    return (x1 / img_width, y1 / img_height)


def split_rois(rois, img_height, img_width):
    """rois format: [[x,y,w,h], [x,y], ...] (absolute values) -> (normalized box list, normalized point list)."""
    box_list = []
    point_list = []
    for roi in rois:
        if len(roi) == 4:  # box
            box_list.append(xywh_to_norm_x1y1x2y2(roi, img_height, img_width))
        elif len(roi) == 2:  # point
            point_list.append(norm_xy(roi, img_height, img_width))
        else:
            log_to_java(f"unknown prompt size: prompt {roi}")
    return box_list, point_list


def split_prompts_into_parts(box_list, fg_point_list):
    """
    SAM accepts at most one box per mask, so an object prompted with several boxes is split into
    parts, each segmented on its own: one part per box (with the positive points inside it),
    plus one part for the positive points outside every box.
    Returns a list of (box_list, fg_point_list) ; a single entry when there is at most one box.
    """
    if len(box_list) <= 1:
        return [(box_list, fg_point_list)]

    points_per_box = [[] for _ in box_list]
    loose_points = []
    # split points an associate them with the boxes they're inside of
    for point in fg_point_list:
        for box_idx, ((x1, y1), (x2, y2)) in enumerate(box_list):
            if x1 <= point[0] <= x2 and y1 <= point[1] <= y2:
                points_per_box[box_idx].append(point)
                break
        else:
            loose_points.append(point)

    parts = [([box], points) for box, points in zip(box_list, points_per_box)]
    if loose_points:
        parts.append(([], loose_points))
    return parts


# ============================================================
# 1. Build SAM model (SAM2 or SAM3 - detected automatically from the checkpoint)
# ============================================================
device, dtype = "cpu", torch.float32
if torch.cuda.is_available():
    device, dtype = "cuda", torch.bfloat16
log_to_java(f"loading model, using device: {device}...")

sam_core = make_sam_from_state_dict(modelPath)
track_model = sam_core.get_tracking_context()
track_model.to(device=device, dtype=dtype)

# ============================================================
# 2. Prepare prediction inputs
# ============================================================
# -- 2.1 pre process input video
log_to_java("pre processing images...")
video_input = videoInput
narr = video_input.ndarray()

# fix dimension order (from (B,H,W,C) rgb to (B,H,W,C) bgr)
images_bgr = narr[..., ::-1]
n_frames_to_process, h_img, w_img = images_bgr.shape[:3]
images_bgr = np.ascontiguousarray(images_bgr).astype(np.uint8)

# -- 2.2 parameters
mask_score_threshold = maskScoreThreshold
max_side_length = maxSideLength
max_side_length = None if (max_side_length is None or max_side_length <= 0) else max_side_length
n_frames_in_memory = nFramesInMemory
tracking_score_threshold = trackingScoreThreshold
imgenc_config_dict = {"max_side_length": max_side_length, "use_square_sizing": True}
frame_offset = frameOffset # first frame's original (absolute) index, only to report frame indices in their original numbering

# -- 2.3 sparse per-frame, per-object prompts
# received as {"<frame_idx>": {"<object_idx>": {"positive_rois": [...], "negative_rois": [...]}}}
frame_prompts_raw = framePrompts
frame_prompts = {
    int(frame_key): {int(obj_key): obj_val for obj_key, obj_val in per_object.items()}
    for frame_key, per_object in frame_prompts_raw.items()
}

# ============================================================
# 3. Per-frame helper: package one frame's results and send them to java
# ============================================================

# queue for storing results arrays while Java retrieves it
shm_queue = deque(maxlen=10)

def package_and_send_frame_results(frame_idx, masks_uint8_list, boxes_xywh_norm_list, scores_list, ids_list, progress_index):
    """
    Wrap one frame's detections as shared-memory appose.NDArrays and report them back to Java via
    task.update. `ids_list` holds each detection's Java-assigned object index (stable tracking
    identity - not python's dict iteration order).
    """
    n_obj = len(masks_uint8_list)
    if n_obj == 0:
        task.update(
            message=f"   frame {frame_idx + frame_offset + 1} - no object tracked",
            current=progress_index + 1,
            maximum=n_frames_to_process,
            info={"frame_idx": frame_idx + frame_offset, "n_results": 0}
        )
        return n_obj

    # Boxes
    boxes_np = np.array([[float(v) for v in box] for box in boxes_xywh_norm_list], dtype='float64')
    shared_boxes = appose.NDArray("float64", boxes_np.shape)
    shared_boxes.ndarray()[:] = boxes_np

    # Masks (resized up to the full frame size for transmission)
    masks_uint8_resized = [cv2.resize(mask, (w_img, h_img), interpolation=cv2.INTER_NEAREST)
                            for mask in masks_uint8_list]
    masks_np = np.array(masks_uint8_resized, dtype='uint8')
    shared_masks = appose.NDArray("uint8", masks_np.shape)
    shared_masks.ndarray()[:] = masks_np

    # Scores
    scores_np = np.array([float(s) for s in scores_list], dtype='float64')
    shared_scores = appose.NDArray("float64", scores_np.shape)
    shared_scores.ndarray()[:] = scores_np

    # object IDs (java-assigned tracking identity, stable across frames)
    ids_np = np.array(ids_list, dtype='int32')
    shared_ids = appose.NDArray("int32", ids_np.shape)
    shared_ids.ndarray()[:] = ids_np

    shm_queue.append((shared_boxes, shared_masks, shared_scores, shared_ids))

    task.update(
        message=f"   frame {frame_idx + frame_offset + 1} - number of object(s) tracked: {n_obj}",
        current=progress_index + 1,
        maximum=n_frames_to_process,
        info={
            "frame_idx": frame_idx + frame_offset,
            "n_results": n_obj,
            "object_ids": shared_ids,
            "scores": shared_scores,
            "boxes": shared_boxes,
            "masks": shared_masks,
        }
    )
    return n_obj


# ============================================================
# 4. Make predictions
# ============================================================
# One single forward pass over the video: objects register themselves lazily, on whichever frame
# their own first prompt appears (defaultdict), and a later prompt for an already-tracked object
# simply adds another prompt-memory encoding (refinement) without resetting its tracking memory.
log_to_java("running prediction...")

memory_per_obj_dict = defaultdict(lambda: SAMVideoMemoryBank(max_frame_memory=n_frames_in_memory))
total_detection = 0

for frame_idx in range(n_frames_to_process):
    frame = images_bgr[frame_idx]

    # Encode frame data (shared for all objects)
    encoded_img = track_model.encode_image(frame, **imgenc_config_dict)

    # 1. Register new/refining prompts for every object prompted on this frame
    prompts_at_frame = frame_prompts.get(frame_idx)
    if prompts_at_frame:
        for obj_idx, obj_prompts_raw in prompts_at_frame.items():
            pos_box_list, pos_point_list = split_rois(obj_prompts_raw.get("positive_rois", []), h_img, w_img)
            _, neg_point_list = split_rois(obj_prompts_raw.get("negative_rois", []), h_img, w_img)
            if len(pos_box_list) == 0 and len(pos_point_list) == 0:
                log_to_java(f"object {obj_idx}: no usable positive prompt on frame {frame_idx + frame_offset + 1} - skipped")
                continue
            log_to_java(f"registering prompt for object {obj_idx} (frame {frame_idx + frame_offset + 1})")
            parts = split_prompts_into_parts(pos_box_list, pos_point_list)
            if len(parts) == 1:
                obj_prompts = {
                    "box_xy1xy2_norm_list": pos_box_list,
                    "fg_xy_norm_list": pos_point_list,
                    "bg_xy_norm_list": neg_point_list,
                }
                _, init_mem = track_model.encode_prompt_memory(encoded_img, **obj_prompts)
            else:
                # several boxes for one object: segment each part separately, then build the
                # object's prompt memory from the union of the part masks
                # log_to_java(f"   object {obj_idx}: {len(parts)} parts merged into one object")
                part_masks = []
                for part_box_list, part_point_list in parts:
                    part_mask, _ = track_model.encode_prompt_memory(
                        encoded_img,
                        box_xy1xy2_norm_list=part_box_list,
                        fg_xy_norm_list=part_point_list,
                        bg_xy_norm_list=neg_point_list,
                    )
                    part_masks.append(part_mask)
                merged_mask = torch.stack(part_masks).amax(dim=0)  # max of mask logits = union of the masks
                init_mem = track_model.encode_prompt_memory_from_mask(encoded_img, merged_mask)
            memory_per_obj_dict[obj_idx].store_prompt_result(init_mem)

    # 2. Advance tracking for every currently-known object (objects prompted for the first time
    #    later in the video simply aren't in this dict yet, so earlier frames are skipped for them)
    masks_uint8_on_frame = []
    boxes_xywh_norm_on_frame = []
    scores_on_frame = []
    ids_on_frame = []

    for obj_idx, obj_memory in memory_per_obj_dict.items():
        masks_bnhw, ious_bn, ptrs_bnc, obj_score_b = track_model.step_video_masking(
            encoded_img, **obj_memory.to_dict()
        )

        # Skip storage for bad results (often due to occlusion) - tracking still continues next frame
        obj_score_float = float(obj_score_b)
        if obj_score_float < tracking_score_threshold:
            log_to_java(f"object {obj_idx}: low tracking score on frame {frame_idx + frame_offset + 1} - skipping output")
            continue

        # Store 'recent' memory encodings from current frame (helps track objects with changing appearance)
        encoded_mem = track_model.encode_frame_memory(encoded_img, masks_bnhw, ptrs_bnc, obj_score_b)
        obj_memory.store_frame_result(encoded_mem)

        mask_uint8 = (masks_bnhw > mask_score_threshold).byte().cpu().numpy().squeeze()

        # bounding box from the mask (the tracking API only returns a mask, no box)
        contours_list, _ = cv2.findContours(mask_uint8, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if len(contours_list) == 0:
            continue  # no usable contour this frame for this object - tracking continues next frame
        box_x, box_y, box_w, box_h = cv2.boundingRect(np.vstack(contours_list))  # spans every part of the object
        mask_h, mask_w = mask_uint8.shape
        box_xywh_norm = [box_x / mask_w, box_y / mask_h, box_w / mask_w, box_h / mask_h]

        masks_uint8_on_frame.append(mask_uint8)
        boxes_xywh_norm_on_frame.append(box_xywh_norm)
        scores_on_frame.append(obj_score_float)
        ids_on_frame.append(obj_idx)

    n_obj = package_and_send_frame_results(frame_idx, masks_uint8_on_frame, boxes_xywh_norm_on_frame,
                                            scores_on_frame, ids_on_frame, frame_idx)
    total_detection += n_obj

log_to_java("python segmentation complete")