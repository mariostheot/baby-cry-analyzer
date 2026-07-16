"""Waveform augmentation used to (a) balance rare classes and (b) close the gap to
noisy, real-world home recordings.

Augmentation is applied to the WAVEFORM before YAMNet, so the augmented example gets a
genuinely different embedding (as opposed to spectrogram-only tricks).
"""
from __future__ import annotations

import numpy as np


class Augmenter:
    def __init__(self, config: dict, sample_rate: int, seed: int = 42) -> None:
        aug = config.get("augment", {})
        self.enabled = aug.get("enabled", True)
        self.noise_snr_db = tuple(aug.get("noise_snr_db", [5.0, 20.0]))
        self.pitch_semitones = tuple(aug.get("pitch_semitones", [-2.0, 2.0]))
        self.time_stretch = tuple(aug.get("time_stretch", [0.9, 1.1]))
        self.gain_db = tuple(aug.get("gain_db", [-3.0, 3.0]))
        self.reverb_mix = tuple(aug.get("reverb_mix", [0.05, 0.25]))
        self.reverb_delay_ms = tuple(aug.get("reverb_delay_ms", [18.0, 55.0]))
        self.sample_rate = sample_rate
        self.rng = np.random.default_rng(seed)

    def _uniform(self, lo: float, hi: float) -> float:
        return float(self.rng.uniform(lo, hi))

    def add_noise(self, wave: np.ndarray, snr_db: float) -> np.ndarray:
        sig_power = float(np.mean(wave ** 2)) + 1e-12
        noise_power = sig_power / (10.0 ** (snr_db / 10.0))
        noise = self.rng.normal(0.0, np.sqrt(noise_power), size=wave.shape)
        return wave + noise.astype(np.float32)

    def pitch_shift(self, wave: np.ndarray, semitones: float) -> np.ndarray:
        import librosa

        return librosa.effects.pitch_shift(
            y=wave, sr=self.sample_rate, n_steps=semitones
        )

    def stretch(self, wave: np.ndarray, rate: float) -> np.ndarray:
        import librosa

        return librosa.effects.time_stretch(y=wave, rate=rate)

    def gain(self, wave: np.ndarray, db: float) -> np.ndarray:
        return wave * (10.0 ** (db / 20.0))

    def add_reverb(self, wave: np.ndarray, mix: float, delay_ms: float) -> np.ndarray:
        """Small deterministic synthetic room tail; avoids an external impulse-response corpus."""
        delay = max(1, int(self.sample_rate * delay_ms / 1000.0))
        out = wave.astype(np.float32, copy=True)
        # Three diminishing reflections approximate a modest room without changing clip length.
        for reflection, gain in ((1, mix), (2, mix * 0.55), (3, mix * 0.30)):
            shift = reflection * delay
            if shift >= out.size:
                break
            out[shift:] += wave[:-shift] * gain
        return out

    def augment(self, wave: np.ndarray) -> np.ndarray:
        """Apply a random combination of augmentations to a single waveform."""
        w = np.asarray(wave, dtype=np.float32)
        if not self.enabled:
            return w
        if self.rng.random() < 0.7:
            w = self.pitch_shift(w, self._uniform(*self.pitch_semitones))
        if self.rng.random() < 0.5:
            w = self.stretch(w, self._uniform(*self.time_stretch))
        if self.rng.random() < 0.7:
            w = self.gain(w, self._uniform(*self.gain_db))
        if self.rng.random() < 0.7:
            w = self.add_noise(w, self._uniform(*self.noise_snr_db))
        if self.rng.random() < 0.5:
            w = self.add_reverb(
                w,
                self._uniform(*self.reverb_mix),
                self._uniform(*self.reverb_delay_ms),
            )
        return np.clip(w, -1.0, 1.0).astype(np.float32)

    @staticmethod
    def plan_oversampling(labels: np.ndarray, target_per_class: int) -> dict[int, int]:
        """How many augmented copies to add per class to reach ``target_per_class``.

        Returns {class_index: copies_per_original_sample}. Never downsamples.
        """
        plan: dict[int, int] = {}
        classes, counts = np.unique(labels, return_counts=True)
        for cls, count in zip(classes, counts):
            if count == 0:
                continue
            needed = max(0, target_per_class - count)
            plan[int(cls)] = int(np.ceil(needed / count))
        return plan
