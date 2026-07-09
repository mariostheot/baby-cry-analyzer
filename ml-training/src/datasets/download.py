"""Downloads the raw datasets.

Every function is defensive: if a source needs credentials that are missing, or a
network download fails, it prints guidance and returns ``None`` instead of crashing,
so the pipeline can continue with whatever data is available.
"""
from __future__ import annotations

import os
import subprocess
import urllib.request
import zipfile
from pathlib import Path
from typing import Optional


def _log(msg: str) -> None:
    print(f"[download] {msg}", flush=True)


def _download_file(url: str, dest: Path) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        _log(f"cached: {dest.name}")
        return True
    try:
        _log(f"GET {url}")
        urllib.request.urlretrieve(url, dest)  # noqa: S310 (trusted URLs only)
        return True
    except Exception as exc:  # noqa: BLE001
        _log(f"failed to download {url}: {exc}")
        return False


def _extract_zip(zip_path: Path, dest_dir: Path) -> bool:
    try:
        with zipfile.ZipFile(zip_path) as zf:
            zf.extractall(dest_dir)
        return True
    except Exception as exc:  # noqa: BLE001
        _log(f"failed to extract {zip_path}: {exc}")
        return False


def download_donateacry(data_dir: Path) -> Optional[Path]:
    """Clone the donateacry-corpus and return the cleaned-data folder."""
    repo_dir = data_dir / "donateacry-corpus"
    cleaned = repo_dir / "donateacry_corpus_cleaned_and_updated_data"
    if cleaned.exists():
        _log("donateacry: cached")
        return cleaned
    try:
        subprocess.check_call(
            ["git", "clone", "--depth", "1",
             "https://github.com/gveres/donateacry-corpus", str(repo_dir)]
        )
    except Exception as exc:  # noqa: BLE001
        _log(f"donateacry: git clone failed ({exc}); trying zip")
        zip_path = data_dir / "donateacry.zip"
        if _download_file(
            "https://github.com/gveres/donateacry-corpus/archive/refs/heads/master.zip",
            zip_path,
        ) and _extract_zip(zip_path, data_dir):
            extracted = data_dir / "donateacry-corpus-master"
            if extracted.exists():
                extracted.rename(repo_dir)
    if cleaned.exists():
        return cleaned
    _log("donateacry: NOT available")
    return None


def download_kaggle_infant_cry(data_dir: Path, kaggle_ref: str) -> Optional[Path]:
    """Download a Kaggle infant-cry dataset (requires ~/.kaggle/kaggle.json)."""
    out = data_dir / "kaggle_infant_cry"
    if out.exists() and any(out.rglob("*.wav")):
        _log("kaggle: cached")
        return out
    try:
        out.mkdir(parents=True, exist_ok=True)
        subprocess.check_call(
            ["kaggle", "datasets", "download", "-d", kaggle_ref,
             "-p", str(out), "--unzip"]
        )
        return out
    except Exception as exc:  # noqa: BLE001
        _log(
            "kaggle: could not download. Make sure the `kaggle` package is installed "
            "and ~/.kaggle/kaggle.json exists (see README). "
            f"Ref={kaggle_ref}. Error={exc}"
        )
        return None


def download_infantcry_dbl(data_dir: Path, doi: str) -> Optional[Path]:
    """InfantCry-DBL (Mendeley Data, CC BY 4.0).

    Mendeley download links are per-version and sometimes require a browser. We try
    the public API; if that fails we print manual instructions.
    """
    out = data_dir / "infantcry_dbl"
    if out.exists() and any(out.rglob("*.wav")):
        _log("infantcry_dbl: cached")
        return out
    out.mkdir(parents=True, exist_ok=True)
    zip_path = data_dir / "infantcry_dbl.zip"
    # Mendeley public archive endpoint (version 1). May change; hence best-effort.
    api = "https://data.mendeley.com/public-files/datasets/x493z8nmwc/files/archive"
    if _download_file(api, zip_path) and _extract_zip(zip_path, out):
        return out
    _log(
        "infantcry_dbl: automatic download failed. Manually download the dataset from "
        f"https://doi.org/{doi} and unzip it into "
        f"'{out}' (folder-per-class), then re-run."
    )
    return None


def download_esc50(data_dir: Path) -> Optional[Path]:
    """ESC-50 - used ONLY as non-cry negatives for an optional custom gate."""
    out = data_dir / "ESC-50-master"
    if out.exists():
        _log("esc50: cached")
        return out
    zip_path = data_dir / "esc50.zip"
    if _download_file(
        "https://github.com/karoldvl/ESC-50/archive/master.zip", zip_path
    ) and _extract_zip(zip_path, data_dir):
        return out if out.exists() else None
    return None


def download_cryceleb(data_dir: Path, hf_repo: str) -> Optional[Path]:
    """CryCeleb2023 from Hugging Face (gated; requires `huggingface-cli login`)."""
    out = data_dir / "cryceleb"
    if out.exists() and any(out.rglob("*.wav")):
        _log("cryceleb: cached")
        return out
    try:
        from huggingface_hub import snapshot_download

        path = snapshot_download(
            repo_id=hf_repo, repo_type="dataset", local_dir=str(out)
        )
        return Path(path)
    except Exception as exc:  # noqa: BLE001
        _log(
            "cryceleb: could not download. Accept the terms on the HF dataset page and "
            f"run `huggingface-cli login`. Repo={hf_repo}. Error={exc}"
        )
        return None


def download_babycry_ujm(data_dir: Path) -> Optional[Path]:
    """BABYCRY-UJM-AXA (Zenodo). Very large; manual download recommended."""
    out = data_dir / "babycry_ujm"
    if out.exists() and any(out.rglob("*.wav")):
        _log("babycry_ujm: cached")
        return out
    _log(
        "babycry_ujm: skipped (multi-GB). If you want it, download BABYCRY-UJM-AXA.zip "
        f"from https://doi.org/10.5281/zenodo.19205928 into '{out}' and unzip."
    )
    return None


def download_all(config: dict) -> dict[str, Optional[Path]]:
    """Run all enabled downloads; return {source_name: local_path_or_None}."""
    data_dir = Path(config["paths"]["data_dir"])
    data_dir.mkdir(parents=True, exist_ok=True)
    ds = config["datasets"]
    result: dict[str, Optional[Path]] = {}

    if ds["donateacry"]["enabled"]:
        result["donateacry"] = download_donateacry(data_dir)
    if ds["kaggle_infant_cry"]["enabled"]:
        result["kaggle_infant_cry"] = download_kaggle_infant_cry(
            data_dir, ds["kaggle_infant_cry"]["kaggle_ref"]
        )
    if ds["infantcry_dbl"]["enabled"]:
        result["infantcry_dbl"] = download_infantcry_dbl(
            data_dir, ds["infantcry_dbl"]["mendeley_doi"]
        )
    if ds["esc50_negatives"]["enabled"]:
        result["esc50_negatives"] = download_esc50(data_dir)
    if ds["cryceleb"]["enabled"]:
        result["cryceleb"] = download_cryceleb(data_dir, ds["cryceleb"]["hf_repo"])
    if ds["babycry_ujm"]["enabled"]:
        result["babycry_ujm"] = download_babycry_ujm(data_dir)

    _log("summary: " + ", ".join(
        f"{k}={'OK' if v else 'MISSING'}" for k, v in result.items()
    ))
    return result
