"""Standalone exporter for ONLY the pooled `yamnet.tflite`.

The CI build uses this to bake a working on-device YAMNet feature extractor into the APK
without re-running the whole Colab training pipeline. It produces the exact same file that
`python -m src.main` would (same `convert_yamnet`), so Colab and CI stay in sync.

Usage:
    python ml-training/tools/export_yamnet.py \
        --config ml-training/configs/default.yaml \
        --out app/src/main/assets/yamnet.tflite
"""
from __future__ import annotations

import os

# Match the training pipeline: force the Keras 2 backend before TensorFlow is imported.
os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")

import argparse
import sys
from pathlib import Path

import yaml

# Make the `src` package importable regardless of the current working directory.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.export.to_tflite import convert_yamnet  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser(description="Export the pooled yamnet.tflite only")
    ap.add_argument("--config", default="configs/default.yaml")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    with open(args.config) as f:
        config = yaml.safe_load(f)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    convert_yamnet(
        config["features"]["yamnet_handle"],
        out,
        gate_class_name=config["gate"]["infant_cry_class_name"],
    )


if __name__ == "__main__":
    main()
