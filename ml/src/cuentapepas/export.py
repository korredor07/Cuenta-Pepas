"""Export a trained checkpoint to ONNX and TFLite (fp32 + fp16), verify parity and write the model.json contract.

Pipeline (CPU only; never touches CUDA): checkpoint -> ONNX (opset 13, static [1,3,384,640]) -> onnxsim ->
onnx2tf -> TFLite NHWC. Every artifact is built in a scratch directory; only after all parity checks pass are the
.tflite files and model.json installed into the output directory (atomically, model.json last), so a failed export
leaves nothing behind. The parity report is NOT installed there (the output directory ships in the APK); it goes to
the run directory or next to the checkpoint, or to --report-dir.
"""
from __future__ import annotations

import gc
import hashlib
import json
import logging
import math
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from cuentapepas.postprocess import (DEFAULT_MAX_DETECTIONS, DEFAULT_NMS_IOU, DEFAULT_SCORE_THRESHOLD, Detection,
                                     postprocess)
from cuentapepas.taxonomy import REPO_ROOT, load_taxonomy
from cuentapepas.yolox_bridge import ensure_yolox

MODEL_ID = "cuentapepas-nano-v1"
EXP_FILE = REPO_ROOT / "ml" / "exps" / "cuentapepas_nano.py"
DEFAULT_VAL_DIR = REPO_ROOT / "data" / "local" / "processed" / "croppie_v1" / "val"
DEFAULT_OUT = REPO_ROOT / "android" / "app" / "src" / "main" / "assets" / "model"
INPUT_H, INPUT_W = 384, 640
DEFAULT_SIZE = (INPUT_H, INPUT_W)
PAD_VALUE = 114
OPSET = 13
N_PARITY_IMAGES = 16
LICENSE_ATTRIBUTION = ("Trained on Croppie coffee Uganda (CC BY-SA 4.0) by Producers Direct & Alliance Bioversity-CIAT. "
                       "YOLOX Apache-2.0.")
FP32_NAME = "cuentapepas_nano_fp32.tflite"
FP16_NAME = "cuentapepas_nano_fp16.tflite"

# Parity tolerances (max |delta|) on the rows that matter (see _max_diffs): boxes are in input pixels, scores are
# obj/cls probabilities in 0..1.
TOL_ONNX = {"boxes": 1e-3, "scores": 1e-4}
TOL_TFLITE_FP32 = {"boxes": 1e-2, "scores": 1e-3}
BOX_RTOL = 1e-4  # extra relative slack on box coordinates, see _max_diffs
FP16_MAX_COUNT_DIFF = 2
FP16_MIN_MEAN_IOU = 0.9
FP16_MIN_CLASS_AGREEMENT = 0.95
FP16_MAX_SCORE_DELTA = 0.05
MATCH_IOU = 0.5

log = logging.getLogger(__name__)
# Detection agreement is gated at the default threshold; the lower one is informational (a barely trained model
# may have no detection at all at 0.30).
GATE_THRESHOLD = DEFAULT_SCORE_THRESHOLD
INFO_THRESHOLD = 0.05


class ExportError(RuntimeError):
    pass


# ---------------------------------------------------------------- input sizes

def parse_input_size(text: str) -> tuple[int, int]:
    """`"HxW"` -> (H, W); both must be positive multiples of 32 (strides 8/16/32 stay exact)."""
    try:
        h_s, w_s = text.lower().split("x")
        h, w = int(h_s), int(w_s)
    except ValueError:
        raise ExportError(f"invalid input size {text!r}, expected HxW such as 384x640") from None
    if h <= 0 or w <= 0 or h % 32 or w % 32:
        raise ExportError(f"invalid input size {text!r}: height and width must be positive multiples of 32")
    return h, w


