"""Maps the various source taxonomies onto the 5 canonical cry categories.

Canonical classes (order matters - must match the Android `CryReason` enum and the
generated `labels.txt`):

    0 hungry
    1 tired
    2 discomfort
    3 belly_pain
    4 burping

Some source labels have no clean mapping (e.g. "lonely", "scared", "unknown") - those
return ``None`` and are dropped from the reason-classification training set.
"""
from __future__ import annotations

from typing import Optional

# Canonical order (keep in sync with configs/default.yaml and CryReason.kt).
CANONICAL: list[str] = ["hungry", "tired", "discomfort", "belly_pain", "burping"]

# Uppercase enum names written to labels.txt (CryReason.valueOf compatible).
ENUM_NAME: dict[str, str] = {c: c.upper() for c in CANONICAL}

# --- donateacry-corpus (folder names / filename reason codes) -------------------
# Cleaned corpus uses folder names; raw filenames use 2-letter codes.
_DONATEACRY_FOLDER = {
    "hungry": "hungry",
    "tired": "tired",
    "discomfort": "discomfort",
    "belly_pain": "belly_pain",
    "burping": "burping",
}
_DONATEACRY_CODE = {
    "hu": "hungry",
    "ti": "tired",
    "dc": "discomfort",
    "bp": "belly_pain",
    "bu": "burping",
    # Codes intentionally dropped (no clean mapping): lo (lonely), ch (cold/hot),
    # sc (scared), dk (don't know).
}

# --- Kaggle 8-class infant cry set ---------------------------------------------
_KAGGLE_8 = {
    "hungry": "hungry",
    "tired": "tired",
    "discomfort": "discomfort",
    "belly_pain": "belly_pain",
    "bellypain": "belly_pain",
    "burping": "burping",
    "cold_hot": "discomfort",   # merge temperature discomfort into discomfort
    "cold-hot": "discomfort",
    "coldhot": "discomfort",
    "lonely": None,             # attention-seeking; no physiological need -> drop
    "scared": None,
    "unknown": None,
    "silence": None,
    "noise": None,
}

# --- InfantCry-DBL (Dunstan Baby Language sounds) ------------------------------
# Approximate mapping DBL sound -> physiological need. Documented as best-effort.
_DBL = {
    "neh": "hungry",       # "neh" ~ hunger (tongue/suck reflex)
    "eh": "burping",       # "eh" ~ need to burp (upper wind)
    "eairh": "belly_pain", # "eairh"/"eair" ~ lower gas / belly pain
    "eair": "belly_pain",
    "heh": "discomfort",   # "heh" ~ discomfort (hot/cold/wet)
    "owh": "tired",        # "owh"/"oah" ~ tired/sleepy
    "oah": "tired",
}


def _norm(label: str) -> str:
    return label.strip().lower().replace(" ", "_")


def to_canonical(source: str, raw_label: str) -> Optional[str]:
    """Return the canonical class for ``raw_label`` from ``source``, or None to drop."""
    key = _norm(raw_label)
    if source == "donateacry":
        return _DONATEACRY_FOLDER.get(key) or _DONATEACRY_CODE.get(key)
    if source == "kaggle_infant_cry":
        return _KAGGLE_8.get(key)
    if source == "infantcry_dbl":
        return _DBL.get(key)
    # Sources without reason labels (esc50/cryceleb/babycry) never reach here for
    # the reason classifier; they are handled separately by the gate pipeline.
    return _DONATEACRY_FOLDER.get(key)  # fallback: accept already-canonical labels


def reason_code_from_donateacry_filename(filename: str) -> Optional[str]:
    """donateacry filenames end with ``-<reason>.<ext>`` e.g. ``...-hu.wav``."""
    stem = filename.rsplit(".", 1)[0]
    parts = stem.split("-")
    if not parts:
        return None
    return _DONATEACRY_CODE.get(parts[-1].lower())


def instance_id_from_donateacry_filename(filename: str) -> Optional[str]:
    """The leading UUID identifies the app install (proxy for one baby/family).

    Used as the CV group id so the same baby never appears in both train and test.
    """
    stem = filename.rsplit(".", 1)[0]
    # iOS: 36-char UUID prefix; Android: shorter ids. Take the first dash-group.
    parts = stem.split("-")
    if len(parts) >= 5 and len("-".join(parts[:5])) == 36:
        return "-".join(parts[:5])  # full UUID
    return parts[0] if parts else None
