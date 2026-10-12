"""`cuentapepas eval`: COCO metrics, recall and confusion matrix of a checkpoint on a processed split.

Inference runs the PyTorch checkpoint on CPU with the app's letterbox (export.letterbox) and the reference
postprocess (postprocess.py), so the numbers describe what the app pipeline sees. Detections are mapped back to
original image pixels before matching against the COCO ground truth.

Confusion matrix layout: rows = ground-truth class + "background" (detections matching no ground truth),
columns = predicted class + "missed" (ground truth matched by no detection). Matching is class-agnostic
(IoU >= 0.5, greedy by descending score, one detection per ground truth) over detections with score >= 0.25.
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
from pathlib import Path

os.environ.setdefault("CUDA_VISIBLE_DEVICES", "")  # evaluation never touches the GPU (training may be using it)

import cv2
import numpy as np

from cuentapepas.export import (DEFAULT_SIZE, TorchRunner, _sha256, find_checkpoint, letterbox, load_torch_model,
                                variant_id)
from cuentapepas.postprocess import postprocess
from cuentapepas.taxonomy import REPO_ROOT

DEFAULT_DATASET = REPO_ROOT / "data" / "local" / "processed" / "croppie_v1"
RECALL_SCORE = 0.25
MATCH_IOU = 0.5
EVAL_SCORE_FLOOR = 0.001  # low floor for COCOeval, as in standard mAP evaluation
SIZE_BUCKETS = {"small": (0.0, 32.0 ** 2), "medium": (32.0 ** 2, 96.0 ** 2), "large": (96.0 ** 2, float("inf"))}


def _iou_matrix(dets: np.ndarray, gts: np.ndarray) -> np.ndarray:
    """IoU between (n,4) and (m,4) xywh boxes."""
    if len(dets) == 0 or len(gts) == 0:
        return np.zeros((len(dets), len(gts)))
    d = np.column_stack([dets[:, 0], dets[:, 1], dets[:, 0] + dets[:, 2], dets[:, 1] + dets[:, 3]])
    g = np.column_stack([gts[:, 0], gts[:, 1], gts[:, 0] + gts[:, 2], gts[:, 1] + gts[:, 3]])
    iw = np.clip(np.minimum(d[:, None, 2], g[None, :, 2]) - np.maximum(d[:, None, 0], g[None, :, 0]), 0, None)
    ih = np.clip(np.minimum(d[:, None, 3], g[None, :, 3]) - np.maximum(d[:, None, 1], g[None, :, 1]), 0, None)
    inter = iw * ih
    area_d = ((d[:, 2] - d[:, 0]) * (d[:, 3] - d[:, 1]))[:, None]
    area_g = ((g[:, 2] - g[:, 0]) * (g[:, 3] - g[:, 1]))[None, :]
    union = area_d + area_g - inter
    return np.where(union > 0, inter / np.maximum(union, 1e-12), 0.0)


def match_and_count(gt: dict, dets: list[dict], n_classes: int) -> dict:
    """Recall, per-size recall and confusion matrix from a COCO ground-truth dict and a detection list.

    `dets` items: {image_id, category_id, bbox: [x, y, w, h], score}; category ids are 0..n_classes-1.
    """
    missed, bg = n_classes, n_classes
    confusion = np.zeros((n_classes + 1, n_classes + 1), dtype=np.int64)
    sizes = {k: [0, 0, 0] for k in SIZE_BUCKETS}  # n_gt, n_localized, n_class_correct
    gts_by_img: dict[int, list[dict]] = {}
    for a in gt["annotations"]:
        if not a.get("iscrowd", 0):
            gts_by_img.setdefault(a["image_id"], []).append(a)
    dets_by_img: dict[int, list[dict]] = {}
    for d in dets:
        if d["score"] >= RECALL_SCORE:
            dets_by_img.setdefault(d["image_id"], []).append(d)
    n_gt = n_loc = n_cls = 0
    for img in gt["images"]:
        g = gts_by_img.get(img["id"], [])
        dl = sorted(dets_by_img.get(img["id"], []), key=lambda d: -d["score"])
        ious = _iou_matrix(np.array([d["bbox"] for d in dl], float).reshape(-1, 4),
                           np.array([a["bbox"] for a in g], float).reshape(-1, 4))
        gt_taken = np.zeros(len(g), bool)
        gt_pred: list[int | None] = [None] * len(g)
        for i, d in enumerate(dl):
            cand = np.where(~gt_taken & (ious[i] >= MATCH_IOU))[0] if len(g) else []
            if len(cand) == 0:
                confusion[bg, d["category_id"]] += 1
                continue
            j = cand[np.argmax(ious[i][cand])]
            gt_taken[j] = True
            gt_pred[j] = d["category_id"]
        for j, a in enumerate(g):
            area = a.get("area", a["bbox"][2] * a["bbox"][3])
            bucket = next(k for k, (lo, hi) in SIZE_BUCKETS.items() if lo <= area < hi)
            sizes[bucket][0] += 1
            n_gt += 1
            if gt_pred[j] is None:
                confusion[a["category_id"], missed] += 1
            else:
                confusion[a["category_id"], gt_pred[j]] += 1
                sizes[bucket][1] += 1
                n_loc += 1
                if gt_pred[j] == a["category_id"]:
                    sizes[bucket][2] += 1
                    n_cls += 1

    def ratio(a, b):
        return float(a / b) if b else None

    return {
        "recall_iou50": ratio(n_loc, n_gt),
        "recall_iou50_class_correct": ratio(n_cls, n_gt),
        "n_gt": n_gt,
        "recall_by_size": {k: {"n_gt": v[0], "recall": ratio(v[1], v[0]), "recall_class_correct": ratio(v[2], v[0])}
                           for k, v in sizes.items()},
        "confusion_matrix": confusion.tolist(),
    }


def coco_metrics(gt: dict, dets: list[dict]) -> dict:
    """mAP50, mAP50-95 and AP50 per class with pycocotools COCOeval (None for a class without ground truth)."""
    from pycocotools.coco import COCO
    from pycocotools.cocoeval import COCOeval

    cats = sorted(c["id"] for c in gt["categories"])
    names = {c["id"]: c["name"] for c in gt["categories"]}
    if not dets:
        return {"map50": 0.0, "map50_95": 0.0, "ap50_per_class": {names[c]: None for c in cats}}
    with contextlib.redirect_stdout(io.StringIO()):
        coco = COCO()
        coco.dataset = json.loads(json.dumps(gt))
        coco.createIndex()
        res = coco.loadRes(json.loads(json.dumps(dets)))
        ev = COCOeval(coco, res, "bbox")
        ev.evaluate()
        ev.accumulate()
        ev.summarize()
    prec = ev.eval["precision"]  # [T, R, K, A, M]

    def mean_valid(p):
        p = p[p > -1]
        return float(p.mean()) if p.size else None

    ap50 = {names[c]: mean_valid(prec[0, :, k, 0, -1]) for k, c in enumerate(ev.params.catIds)}
    return {"map50": float(ev.stats[1]), "map50_95": float(ev.stats[0]), "ap50_per_class": ap50}


def evaluate_detections(gt: dict, dets: list[dict]) -> dict:
    out = coco_metrics(gt, dets)
    out.update(match_and_count(gt, dets, len(gt["categories"])))
    names = [c["name"] for c in sorted(gt["categories"], key=lambda c: c["id"])]
    out["confusion_labels"] = {"rows": names + ["background"], "cols": names + ["missed"]}
    return out


def _git_commit() -> dict:
    def git(*a):
        return subprocess.run(["git", "-C", str(REPO_ROOT), *a], capture_output=True, text=True).stdout.strip()
    return {"commit": git("rev-parse", "HEAD") or None, "dirty": bool(git("status", "--porcelain"))}


def run_inference(ckpt: Path, gt: dict, image_dir: Path, max_images: int | None, log=print,
                  size: tuple[int, int] = DEFAULT_SIZE) -> tuple[dict, list[dict]]:
    """Detections (original-pixel xywh) for the (optionally subsampled) images; returns the matching GT subset."""
    images = sorted(gt["images"], key=lambda i: i["id"])
    if max_images and max_images < len(images):
        idx = np.linspace(0, len(images) - 1, max_images).round().astype(int)
        images = [images[i] for i in sorted(set(idx.tolist()))]
    keep = {i["id"] for i in images}
    sub = {"images": images, "annotations": [a for a in gt["annotations"] if a["image_id"] in keep],
           "categories": gt["categories"]}
    import torch
    torch.set_num_threads(max(1, (os.cpu_count() or 2) // 2))
    model, _ = load_torch_model(ckpt)
    runner = TorchRunner(model)
    dets: list[dict] = []
    for n, img in enumerate(images, 1):
        bgr = cv2.imread(str(image_dir / img["file_name"]), cv2.IMREAD_COLOR)
        if bgr is None:
            raise RuntimeError(f"cannot read {image_dir / img['file_name']}")
        h, w = bgr.shape[:2]
        r = min(size[0] / h, size[1] / w)
        for d in postprocess(runner(letterbox(bgr, size)), score_threshold=EVAL_SCORE_FLOOR):
            x1, y1, x2, y2 = (v / r for v in d.box)
            x1, y1, x2, y2 = max(x1, 0.0), max(y1, 0.0), min(x2, float(w)), min(y2, float(h))
            if x2 <= x1 or y2 <= y1:
                continue
            dets.append({"image_id": img["id"], "category_id": d.cls, "bbox": [x1, y1, x2 - x1, y2 - y1],
                         "score": d.score})
        if n % 20 == 0 or n == len(images):
            log(f"  inferred {n}/{len(images)} images")
    return sub, dets


def evaluate_run(run: Path | None, ckpt: Path | None, split: str = "val", max_images: int | None = None,
                 dataset: Path = DEFAULT_DATASET, input_size: tuple[int, int] = DEFAULT_SIZE) -> dict:
    ckpt = ckpt or find_checkpoint(run)
    gt = json.loads((dataset / "annotations" / f"{split}.json").read_text(encoding="utf-8"))
    sub, dets = run_inference(ckpt, gt, dataset / split, max_images, size=input_size)
    metrics = evaluate_detections(sub, dets)
    metrics.update({"split": split, "n_images": len(sub["images"]), "n_detections": len(dets),
                    "ckpt": ckpt.name, "ckpt_sha256": _sha256(ckpt), "git": _git_commit(),
                    "input_size_hw": list(input_size), "variant": variant_id(input_size), "recall_score_threshold": RECALL_SCORE,
                    "match_iou": MATCH_IOU,
                    "val_map50": metrics["map50"], "val_map50_95": metrics["map50_95"]})
    if run is not None:
        # metrics.json feeds the default export, so only the training size may overwrite it.
        name = "metrics.json" if input_size == DEFAULT_SIZE else f"metrics_{variant_id(input_size)}.json"
        (run / name).write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8", newline="\n")
    return metrics