def anchor_count(size: tuple[int, int]) -> int:
    h, w = size
    return sum((h // s) * (w // s) for s in (8, 16, 32))


def variant_id(size: tuple[int, int]) -> str:
    return f"r{size[0]}x{size[1]}"


# ---------------------------------------------------------------- inputs

def find_checkpoint(run_dir: Path) -> Path:
    """`best_ckpt.pth` under a training run directory (YOLOX writes it to <run>/<exp_name>/)."""
    found = sorted(run_dir.rglob("best_ckpt.pth"))
    if not found:
        raise ExportError(f"no best_ckpt.pth under {run_dir}")
    if len(found) > 1:
        raise ExportError(f"several best_ckpt.pth under {run_dir}, pass one with --ckpt:\n  "
                          + "\n  ".join(str(f) for f in found))
    return found[0]


def letterbox(image_bgr: np.ndarray, size: tuple[int, int] = DEFAULT_SIZE) -> np.ndarray:
    """Preprocessing exactly as the app does it: top-left letterbox into `size` (H, W), pad 114, BGR float32 0..255, HWC."""
    INPUT_H, INPUT_W = size
    h, w = image_bgr.shape[:2]
    # Same arithmetic as Kotlin: float32 scale, roundToInt = half-up (floor(x + 0.5)), not Python's banker's round().
    r = np.float32(min(INPUT_H / h, INPUT_W / w))
    nh, nw = int(np.floor(np.float32(h) * r + np.float32(0.5))), int(np.floor(np.float32(w) * r + np.float32(0.5)))
    canvas = np.full((INPUT_H, INPUT_W, 3), PAD_VALUE, dtype=np.uint8)
    canvas[:nh, :nw] = cv2.resize(image_bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)
    return canvas.astype(np.float32)


def load_parity_images(val_dir: Path, n: int = N_PARITY_IMAGES, size: tuple[int, int] = DEFAULT_SIZE) -> list[tuple[str, np.ndarray]]:
    """`n` evenly spaced val images (sorted by name, so the selection is deterministic), letterboxed HWC float32."""
    files = sorted(val_dir.glob("*.jpg"))
    if len(files) < n:
        raise ExportError(f"need {n} val images for the parity check, found {len(files)} in {val_dir}")
    step = len(files) // n
    out = []
    for path in files[::step][:n]:
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if img is None:
            raise ExportError(f"cannot read parity image {path}")
        out.append((path.name, letterbox(img, size)))
    return out


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_torch_model(ckpt_path: Path):
    """YOLOX-Nano from the repo Exp with the checkpoint's weights, eval mode, on CPU, decoding in the graph."""
    import torch
    from torch import nn

    ensure_yolox()
    from yolox.exp import get_exp
    from yolox.models.network_blocks import SiLU
    from yolox.utils import replace_module

    exp = get_exp(str(EXP_FILE), None)
    model = exp.get_model()
    ckpt = torch.load(str(ckpt_path), map_location="cpu", weights_only=True)
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model = replace_module(model, nn.SiLU, SiLU)
    model.eval().to(torch.device("cpu"))
    model.head.decode_in_inference = True
    return model, ckpt


# ---------------------------------------------------------------- conversion

def _import_simplify():
    """`onnxsim.simplify`, working around an environment mismatch.

    The installed onnxsim imports `ml_dtypes.float6_*` at module level (only for its FP6 quantization helper, unused
    here), but TensorFlow 2.18 pins ml_dtypes 0.4.x, which lacks them. Placeholders let the import succeed; nothing
    in the simplify path touches them.
    """
    import ml_dtypes

    for name in ("float6_e3m2fn", "float6_e2m3fn"):
        if not hasattr(ml_dtypes, name):
            setattr(ml_dtypes, name, None)
    from onnxsim import simplify

    return simplify


def export_onnx(model, onnx_path: Path, size: tuple[int, int] = DEFAULT_SIZE) -> None:
    import onnx
    import torch

    simplify = _import_simplify()

    dummy = torch.zeros(1, 3, *size)
    with torch.no_grad():
        torch.onnx.export(model, dummy, str(onnx_path), input_names=["images"], output_names=["output"],
                          opset_version=OPSET, dynamic_axes=None, dynamo=False)
    simplified, ok = simplify(onnx.load(str(onnx_path)))
    if not ok:
        raise ExportError("onnxsim could not validate the simplified ONNX model")
    onnx.save(simplified, str(onnx_path))


def onnx_to_tflite(onnx_path: Path, work: Path) -> tuple[Path, Path, list[str]]:
    """Run onnx2tf and return (fp32, fp16) .tflite paths plus the flags used.

    Only `-i -o -nuo` are used. `-nuo` skips onnx2tf's own onnxsim pass: the graph is already simplified here, and
    onnx2tf shells out to the `onnxsim` CLI, which fails to import in this environment (see `_import_simplify`).
    `-osd` is not needed. `-cotof` (onnx2tf's own output comparison) is not used: with 4-D inputs it fetches a
    calibration .npy from GitHub, and this pipeline verifies parity itself on real val images, offline.
    """
    out_dir = work / "tf"
    flags = ["-i", str(onnx_path), "-o", str(out_dir), "-nuo"]
    recorded_flags = ["-i", "<model.onnx>", "-o", "<out_dir>", "-nuo"]  # paths are scratch directories
    onnx2tf = Path(sys.executable).parent / ("onnx2tf.exe" if os.name == "nt" else "onnx2tf")
    cmd = [str(onnx2tf), *flags] if onnx2tf.exists() else [sys.executable, "-m", "onnx2tf", *flags]
    env = dict(os.environ, CUDA_VISIBLE_DEVICES="", TF_CPP_MIN_LOG_LEVEL="2", PYTHONUTF8="1")
    # onnx2tf's pre-conversion sanity pass wants a 20x128x128x3 sample array and downloads it from GitHub unless a file
    # with this exact name exists in its working directory. Provide a local one so the export never needs the network.
    np.save(work / "calibration_image_sample_data_20x128x128x3_float32.npy",
            np.random.default_rng(0).random((20, 128, 128, 3), dtype=np.float32))
    proc = subprocess.run(cmd, cwd=work, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        tail = "\n".join((proc.stdout + proc.stderr).splitlines()[-30:])
        raise ExportError(f"onnx2tf failed (exit {proc.returncode}):\n{tail}")
    fp32 = next(iter(sorted(out_dir.glob("*_float32.tflite"))), None)
    fp16 = next(iter(sorted(out_dir.glob("*_float16.tflite"))), None)
    if fp32 is None or fp16 is None:
        raise ExportError(f"onnx2tf did not produce float32/float16 tflite files in {out_dir}: "
                          f"{sorted(p.name for p in out_dir.iterdir())}")
    return fp32, fp16, recorded_flags


# ---------------------------------------------------------------- runners

class TorchRunner:
    def __init__(self, model):
        self.model = model

    def __call__(self, hwc: np.ndarray) -> np.ndarray:
        import torch

        with torch.no_grad():
            x = torch.from_numpy(np.ascontiguousarray(hwc.transpose(2, 0, 1)[None]))
            return self.model(x).numpy()[0]


class OnnxRunner:
    def __init__(self, path: Path):
        import onnxruntime as ort

        self.session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])

    def close(self) -> None:
        self.session = None

    def __call__(self, hwc: np.ndarray) -> np.ndarray:
        return self.session.run(["output"], {"images": np.ascontiguousarray(hwc.transpose(2, 0, 1)[None])})[0][0]


class TfliteRunner:
    """TFLite model with an NHWC float32 input. `transposed` is True when the output is [1, 5+C, N]."""

    def __init__(self, path: Path, size: tuple[int, int] = DEFAULT_SIZE):
        try:
            from ai_edge_litert.interpreter import Interpreter
        except ImportError:
            from tensorflow.lite import Interpreter
        self.interpreter = Interpreter(model_path=str(path), num_threads=2)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        outputs = self.interpreter.get_output_details()
        if len(outputs) != 1:
            raise ExportError(f"{path.name}: expected 1 output, got {[o['name'] for o in outputs]}")
        self.output = outputs[0]
        shape = [int(s) for s in self.input["shape"]]
        if shape != [1, size[0], size[1], 3] or self.input["dtype"] != np.float32:
            raise ExportError(f"{path.name}: unexpected input {shape} {self.input['dtype']}, want NHWC float32")
        self.output_shape = [int(s) for s in self.output["shape"]]
        self.transposed = self.output_shape[1] < self.output_shape[2]

    def close(self) -> None:
        """Release the model file (Windows cannot delete a file an interpreter still maps)."""
        self.interpreter = None

    def __call__(self, hwc: np.ndarray) -> np.ndarray:
        self.interpreter.set_tensor(self.input["index"], np.ascontiguousarray(hwc[None]))
        self.interpreter.invoke()
        out = self.interpreter.get_tensor(self.output["index"])[0]
        return out.T if self.transposed else out


# ---------------------------------------------------------------- parity

def _max_diffs(ref: np.ndarray, other: np.ndarray, tol: dict[str, float]) -> dict:
    """Max |delta| of boxes (cols 0-3) and scores (cols 4-9) on the rows that matter, plus over every row.

    "Rows that matter" are those whose reference score (obj * max cls) is at least INFO_THRESHOLD: the app's score
    threshold slider never goes below it. The 5040 anchors also include confident-background rows whose box size is
    exp() of a raw logit; fp32 rounding differences between runtimes are amplified there (up to ~0.05 px on a
    ~450 px box) without any effect on a detection, so they are reported (`*_all_rows`) but not gated.

    `box_ok` / `score_ok` are evaluated only on those gated rows (reference score >= INFO_THRESHOLD). Per coordinate
    a box may differ by up to `tol["boxes"] + BOX_RTOL * |ref|` (strictly less than): the relative term exists
    because fp32 runtimes differ on large boxes (fp32 resolution alone is ~3e-5 px at 450 px and conv rounding
    accumulates over ~100 layers; large background-like boxes are where it shows). Scores use the absolute `tol`.
    """
    d = np.abs(ref - other)
    rows = ref[:, 4] * ref[:, 5:].max(axis=1) >= INFO_THRESHOLD
    box_allowed = tol["boxes"] + BOX_RTOL * np.abs(ref[rows, :4])
    return {"boxes": float(d[rows, :4].max(initial=0.0)), "scores": float(d[rows, 4:].max(initial=0.0)),
            "boxes_all_rows": float(d[:, :4].max()), "scores_all_rows": float(d[:, 4:].max()),
            "box_ok": bool(np.all(d[rows, :4] < box_allowed)), "score_ok": bool(np.all(d[rows, 4:] < tol["scores"]))}


def _box_iou(a: tuple, b: tuple) -> float:
    iw = max(min(a[2], b[2]) - max(a[0], b[0]), 0.0)
    ih = max(min(a[3], b[3]) - max(a[1], b[1]), 0.0)
    inter = iw * ih
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def detection_agreement(ref: list[list[Detection]], other: list[list[Detection]]) -> dict:
    """Per-image count differences and the mean best IoU of each reference detection against the other model."""
    count_diffs = [abs(len(r) - len(o)) for r, o in zip(ref, other)]
    best_ious = [max((_box_iou(d.box, e.box) for e in o), default=0.0) for r, o in zip(ref, other) for d in r]
    agree, deltas = [], []
    for r, o in zip(ref, other):
        for d in r:
            best = max(o, key=lambda e: _box_iou(d.box, e.box), default=None)
            if best is not None and _box_iou(d.box, best.box) >= MATCH_IOU:
                agree.append(best.cls == d.cls)
                deltas.append(abs(best.score - d.score))
    return {"n_ref": sum(len(r) for r in ref), "n_other": sum(len(o) for o in other),
            "max_count_diff": max(count_diffs), "mean_best_iou": float(np.mean(best_ious)) if best_ious else None,
            "n_matched": len(agree),
            "class_agreement": float(np.mean(agree)) if agree else None,
            "max_score_delta": float(max(deltas)) if deltas else None}


def _merge(per_image: list[dict]) -> dict:
    return {k: (all(d[k] for d in per_image) if k.endswith("_ok") else max(d[k] for d in per_image))
            for k in per_image[0]}


def run_parity(torch_run, onnx_run, fp32_run, fp16_run, images) -> dict:
    """Compare raw outputs (torch vs onnx, torch vs tflite fp32) and postprocessed detections (torch vs tflite fp16)."""
    diffs: dict[str, list] = {"onnx": [], "tflite_fp32": []}
    thresholds = (GATE_THRESHOLD, INFO_THRESHOLD)
    ref_dets: dict[float, list] = {t: [] for t in thresholds}
    fp16_dets: dict[float, list] = {t: [] for t in thresholds}
    finite = {"pytorch": True, "onnx": True, "tflite_fp32": True, "tflite_fp16": True}
    n_gated_rows = 0
    for _, hwc in images:
        ref = torch_run(hwc)
        out_onnx, out32, out16 = onnx_run(hwc), fp32_run(hwc), fp16_run(hwc)
        for key, arr in (("pytorch", ref), ("onnx", out_onnx), ("tflite_fp32", out32), ("tflite_fp16", out16)):
            finite[key] = finite[key] and bool(np.isfinite(arr).all())
        n_gated_rows += int((ref[:, 4] * ref[:, 5:].max(axis=1) >= INFO_THRESHOLD).sum())
        diffs["onnx"].append(_max_diffs(ref, out_onnx, TOL_ONNX))
        diffs["tflite_fp32"].append(_max_diffs(ref, out32, TOL_TFLITE_FP32))
        for t in thresholds:
            ref_dets[t].append(postprocess(ref, score_threshold=t, nms_iou=DEFAULT_NMS_IOU))
            fp16_dets[t].append(postprocess(out16, score_threshold=t, nms_iou=DEFAULT_NMS_IOU))
    return {
        "n_images": len(images),
        "n_gated_rows": int(n_gated_rows),
        "n_ref_detections": sum(len(d) for d in ref_dets[GATE_THRESHOLD]),
        "onnx_max_abs_diff": _merge(diffs["onnx"]),
        "tflite_fp32_max_abs_diff": _merge(diffs["tflite_fp32"]),
        "tflite_fp16_detections": {f"{t:.2f}": detection_agreement(ref_dets[t], fp16_dets[t]) for t in thresholds},
        "pytorch_output_finite": finite["pytorch"], "onnx_output_finite": finite["onnx"],
        "tflite_fp32_output_finite": finite["tflite_fp32"], "tflite_fp16_output_finite": finite["tflite_fp16"],
    }


def check_parity(report: dict) -> list[str]:
    """Tolerance violations as readable messages (empty list = pass)."""
    problems = []
    if not report.get("n_gated_rows"):
        problems.append("parity is vacuous: no confident rows/detections; use images with cherries or a trained model")
    elif not report.get("n_ref_detections"):
        problems.append("parity is vacuous: no confident rows/detections; use images with cherries or a trained model "
                        "(n_ref_detections=0)")
    for key, tol, label in (("onnx_max_abs_diff", TOL_ONNX, "ONNX"),
                            ("tflite_fp32_max_abs_diff", TOL_TFLITE_FP32, "TFLite fp32")):
        for what, ok in (("boxes", "box_ok"), ("scores", "score_ok")):
            if not report[key][ok]:
                problems.append(f"{label} {what} max|diff| {report[key][what]:.3e} exceeds tolerance {tol[what]:.0e}"
                                + (f" + {BOX_RTOL:.0e}*|ref|" if what == "boxes" else ""))
    gate = report["tflite_fp16_detections"][f"{GATE_THRESHOLD:.2f}"]
    if gate["max_count_diff"] > FP16_MAX_COUNT_DIFF:
        problems.append(f"TFLite fp16 detection count differs by {gate['max_count_diff']} on some image "
                        f"(max {FP16_MAX_COUNT_DIFF})")
    if gate["mean_best_iou"] is not None and gate["mean_best_iou"] <= FP16_MIN_MEAN_IOU:
        problems.append(f"TFLite fp16 mean best IoU {gate['mean_best_iou']:.3f} <= {FP16_MIN_MEAN_IOU}")
    if gate.get("n_ref", 0) > 0:
        ca, sd = gate.get("class_agreement"), gate.get("max_score_delta")
        if ca is None or ca < FP16_MIN_CLASS_AGREEMENT:
            problems.append(f"TFLite fp16 class agreement {ca} < {FP16_MIN_CLASS_AGREEMENT} on matched detections")
        if sd is None or sd > FP16_MAX_SCORE_DELTA:
            problems.append(f"TFLite fp16 max score delta {sd} > {FP16_MAX_SCORE_DELTA} on matched detections")
    for key, label in (("pytorch_output_finite", "PyTorch"), ("onnx_output_finite", "ONNX"),
                       ("tflite_fp32_output_finite", "TFLite fp32"), ("tflite_fp16_output_finite", "TFLite fp16")):
        if report.get(key) is not True:
            problems.append(f"{label} output contains NaN/Inf (or finite check missing)")
    return problems


# ---------------------------------------------------------------- model.json

def _rel(path: Path) -> str | None:
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return None


def _finite(v) -> float | None:
    if v is None or isinstance(v, bool):
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def read_metrics(run_dir: Path | None, ckpt: dict) -> tuple[dict[str, float], str]:
    """Real validation metrics only, missing keys omitted (never null: the app parses Map<String, Double>).

    Source: <run>/metrics.json, else the AP the trainer stored in the checkpoint, else nothing (empty dict).
    YOLOX records `best_ap` (COCO AP@[.5:.95] on val, a fraction) in best_ckpt.pth but not AP50, so `val_map50`
    is only present when metrics.json provides it.
    """
    keys = ("val_map50", "val_map50_95")
    if run_dir is not None and (run_dir / "metrics.json").exists():
        try:
            raw = json.loads((run_dir / "metrics.json").read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise ExportError(f"malformed metrics.json in {run_dir}: {e}") from e
        if not isinstance(raw, dict):
            raise ExportError(f"malformed metrics.json in {run_dir}: expected an object")
        found = {}
        for k in keys:
            v = _finite(raw.get(k))
            if v is not None:
                found[k] = v
        return found, "metrics.json" if found else "none"
    best_ap = _finite(ckpt.get("best_ap")) if isinstance(ckpt, dict) else None
    if best_ap is not None:
        return {"val_map50_95": best_ap}, "checkpoint.best_ap"
    return {}, "none"


def build_model_json(*, training_run: str | None, metrics: dict, metrics_source: str, parity_report_sha256: str,
                     fp16: Path, fp32: Path, output_shape: list[int],
                     transposed: bool, size: tuple[int, int] = DEFAULT_SIZE,
                     parity_summary: dict | None = None) -> dict:
    taxonomy = load_taxonomy()
    output = {"shape": output_shape,
              "layout": ["cx", "cy", "w", "h", "obj", *(f"cls{i}" for i in range(len(taxonomy.ids)))],
              "coords": "input_pixels", "activations": "sigmoid_applied"}
    if transposed:
        output["transposed"] = True
    model_json = {
        "model_id": MODEL_ID,
        "variant": variant_id(size),
        "taxonomy_schema_version": taxonomy.schema_version,
        "classes": taxonomy.ids,
        "input": {"name": "images", "width": size[1], "height": size[0], "layout": "NHWC", "dtype": "float32",
                  "channel_order": "BGR", "range": [0, 255], "letterbox": "top_left", "pad_value": PAD_VALUE},
        "output": output,
        "postprocess": {"score": "obj*max(cls)", "default_score_threshold": DEFAULT_SCORE_THRESHOLD,
                        "nms": "class_agnostic_greedy", "default_nms_iou": DEFAULT_NMS_IOU,
                        "max_detections": DEFAULT_MAX_DETECTIONS},
        "files": {"fp16": {"name": FP16_NAME, "sha256": _sha256(fp16)},
                  "fp32": {"name": FP32_NAME, "sha256": _sha256(fp32)}},
        "training_run": training_run,
        "metrics": metrics,
        "metrics_source": metrics_source,
        "parity_report_sha256": parity_report_sha256,
        "license_attribution": LICENSE_ATTRIBUTION,
    }
    if parity_summary is not None:
        model_json["parity_summary"] = parity_summary
    return model_json


def make_parity_summary(report: dict) -> dict:
    """Compact parity numbers for model.json (the full report stays out of the APK)."""
    gate = report["tflite_fp16_detections"][f"{GATE_THRESHOLD:.2f}"]
    return {"n_images": report["n_images"], "n_ref": gate["n_ref"], "fp16_class_agreement": gate["class_agreement"],
            "fp16_mean_best_iou": gate["mean_best_iou"], "fp16_max_score_delta": gate["max_score_delta"],
            "fp16_max_count_diff": gate["max_count_diff"],
            "tflite_fp32_max_box_diff": report["tflite_fp32_max_abs_diff"]["boxes"],
            "tflite_fp32_max_score_diff": report["tflite_fp32_max_abs_diff"]["scores"]}


# ---------------------------------------------------------------- entry point

@dataclass(frozen=True)
class ExportConfig:
    out: Path = DEFAULT_OUT
    run: Path | None = None
    ckpt: Path | None = None
    val_dir: Path = DEFAULT_VAL_DIR
    report_dir: Path | None = None
    input_size: tuple[int, int] = DEFAULT_SIZE
    metrics: dict[str, float] | None = None  # measured at this size; replaces read_metrics (valid for 384x640 only)
    metrics_source: str | None = None


def _scratch_dir() -> Path:
    base = Path(os.environ.get("CP_TOOLS") or REPO_ROOT / ".tools") / "tmp"
    base.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix="cp_export_", dir=base))


