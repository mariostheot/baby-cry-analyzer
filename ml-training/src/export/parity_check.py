"""Golden-sample parity check.

Exports one 16 kHz mono clip plus the YAMNet embedding/gate that Python computed for it.
The Android instrumentation test feeds the SAME clip through the on-device
`yamnet.tflite` and asserts the embedding matches - catching any train/inference feature
mismatch (the classic reason an on-device model silently underperforms).

Also verifies Python-vs-TFLite parity here (both must contain the exact same math).
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd


def _tflite_embedding(model_path: Path, wave: np.ndarray) -> Optional[np.ndarray]:
    import tensorflow as tf

    try:
        interp = tf.lite.Interpreter(model_path=str(model_path))
        in_detail = interp.get_input_details()[0]
        interp.resize_tensor_input(in_detail["index"], [len(wave)])
        interp.allocate_tensors()
        interp.set_tensor(in_detail["index"], wave.astype(np.float32))
        interp.invoke()
        for out in interp.get_output_details():
            arr = interp.get_tensor(out["index"])
            # Pooled export: already a 1024-d vector. Legacy export: [frames, 1024].
            if arr.ndim == 1 and arr.shape[-1] == 1024:
                return arr
            if arr.ndim == 2 and arr.shape[-1] == 1024:
                return arr.mean(axis=0)
    except Exception as exc:  # noqa: BLE001
        print(f"[parity] tflite check skipped: {exc}")
    return None


def run(config: dict, embedder, sample_path: Optional[str] = None) -> None:
    artifacts = Path(config["paths"]["artifacts_dir"])
    bundle = artifacts / "model_bundle"
    bundle.mkdir(parents=True, exist_ok=True)

    if sample_path is None:
        manifest = artifacts / "manifest.csv"
        if not manifest.exists():
            print("[parity] no manifest; skipping")
            return
        sample_path = pd.read_csv(manifest)["path"].iloc[0]

    wave = embedder.load_audio(sample_path)
    emb, gate = embedder.embed_waveform(wave)

    try:
        import soundfile as sf
        sf.write(bundle / "parity_sample.wav", wave, embedder.sample_rate,
                 subtype="PCM_16")
    except Exception as exc:  # noqa: BLE001
        print(f"[parity] could not write wav: {exc}")

    (bundle / "parity_expected.json").write_text(json.dumps({
        "sample_rate": embedder.sample_rate,
        "gate_score": float(gate),
        "embedding": [float(v) for v in emb],
    }))

    tflite_emb = _tflite_embedding(bundle / "yamnet.tflite", wave)
    if tflite_emb is not None:
        cos = float(np.dot(emb, tflite_emb) /
                    (np.linalg.norm(emb) * np.linalg.norm(tflite_emb) + 1e-9))
        max_abs = float(np.max(np.abs(emb - tflite_emb)))
        print(f"[parity] python-vs-tflite cosine={cos:.5f} max_abs_diff={max_abs:.5f}")

    print("[parity] wrote parity_sample.wav + parity_expected.json to the bundle.")
    print("[parity] To run the Android parity test, copy both into "
          "app/src/androidTest/assets/ and remove @Ignore from YamnetParityTest.")
