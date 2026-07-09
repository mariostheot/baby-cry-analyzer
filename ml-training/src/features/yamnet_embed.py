"""YAMNet feature extraction.

YAMNet (a MobileNet-based deep CNN pretrained on Google AudioSet) is used two ways:

1. As a frozen feature extractor: each clip -> a 1024-d embedding (mean-pooled over
   its ~0.48s frames). This embedding is the input to our trained reason classifier.
2. As a cry-presence gate: YAMNet also predicts 521 AudioSet classes; we read the
   "Baby cry, infant cry" score to decide whether the audio actually contains a cry.

The identical model runs on the phone (yamnet.tflite), so on-device embeddings match
what the classifier was trained on.
"""
from __future__ import annotations

import csv
import hashlib
from pathlib import Path
from typing import Optional

import numpy as np


class YamnetEmbedder:
    def __init__(
        self,
        handle: str = "https://tfhub.dev/google/yamnet/1",
        sample_rate: int = 16000,
        min_seconds: float = 0.5,
        max_seconds: float = 10.0,
        cache_dir: Optional[str] = None,
        pooling: str = "mean",
        gate_class_name: str = "Baby cry, infant cry",
    ) -> None:
        import tensorflow_hub as hub  # imported lazily so `import` is cheap

        self.sample_rate = sample_rate
        self.min_seconds = min_seconds
        self.max_seconds = max_seconds
        self.pooling = pooling
        self.cache_dir = Path(cache_dir) if cache_dir else None
        if self.cache_dir:
            self.cache_dir.mkdir(parents=True, exist_ok=True)

        print(f"[yamnet] loading model from {handle} ...", flush=True)
        self.model = hub.load(handle)
        self.gate_index = self._find_class_index(gate_class_name)
        print(f"[yamnet] ready (gate class index={self.gate_index})", flush=True)

    def _find_class_index(self, name: str) -> int:
        class_map_path = self.model.class_map_path().numpy().decode("utf-8")
        with open(class_map_path) as f:
            reader = csv.DictReader(f)
            for row in reader:
                if row["display_name"].strip() == name:
                    return int(row["index"])
        # Fallback: known AudioSet index for "Baby cry, infant cry".
        return 20

    def load_audio(self, path: str) -> np.ndarray:
        import librosa

        wave, _ = librosa.load(path, sr=self.sample_rate, mono=True)
        return self._fit_length(wave.astype(np.float32))

    def _fit_length(self, wave: np.ndarray) -> np.ndarray:
        min_len = int(self.min_seconds * self.sample_rate)
        max_len = int(self.max_seconds * self.sample_rate)
        if wave.shape[0] < min_len:
            wave = np.pad(wave, (0, min_len - wave.shape[0]))
        elif wave.shape[0] > max_len:
            start = (wave.shape[0] - max_len) // 2
            wave = wave[start:start + max_len]
        return wave

    def embed_waveform(self, wave: np.ndarray) -> tuple[np.ndarray, float]:
        """Return (1024-d embedding, infant-cry gate score) for a waveform."""
        import tensorflow as tf

        wave = self._fit_length(np.asarray(wave, dtype=np.float32))
        scores, embeddings, _ = self.model(tf.constant(wave, dtype=tf.float32))
        emb = embeddings.numpy()
        if self.pooling == "mean":
            pooled = emb.mean(axis=0)
        elif self.pooling == "max":
            pooled = emb.max(axis=0)
        else:
            pooled = emb.mean(axis=0)
        gate = float(scores.numpy()[:, self.gate_index].max())
        return pooled.astype(np.float32), gate

    def _cache_path(self, path: str) -> Optional[Path]:
        if not self.cache_dir:
            return None
        p = Path(path)
        try:
            mtime = int(p.stat().st_mtime)
        except OSError:
            mtime = 0
        key = f"{path}|{mtime}|{self.pooling}|{self.sample_rate}"
        digest = hashlib.md5(key.encode()).hexdigest()  # noqa: S324 (cache key only)
        return self.cache_dir / f"{digest}.npz"

    def embed_file(self, path: str, use_cache: bool = True) -> tuple[np.ndarray, float]:
        cache = self._cache_path(path) if use_cache else None
        if cache and cache.exists():
            data = np.load(cache)
            return data["emb"].astype(np.float32), float(data["gate"])
        emb, gate = self.embed_waveform(self.load_audio(path))
        if cache is not None:
            np.savez(cache, emb=emb, gate=np.float32(gate))
        return emb, gate

    def embed_paths(
        self, paths: list[str]
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """Embed many files.

        Returns ``(X[K,1024], gate_scores[K], kept_idx[K])`` where ``kept_idx`` are the
        indices into ``paths`` that were embedded successfully. The caller must use
        ``kept_idx`` to filter labels/groups so everything stays aligned.
        """
        try:
            from tqdm import tqdm
            iterator = tqdm(list(enumerate(paths)), desc="[yamnet] embedding")
        except Exception:  # noqa: BLE001
            iterator = list(enumerate(paths))
        embs, gates, kept = [], [], []
        for i, p in iterator:
            try:
                emb, gate = self.embed_file(p)
            except Exception as exc:  # noqa: BLE001
                print(f"[yamnet] skip {p}: {exc}")
                continue
            embs.append(emb)
            gates.append(gate)
            kept.append(i)
        if not embs:
            raise RuntimeError("No embeddings computed - all files failed to load.")
        return (
            np.vstack(embs).astype(np.float32),
            np.asarray(gates, dtype=np.float32),
            np.asarray(kept, dtype=np.int64),
        )
