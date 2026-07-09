"""End-to-end training entry point.

Usage:
    python -m src.main --config configs/default.yaml

Steps: download -> build manifest -> embed + train (grouped CV) -> evaluate -> export
TFLite bundle -> parity sample. Every step is resumable-ish thanks to embedding caching.
"""
from __future__ import annotations

import os

# Colab / TF 2.16+ default to Keras 3, but this pipeline targets Keras 2 (tf.keras
# models, TFLite `from_keras_model`, Normalization weight layout). Force the legacy
# Keras backend BEFORE TensorFlow gets imported anywhere downstream.
os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")

import argparse
from pathlib import Path

import yaml

from .datasets.build_manifest import build_manifest
from .datasets.download import download_all
from .eval.evaluate import run as evaluate_run
from .export.parity_check import run as parity_run
from .export.to_tflite import export_all
from .features.yamnet_embed import YamnetEmbedder
from .model.train import train


def load_config(path: str) -> dict:
    with open(path) as f:
        return yaml.safe_load(f)


def build_embedder(config: dict) -> YamnetEmbedder:
    f = config["features"]
    return YamnetEmbedder(
        handle=f["yamnet_handle"],
        sample_rate=config["sample_rate"],
        min_seconds=f["min_seconds"],
        max_seconds=f["max_seconds"],
        cache_dir=config["paths"]["cache_dir"],
        pooling=f["pooling"],
        gate_class_name=config["gate"]["infant_cry_class_name"],
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Baby Cry AI - train pipeline")
    parser.add_argument("--config", default="configs/default.yaml")
    parser.add_argument("--skip-download", action="store_true")
    parser.add_argument("--skip-eval", action="store_true")
    args = parser.parse_args()

    config = load_config(args.config)
    Path(config["paths"]["artifacts_dir"]).mkdir(parents=True, exist_ok=True)

    if args.skip_download:
        # Reuse whatever is already on disk.
        from .datasets.download import (
            download_donateacry, download_infantcry_dbl, download_kaggle_infant_cry,
        )
        data_dir = Path(config["paths"]["data_dir"])
        sources = {
            "donateacry": download_donateacry(data_dir),
            "kaggle_infant_cry": (
                download_kaggle_infant_cry(
                    data_dir, config["datasets"]["kaggle_infant_cry"]["kaggle_ref"])
                if config["datasets"]["kaggle_infant_cry"]["enabled"] else None
            ),
            "infantcry_dbl": (
                download_infantcry_dbl(
                    data_dir, config["datasets"]["infantcry_dbl"]["mendeley_doi"])
                if config["datasets"]["infantcry_dbl"]["enabled"] else None
            ),
        }
    else:
        sources = download_all(config)

    build_manifest(sources, config)

    embedder = build_embedder(config)
    result = train(config, embedder=embedder)

    if not args.skip_eval:
        evaluate_run(config, X=result["X"], y=result["y"], groups=result["groups"])

    export_all(result, config)
    parity_run(config, embedder)

    print("\n[done] Model bundle is in "
          f"{Path(config['paths']['artifacts_dir']) / 'model_bundle'}")
    print("[done] Copy cry_reason.tflite, yamnet.tflite and labels.txt into "
          "app/src/main/assets/ (or push the repo and let GitHub Actions build the APK).")


if __name__ == "__main__":
    main()
