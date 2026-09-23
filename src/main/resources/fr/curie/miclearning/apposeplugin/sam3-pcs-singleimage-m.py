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
from muggled_sam.make_sam import make_sam_from_state_dict

import sys
import os

log_to_java(f"Python : {sys.executable}")
log_to_java(f"Prefix : {sys.prefix}")
log_to_java(f"Torch : {torch.__version__}")
log_to_java(f"CUDA version : {torch.version.cuda}")
log_to_java(f"CUDA available : {torch.cuda.is_available()}")
log_to_java(f"PATH : {os.environ.get('PATH')}")



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


# ============================================================
# 1. Build SAM3 model
# ============================================================
device, dtype = "cpu", torch.float32
if torch.cuda.is_available():
    device, dtype = "cuda", torch.bfloat16
log_to_java(f"building model, using device: {device}...")
sam_core = make_sam_from_state_dict(model_path)
detect_model = sam_core.get_detector_context()
detect_model.to(device=device, dtype=dtype)

# ============================================================
# 2. Pre-process input images
# ============================================================
log_to_java("pre processing images...")
narr = images_input.ndarray()

# fix dimension order (from (B,H,W,C) rgb to (B,H,W,C) bgr)
images_bgr = narr[..., ::-1]
n_frames, h_img, w_img = images_bgr.shape[:3]

# array needs to be saved in a contiguous memory block
images_bgr = np.ascontiguousarray(images_bgr).astype(np.uint8)

imgenc_config_dict = {"max_side_length": max_side_length, "use_square_sizing": True}

# concept_labels/concept_texts/concept_text_used are frame-invariant, index-aligned lists;
# frame_prompts[frame_idx][concept_idx] = {"positive_rois": [...], "negative_rois": [...]} (per-frame visual prompts)
n_concepts = len(concept_labels)

# ============================================================
# 3. Make predictions for each image, one concept at a time
# ============================================================
log_to_java("running prediction...")
for frame_idx, img in enumerate(images_bgr):
    if n_frames > 1:
        log_to_java(f"   Slice {frame_idx + 1}")
    enc_img = detect_model.encode_image(img, **imgenc_config_dict)

    slice_total_detection = 0
    slice_masks = []
    slice_boxes = []
    slice_scores = []
    slice_concept_ids = []  # global concept index (stable across frames) for each detection

    concept_prompts_this_frame = frame_prompts[frame_idx]

    for concept_idx in range(n_concepts):
        text = concept_texts[concept_idx]
        text_used = concept_text_used[concept_idx]
        prompts = concept_prompts_this_frame[concept_idx]
        pos_box_list, pos_point_list = split_rois(prompts["positive_rois"], h_img, w_img)
        neg_box_list, neg_point_list = split_rois(prompts["negative_rois"], h_img, w_img)

        has_positive_visual = len(pos_box_list) > 0 or len(pos_point_list) > 0
        if not text_used and not has_positive_visual:
            log_to_java(f"      concept '{concept_labels[concept_idx]}': no prompt on frame {frame_idx + 1}, skipped")
            continue

        # encode_exemplars always receives a text value (SAM3 requires one); concepts without a
        # real text prompt use the "visual" placeholder, matching the rest of the codebase's convention
        enc_exm = detect_model.encode_exemplars(
            enc_img,
            text=text,
            box_xy1xy2_norm_list=pos_box_list,
            point_xy_norm_list=pos_point_list,
            negative_boxes_list=neg_box_list,
            negative_points_list=neg_point_list,
        )
        mask_preds, box_preds, det_scores, pres_score = detect_model.generate_detections(enc_img, enc_exm)
        filtered_masks, filtered_boxes, filtered_scores, presence_score = detect_model.filter_detections(
            mask_preds, box_preds, det_scores, pres_score, confidence_threshold
        )

        n_det = filtered_masks.shape[0]
        log_to_java("      concept '{}': number of object(s) detected: {} - presence score: {}".format(
            concept_labels[concept_idx], n_det, *presence_score.tolist()))

        if presence_score > 0.5 and n_det > 0:
            slice_total_detection += n_det

            # Extract coordinates from muggled SAM output: [[[x1, y1], [x2, y2]], [...]]
            x1 = filtered_boxes[:, 0, 0]
            y1 = filtered_boxes[:, 0, 1]
            x2 = filtered_boxes[:, 1, 0]
            y2 = filtered_boxes[:, 1, 1]
            # already normalized - combine into [[x1, y1, w, h], [...]]
            xywh_norm = torch.stack([x1, y1, x2 - x1, y2 - y1], dim=-1)

            slice_masks.append((filtered_masks > mask_threshold).detach().cpu())
            slice_boxes.append(xywh_norm.detach().cpu().float())
            slice_scores.append(filtered_scores.detach().cpu().float())

            concept_ids = torch.full((n_det,), concept_idx, dtype=torch.int32)
            slice_concept_ids.append(concept_ids)

    # ============================================================
    # 4. Concatenate and send this frame's results to Java
    # ============================================================
    if slice_total_detection > 0:
        pre_final_masks = torch.cat(slice_masks, dim=0).numpy().astype('uint8')
        final_boxes = torch.cat(slice_boxes, dim=0).numpy().astype('float64')
        final_scores = torch.cat(slice_scores, dim=0).numpy().astype('float64')
        final_ids = torch.cat(slice_concept_ids, dim=0).numpy().astype('int32')

        final_masks = np.empty((slice_total_detection, h_img, w_img), dtype=np.uint8)
        for i in range(slice_total_detection):
            final_masks[i] = cv2.resize(pre_final_masks[i], (w_img, h_img), interpolation=cv2.INTER_NEAREST)

        shared_masks = appose.NDArray("uint8", final_masks.shape)
        shared_boxes = appose.NDArray("float64", final_boxes.shape)
        shared_scores = appose.NDArray("float64", final_scores.shape)
        shared_ids = appose.NDArray("int32", final_ids.shape)

        shared_masks.ndarray()[:] = final_masks
        shared_boxes.ndarray()[:] = final_boxes
        shared_scores.ndarray()[:] = final_scores
        shared_ids.ndarray()[:] = final_ids

        task.update(
            message=f"   frame {frame_idx + 1} - number of object(s) detected: {slice_total_detection}",
            current=frame_idx + 1,
            maximum=n_frames,
            info={
                "frame_idx": frame_idx,
                "n_results": slice_total_detection,
                "prompts_ids": shared_ids,
                "scores": shared_scores,
                "boxes": shared_boxes,
                "masks": shared_masks
            }
        )
    else:
        task.update(
            message=f"   frame {frame_idx + 1} - no object detected",
            current=frame_idx + 1,
            maximum=n_frames,
            info={
                "frame_idx": frame_idx,
                "n_results": 0
            }
        )

log_to_java("python segmentation complete")