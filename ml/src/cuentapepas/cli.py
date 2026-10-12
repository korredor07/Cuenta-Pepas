"""Command line entry point: `cuentapepas <command>`."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from cuentapepas.catalog import load_source
from cuentapepas.datasets.coco_build import DEFAULT_SEED, DEFAULT_VAL_FRACTION, build_coco
from cuentapepas.taxonomy import REPO_ROOT, load_taxonomy
from cuentapepas.train import DEFAULT_PRETRAINED, SMOKE_IMAGES, TrainConfig, train
from cuentapepas.train import DEFAULT_SEED as TRAIN_SEED


def _repo_path(value: str) -> Path:
    """Relative paths are resolved against the repository root so the CLI works from any directory."""
    path = Path(value)
    return path if path.is_absolute() else REPO_ROOT / path


def _build_dataset(args: argparse.Namespace) -> int:
    source = load_source(args.source)
    manifest = _repo_path(args.manifest)
    summary = build_coco(
        _repo_path(args.raw), _repo_path(args.out), manifest, source, load_taxonomy(),
        val_fraction=args.val_fraction, seed=args.seed,
    )
    summary_path = manifest.with_suffix(".summary.json")
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(summary, indent=2))
    print(f"manifest: {manifest}")
    print(f"summary: {summary_path}")
    return 0


def _train(args: argparse.Namespace) -> int:
    return train(TrainConfig(batch=args.batch, epochs=args.epochs, workers=args.workers, smoke=args.smoke,
                             seed=args.seed, pretrained=_repo_path(args.pretrained)))


def _export(args: argparse.Namespace) -> int:
    from cuentapepas.export import ExportConfig, ExportError, export, parse_input_size

    cfg = ExportConfig(out=_repo_path(args.out), run=_repo_path(args.run) if args.run else None,
                       ckpt=_repo_path(args.ckpt) if args.ckpt else None, val_dir=_repo_path(args.val_dir),
                       report_dir=_repo_path(args.report_dir) if args.report_dir else None,
                       input_size=parse_input_size(args.input_size))
    try:
        report = export(cfg)
    except ExportError as exc:
        print(f"export failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({k: report[k] for k in ("onnx_max_abs_diff", "tflite_fp32_max_abs_diff", "tflite_fp16_detections")},
                     indent=2))
    print(f"exported to {cfg.out}\nparity report: {report['parity_report_path']}")
    return 0


def _eval(args: argparse.Namespace) -> int:
    from cuentapepas.evaluate import evaluate_run

    run = _repo_path(args.run)
    metrics = evaluate_run(run, _repo_path(args.ckpt) if args.ckpt else None, args.split, args.max_images,
                          input_size=_parse_size(args.input_size))
    print(json.dumps({k: metrics[k] for k in ("n_images", "map50", "map50_95", "ap50_per_class", "recall_iou50",
                                              "recall_by_size")}, indent=2))
    print(f"metrics: {run / 'metrics.json'}")
    return 0


def _parse_size(text: str) -> tuple[int, int]:
    from cuentapepas.export import parse_input_size

    return parse_input_size(text)


def _export_variants(args: argparse.Namespace) -> int:
    from cuentapepas.export import ExportError, export_variants

    try:
        sizes = [_parse_size(t) for t in args.sizes.split(",")]
        reports = export_variants(_repo_path(args.run), _repo_path(args.out), sizes, _parse_size(args.default),
                                  _repo_path(args.val_dir), args.max_images)
    except ExportError as exc:
        print(f"export-variants failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({k: r["tflite_fp16_detections"] for k, r in reports.items()}, indent=2))
    print(f"exported to {_repo_path(args.out)}")
    return 0


def _fixtures(args: argparse.Namespace) -> int:
    from cuentapepas.fixtures import make_fixtures

    print(json.dumps(make_fixtures(_repo_path(args.model), _repo_path(args.out)), indent=2))
    return 0


def _bench_images(args: argparse.Namespace) -> int:
    from cuentapepas.fixtures import make_bench_images

    print("\n".join(make_bench_images(_repo_path(args.out), args.n)))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cuentapepas")
    commands = parser.add_subparsers(dest="command", required=True)

    build = commands.add_parser("build-dataset", help="build a COCO dataset with a group split by original photo")
    build.add_argument("--source", required=True, help="catalog entry name (data/sources/<name>.yaml)")
    build.add_argument("--raw", required=True, help="root of the unzipped source data (contains train/ and val/)")
    build.add_argument("--out", required=True, help="output directory for the COCO dataset")
    build.add_argument("--manifest", required=True, help="output JSONL manifest; the summary goes next to it")
    build.add_argument("--val-fraction", type=float, default=DEFAULT_VAL_FRACTION)
    build.add_argument("--seed", type=int, default=DEFAULT_SEED)
    build.set_defaults(handler=_build_dataset)

    train_cmd = commands.add_parser("train", help="train YOLOX-Nano (fp32) and record the run in runs/<UTC>-<exp>/")
    train_cmd.add_argument("--batch", type=int, default=8)
    train_cmd.add_argument("--epochs", type=int, default=120)
    train_cmd.add_argument("--workers", type=int, default=2, help="DataLoader workers (0 = load in the main process)")
    train_cmd.add_argument("--seed", type=int, default=TRAIN_SEED)
    train_cmd.add_argument("--pretrained", default=str(DEFAULT_PRETRAINED),
                           help="COCO checkpoint to fine-tune from (see tools/get-pretrained.sh)")
    train_cmd.add_argument("--smoke", action="store_true",
                           help=f"1 epoch on a {SMOKE_IMAGES}-image-per-split subset, evaluating after the epoch")
    train_cmd.set_defaults(handler=_train)

    # Defaults are literals so `cuentapepas --help` does not import torch/cv2 (cuentapepas.export defines the same ones).
    export_cmd = commands.add_parser("export", help="export a checkpoint to ONNX/TFLite with parity checks and model.json")
    source = export_cmd.add_mutually_exclusive_group(required=True)
    source.add_argument("--run", help="training run directory (uses its best_ckpt.pth), e.g. runs/<id>")
    source.add_argument("--ckpt", help="checkpoint path (best_ckpt.pth)")
    export_cmd.add_argument("--out", default="android/app/src/main/assets/model",
                            help="output directory (default: the Android assets model dir)")
    export_cmd.add_argument("--val-dir", default="data/local/processed/croppie_v1/val",
                            help="val images used for the parity check")
    export_cmd.add_argument("--report-dir", help="where parity.json goes (default: <run>/export with --run, else next to "
                                                 "the checkpoint; never --out, which ships in the APK)")
    export_cmd.add_argument("--input-size", default="384x640", help="static input HxW, multiples of 32")
    export_cmd.set_defaults(handler=_export)

    var = commands.add_parser("export-variants",
                              help="export several input resolutions into <out>/r<H>x<W>/ + variants.json")
    var.add_argument("--run", required=True)
    var.add_argument("--out", required=True)
    var.add_argument("--sizes", default="384x640,544x960,736x1280")
    var.add_argument("--default", default="544x960")
    var.add_argument("--val-dir", default="data/local/processed/croppie_v1/val")
    var.add_argument("--max-images", type=int, default=300, help="val images for the per-size eval")
    var.set_defaults(handler=_export_variants)

    eval_cmd = commands.add_parser("eval", help="COCO mAP, recall and confusion matrix on a split -> runs/<id>/metrics.json")
    eval_cmd.add_argument("--run", required=True, help="training run directory, e.g. runs/<id>")
    eval_cmd.add_argument("--ckpt", help="checkpoint path (default: best_ckpt.pth of the run)")
    eval_cmd.add_argument("--split", default="val")
    eval_cmd.add_argument("--max-images", type=int, default=None, help="evaluate an evenly spaced subset (quick checks)")
    eval_cmd.add_argument("--input-size", default="384x640", help="test size HxW, multiples of 32")
    eval_cmd.set_defaults(handler=_eval)

    fix = commands.add_parser("fixtures", help="Kotlin parity fixtures (raw_<k>.bin + expected_<k>.json) from a fp32 TFLite")
    fix.add_argument("--model", required=True, help="fp32 .tflite")
    fix.add_argument("--out", required=True)
    fix.set_defaults(handler=_fixtures)

    bench = commands.add_parser("bench-images", help="val images letterboxed to 1280x720 JPEG + ATTRIBUTION.txt")
    bench.add_argument("--out", required=True)
    bench.add_argument("--n", type=int, default=20)
    bench.set_defaults(handler=_bench_images)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