def _run_of(ckpt_path: Path) -> Path | None:
    """`runs/<id>` when the checkpoint sits at runs/<id>/<exp>/best_ckpt.pth."""
    run = ckpt_path.resolve().parent.parent
    return run if run.parent.name == "runs" else None


def install_atomic(files: list[tuple[Path, str]], out: Path) -> None:
    """Copy each (source, name) to `<out>/<name>.part`, back up existing targets to `<name>.bak`, then os.replace
    the parts in the given order (put model.json last).

    On any error the backups are restored, every `.part` is removed and the error re-raised; on success the
    backups are removed.
    """
    out.mkdir(parents=True, exist_ok=True)
    parts = [out / f"{name}.part" for _, name in files]
    targets = [out / name for _, name in files]
    backups: dict[Path, Path] = {}
    replaced: list[Path] = []
    try:
        for (src, _), part in zip(files, parts):
            shutil.copyfile(src, part)
        for t in targets:
            if t.exists():
                bak = t.with_name(t.name + ".bak")
                shutil.copyfile(t, bak)
                backups[t] = bak
        for part, t in zip(parts, targets):
            os.replace(part, t)
            replaced.append(t)
    except BaseException:
        for part in parts:
            part.unlink(missing_ok=True)
        for t in targets:
            bak = backups.get(t)
            if bak is not None and bak.exists():
                os.replace(bak, t)
            elif t in replaced:
                t.unlink(missing_ok=True)  # did not exist before this install
        raise
    for bak in backups.values():
        bak.unlink(missing_ok=True)


