"""Builds a unified manifest (one row per audio clip) from the downloaded datasets.

Columns: ``path, label, source, group_id``.

- ``label`` is one of the 5 canonical classes (rows that cannot be mapped are dropped).
- ``group_id`` groups clips that belong to the same baby/family so that
  StratifiedGroupKFold never puts the same baby in both train and test.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

import pandas as pd

from . import label_map

AUDIO_EXTS = {".wav", ".ogg", ".3gp", ".caf", ".flac", ".mp3", ".m4a", ".aac"}


def _iter_audio(root: Path):
    for p in root.rglob("*"):
        if p.suffix.lower() in AUDIO_EXTS and p.is_file():
            yield p


def _rows_folder_per_class(root: Path, source: str) -> list[dict]:
    """Generic reader for datasets laid out as <root>/<class_name>/<clip>."""
    rows: list[dict] = []
    for audio in _iter_audio(root):
        class_folder = audio.parent.name
        canonical = label_map.to_canonical(source, class_folder)
        if canonical is None:
            continue
        group = label_map.instance_id_from_donateacry_filename(audio.name)
        if not group:
            group = f"{source}:{audio.stem}"  # conservative: clip is its own group
        rows.append(
            {"path": str(audio), "label": canonical, "source": source, "group_id": group}
        )
    return rows


def _rows_donateacry(root: Path) -> list[dict]:
    rows: list[dict] = []
    for audio in _iter_audio(root):
        # Prefer folder name, fall back to the filename reason code.
        canonical = label_map.to_canonical("donateacry", audio.parent.name)
        if canonical is None:
            canonical = label_map.reason_code_from_donateacry_filename(audio.name)
        if canonical is None:
            continue
        group = label_map.instance_id_from_donateacry_filename(audio.name) or audio.stem
        rows.append(
            {"path": str(audio), "label": canonical, "source": "donateacry",
             "group_id": group}
        )
    return rows


def build_manifest(sources: dict[str, Optional[Path]], config: dict) -> pd.DataFrame:
    """Combine all reason-labeled sources into one DataFrame."""
    rows: list[dict] = []

    if sources.get("donateacry"):
        rows += _rows_donateacry(sources["donateacry"])
    if sources.get("kaggle_infant_cry"):
        rows += _rows_folder_per_class(sources["kaggle_infant_cry"], "kaggle_infant_cry")
    if sources.get("infantcry_dbl"):
        rows += _rows_folder_per_class(sources["infantcry_dbl"], "infantcry_dbl")
    # esc50 / cryceleb / babycry have no reason labels -> not part of this manifest.

    df = pd.DataFrame(rows, columns=["path", "label", "source", "group_id"])
    if df.empty:
        raise RuntimeError(
            "No labeled audio found. Check that at least donateacry downloaded "
            "correctly (see the [download] logs)."
        )

    # Keep only canonical classes, in canonical order for a stable label index.
    classes = config["classes"]
    df = df[df["label"].isin(classes)].reset_index(drop=True)
    df["label"] = pd.Categorical(df["label"], categories=classes, ordered=True)

    out = Path(config["paths"]["artifacts_dir"]) / "manifest.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(out, index=False)
    print(f"[manifest] {len(df)} clips -> {out}")
    print("[manifest] class distribution:\n" + df["label"].value_counts().to_string())
    print("[manifest] per-source counts:\n" + df.groupby("source").size().to_string())
    print(f"[manifest] unique groups: {df['group_id'].nunique()}")
    return df