def export(cfg: ExportConfig) -> dict:
    """Run the whole export. Returns the parity report; raises ExportError (nothing written to `cfg.out`) on failure."""
    if (cfg.run is None) == (cfg.ckpt is None):
        raise ExportError("give exactly one of --run or --ckpt")
    ckpt_path = cfg.ckpt if cfg.ckpt is not None else find_checkpoint(cfg.run)
    if not ckpt_path.exists():
        raise ExportError(f"checkpoint not found: {ckpt_path}")
    run_dir = cfg.run if cfg.run is not None else _run_of(ckpt_path)
    size = cfg.input_size
    if size[0] <= 0 or size[1] <= 0 or size[0] % 32 or size[1] % 32:
        raise ExportError(f"input size {size} must be positive multiples of 32")
    images = load_parity_images(cfg.val_dir, size=size)

    work = _scratch_dir()
    try:
        model, ckpt = load_torch_model(ckpt_path)
        onnx_path = work / "cuentapepas_nano.onnx"
        print(f"[export] ONNX opset {OPSET} from {ckpt_path}", flush=True)
        export_onnx(model, onnx_path, size)
        print("[export] onnx2tf ...", flush=True)
        fp32, fp16, flags = onnx_to_tflite(onnx_path, work)

        fp32_run, fp16_run, onnx_run = TfliteRunner(fp32, size), TfliteRunner(fp16, size), OnnxRunner(onnx_path)
        try:
            if fp32_run.output_shape != fp16_run.output_shape:
                raise ExportError(f"fp32/fp16 output shapes differ: {fp32_run.output_shape} vs {fp16_run.output_shape}")
            print(f"[export] parity on {len(images)} val images ...", flush=True)
            report = run_parity(TorchRunner(model), onnx_run, fp32_run, fp16_run, images)
        finally:
            fp32_run.close(), fp16_run.close(), onnx_run.close()
        problems = check_parity(report)
        if problems:
            raise ExportError("parity check failed:\n  " + "\n  ".join(problems) + "\n" + json.dumps(report, indent=2, allow_nan=False))

        if cfg.metrics is not None:
            metrics, metrics_source = cfg.metrics, cfg.metrics_source or "eval"
        else:
            metrics, metrics_source = read_metrics(run_dir, ckpt)
        want = [1, anchor_count(size), 5 + len(load_taxonomy().ids)]
        got = [1, *fp32_run.output_shape[:0:-1]] if fp32_run.transposed else fp32_run.output_shape
        if got != want:
            raise ExportError(f"unexpected output shape {fp32_run.output_shape}, want {want}")
        training_run = _rel(run_dir) if run_dir is not None else None
        out_shape = fp32_run.output_shape
        report.update({
            "checkpoint": _rel(ckpt_path) or str(ckpt_path), "checkpoint_sha256": _sha256(ckpt_path),
            "metrics_source": metrics_source, "onnx2tf_flags": flags, "variant": variant_id(size),
            "tflite_input": {"name": fp32_run.input["name"], "shape": [int(s) for s in fp32_run.input["shape"]]},
            "tflite_output": {"name": fp32_run.output["name"], "shape": out_shape, "transposed": fp32_run.transposed},
            "tolerances": {"onnx": TOL_ONNX, "tflite_fp32": TOL_TFLITE_FP32, "box_relative": BOX_RTOL,
                           "gated_rows": f"reference score >= {INFO_THRESHOLD}",
                           "fp16_max_count_diff": FP16_MAX_COUNT_DIFF, "fp16_min_mean_iou": FP16_MIN_MEAN_IOU,
                           "fp16_min_class_agreement": FP16_MIN_CLASS_AGREEMENT,
                           "fp16_max_score_delta": FP16_MAX_SCORE_DELTA},
            "parity_images": [name for name, _ in images],
        })

        report_dir = cfg.report_dir or (cfg.run / "export" if cfg.run is not None else ckpt_path.resolve().parent)
        report_dir.mkdir(parents=True, exist_ok=True)
        parity_path = report_dir / "parity.json"
        parity_path.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8", newline="\n")
        model_json = build_model_json(training_run=training_run, metrics=metrics, metrics_source=metrics_source,
                                      parity_report_sha256=_sha256(parity_path), fp16=fp16, fp32=fp32,
                                      output_shape=out_shape, transposed=fp32_run.transposed, size=size,
                                      parity_summary=make_parity_summary(report))
        model_json_path = work / "model.json"
        model_json_path.write_text(json.dumps(model_json, indent=2, allow_nan=False) + "\n", encoding="utf-8", newline="\n")
        report["parity_report_path"] = str(parity_path)
        install_atomic([(fp32, FP32_NAME), (fp16, FP16_NAME), (model_json_path, "model.json")], cfg.out)
        return report
    finally:
        gc.collect()
        try:
            shutil.rmtree(work)
        except OSError as e:
            log.warning("could not remove scratch directory %s: %s", work, e)


# ---------------------------------------------------------------- variants

VARIANT_LABELS_ES = {(384, 640): "Normal (rápido)", (544, 960): "Alta", (736, 1280): "Máxima (más lento)"}


def export_variants(run: Path, out: Path, sizes: list[tuple[int, int]], default: tuple[int, int],
                    val_dir: Path = DEFAULT_VAL_DIR, max_images: int | None = 300) -> dict:
    """Export each size into `<out>/r<H>x<W>/` (atomic per variant) with metrics measured at that size, then write
    `<out>/variants.json` last. Returns {variant id: parity report}."""
    from cuentapepas.evaluate import evaluate_run

    if default not in sizes:
        raise ExportError(f"default size {default} is not among the sizes {sizes}")
    if len(set(sizes)) != len(sizes):
        raise ExportError("duplicate sizes")
    reports = {}
    for size in sizes:
        vid = variant_id(size)
        print(f"[variants] {vid}: eval ({max_images or 'all'} images) ...", flush=True)
        m = evaluate_run(None, find_checkpoint(run), "val", max_images, input_size=size)
        measured = {"val_map50": m["map50"], "val_map50_95": m["map50_95"], "val_recall_iou50": m["recall_iou50"],
                    "val_recall_small": m["recall_by_size"]["small"]["recall"], "val_n_images": m["n_images"]}
        measured = {k: float(v) for k, v in measured.items() if v is not None}
        print(f"[variants] {vid}: export ...", flush=True)
        reports[vid] = export(ExportConfig(out=out / vid, run=run, val_dir=val_dir, report_dir=run / "export" / vid,
                                           input_size=size, metrics=measured,
                                           metrics_source=f"eval:val:n={m['n_images']}:{vid}"))
        reports[vid]["eval"] = m
    doc = {"default": variant_id(default),
           "variants": [{"id": variant_id(s), "label_es": VARIANT_LABELS_ES.get(s, variant_id(s)),
                         "input": [s[0], s[1]]} for s in sizes]}
    out.mkdir(parents=True, exist_ok=True)
    tmp = out / "variants.json.part"
    tmp.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    os.replace(tmp, out / "variants.json")
    return reports
