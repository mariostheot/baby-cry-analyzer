"""Supervised training of the cry-reason head with grouped cross-validation.

Pipeline:
  1. Embed every clip with YAMNet (cached).
  2. StratifiedGroupKFold CV -> out-of-fold (OOF) predictions for honest metrics
     (no baby appears in both train and test).
  3. Fit a temperature on the OOF logits so the on-device confidence is calibrated.
  4. Retrain a final model on all data (+ augmentation) for export.

Augmentation is applied to the TRAIN split only (never to validation), by loading each
clip, perturbing the waveform, and re-embedding with YAMNet.
"""
from __future__ import annotations

import datetime as _dt
import json
import os
import random
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd

from ..augment import Augmenter
from ..features.yamnet_embed import YamnetEmbedder
from .head import adapt_normalization, build_head, logit_model


def set_seeds(seed: int) -> None:
    import tensorflow as tf

    os.environ["PYTHONHASHSEED"] = str(seed)
    random.seed(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)


def _class_weights(y: np.ndarray, num_classes: int) -> dict[int, float]:
    from sklearn.utils.class_weight import compute_class_weight

    present = np.unique(y)
    weights = compute_class_weight("balanced", classes=present, y=y)
    result = {c: 1.0 for c in range(num_classes)}
    for c, w in zip(present, weights):
        result[int(c)] = float(w)
    return result


def _augmented_set(
    embedder: YamnetEmbedder,
    augmenter: Augmenter,
    paths: list[str],
    y: np.ndarray,
    target_per_class: int,
    input_dim: int,
) -> tuple[np.ndarray, np.ndarray]:
    plan = Augmenter.plan_oversampling(y, target_per_class)
    xs: list[np.ndarray] = []
    ys: list[int] = []
    for path, label in zip(paths, y):
        copies = plan.get(int(label), 0)
        for _ in range(copies):
            try:
                wave = augmenter.augment(embedder.load_audio(path))
                emb, _ = embedder.embed_waveform(wave)
            except Exception:  # noqa: BLE001
                continue
            xs.append(emb)
            ys.append(int(label))
    if not xs:
        return np.zeros((0, input_dim), dtype=np.float32), np.zeros((0,), dtype=np.int64)
    return np.vstack(xs).astype(np.float32), np.asarray(ys, dtype=np.int64)


def _train_model(x_tr, y_tr, x_val, y_val, config: dict, num_classes: int, input_dim: int):
    import tensorflow as tf

    t = config["train"]
    model = build_head(
        input_dim=input_dim,
        num_classes=num_classes,
        hidden=t["hidden"],
        dropout=t["dropout"],
        l2=t["l2"],
    )
    adapt_normalization(model, x_tr)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(t["lr"]),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_loss", patience=t["early_stopping_patience"],
            restore_best_weights=True,
        )
    ]
    model.fit(
        x_tr, y_tr,
        validation_data=(x_val, y_val),
        epochs=t["epochs"],
        batch_size=t["batch_size"],
        class_weight=_class_weights(y_tr, num_classes),
        callbacks=callbacks,
        verbose=0,
    )
    return model


def fit_temperature(logits: np.ndarray, y: np.ndarray) -> float:
    """Temperature scaling: find T>0 minimizing NLL on held-out (OOF) logits."""
    from scipy.optimize import minimize_scalar

    def nll(temp: float) -> float:
        z = logits / max(temp, 1e-3)
        z = z - z.max(axis=1, keepdims=True)
        log_sum = np.log(np.exp(z).sum(axis=1))
        ll = z[np.arange(len(y)), y] - log_sum
        return float(-np.mean(ll))

    res = minimize_scalar(nll, bounds=(0.05, 10.0), method="bounded")
    return float(res.x)


def train(config: dict, embedder: Optional[YamnetEmbedder] = None) -> dict:
    from sklearn.metrics import f1_score
    from sklearn.model_selection import StratifiedGroupKFold

    set_seeds(config["seed"])
    classes: list[str] = config["classes"]
    num_classes = len(classes)
    artifacts = Path(config["paths"]["artifacts_dir"])
    artifacts.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(artifacts / "manifest.csv")
    df["label"] = pd.Categorical(df["label"], categories=classes, ordered=True)

    if embedder is None:
        f = config["features"]
        embedder = YamnetEmbedder(
            handle=f["yamnet_handle"],
            sample_rate=config["sample_rate"],
            min_seconds=f["min_seconds"],
            max_seconds=f["max_seconds"],
            cache_dir=config["paths"]["cache_dir"],
            pooling=f["pooling"],
            gate_class_name=config["gate"]["infant_cry_class_name"],
        )

    X, _gates, kept = embedder.embed_paths(df["path"].tolist())
    df = df.iloc[kept].reset_index(drop=True)
    y = df["label"].cat.codes.to_numpy()
    groups = df["group_id"].to_numpy()
    paths = df["path"].tolist()
    input_dim = int(X.shape[1])

    augmenter = Augmenter(config, config["sample_rate"], seed=config["seed"])
    target = config["augment"]["target_per_class"] if config["augment"]["enabled"] else 0

    # ---- Cross-validation for honest, leakage-free metrics --------------------
    n_splits = min(config["train"]["cv_folds"], _max_safe_splits(y, groups))
    oof_prob = np.full((len(y), num_classes), np.nan, dtype=np.float32)
    oof_logits = np.zeros((len(y), num_classes), dtype=np.float32)
    fold_f1: list[float] = []

    if n_splits >= 2:
        sgkf = StratifiedGroupKFold(n_splits=n_splits, shuffle=True,
                                    random_state=config["seed"])
        for fold, (tr, va) in enumerate(sgkf.split(X, y, groups)):
            x_tr, y_tr = X[tr], y[tr]
            if target:
                x_aug, y_aug = _augmented_set(
                    embedder, augmenter, [paths[i] for i in tr], y_tr, target, input_dim
                )
                if len(x_aug):
                    x_tr = np.vstack([x_tr, x_aug])
                    y_tr = np.concatenate([y_tr, y_aug])
            model = _train_model(x_tr, y_tr, X[va], y[va], config, num_classes, input_dim)
            oof_prob[va] = model.predict(X[va], verbose=0)
            oof_logits[va] = logit_model(model).predict(X[va], verbose=0)
            f1 = f1_score(y[va], oof_prob[va].argmax(1), average="macro")
            fold_f1.append(float(f1))
            print(f"[train] fold {fold + 1}/{n_splits} macro-F1={f1:.3f}")
    else:
        print("[train] not enough groups for CV; skipping OOF metrics")

    valid = ~np.isnan(oof_prob).any(axis=1)
    metrics = _summarize(y[valid], oof_prob[valid], classes) if valid.any() else {}
    temperature = (
        fit_temperature(oof_logits[valid], y[valid])
        if valid.any() and config["train"]["calibrate_temperature"] else 1.0
    )
    metrics["cv_macro_f1_mean"] = float(np.mean(fold_f1)) if fold_f1 else None
    metrics["cv_macro_f1_std"] = float(np.std(fold_f1)) if fold_f1 else None
    metrics["temperature"] = temperature

    # ---- Final model on all data (with augmentation) --------------------------
    x_all, y_all = X, y
    if target:
        x_aug, y_aug = _augmented_set(embedder, augmenter, paths, y_all, target, input_dim)
        if len(x_aug):
            x_all = np.vstack([X, x_aug])
            y_all = np.concatenate([y, y_aug])
    # Small internal split just for early stopping of the final model.
    from sklearn.model_selection import train_test_split
    x_fit, x_es, y_fit, y_es = train_test_split(
        x_all, y_all, test_size=0.1, random_state=config["seed"], stratify=y_all
    )
    final = _train_model(x_fit, y_fit, x_es, y_es, config, num_classes, input_dim)

    # ---- Persist artifacts ----------------------------------------------------
    np.savez(artifacts / "oof.npz", y_true=y[valid], probs=oof_prob[valid],
             logits=oof_logits[valid])
    final.save(artifacts / "cry_reason_head.keras")

    metadata = {
        "classes": classes,
        "enum_labels": [c.upper() for c in classes],
        "input_dim": input_dim,
        "num_classes": num_classes,
        "yamnet_handle": config["features"]["yamnet_handle"],
        "sample_rate": config["sample_rate"],
        "pooling": config["features"]["pooling"],
        "gate_threshold": config["gate"]["threshold"],
        "gate_class_name": config["gate"]["infant_cry_class_name"],
        "seed": config["seed"],
        "trained_at": _dt.datetime.utcnow().isoformat() + "Z",
        "n_clips": int(len(y)),
        "class_counts": {c: int((y == i).sum()) for i, c in enumerate(classes)},
        "metrics": metrics,
    }
    (artifacts / "metadata.json").write_text(json.dumps(metadata, indent=2))
    print(f"[train] macro-F1 (OOF) = {metrics.get('macro_f1')}")
    print(f"[train] saved model + metadata to {artifacts}")

    return {
        "model": final,
        "temperature": temperature,
        "metadata": metadata,
        "classes": classes,
        "input_dim": input_dim,
        "embedder": embedder,
        "X": X,
        "y": y,
        "groups": groups,
    }


def _max_safe_splits(y: np.ndarray, groups: np.ndarray) -> int:
    """Upper bound on folds given group count and the rarest class's group count."""
    n_groups = len(np.unique(groups))
    rarest_groups = min(
        len(np.unique(groups[y == c])) for c in np.unique(y)
    )
    return max(1, min(n_groups, rarest_groups))


def _summarize(y_true: np.ndarray, probs: np.ndarray, classes: list[str]) -> dict:
    from sklearn.metrics import (
        accuracy_score,
        balanced_accuracy_score,
        cohen_kappa_score,
        classification_report,
        f1_score,
    )

    y_pred = probs.argmax(1)
    top2 = np.argsort(probs, axis=1)[:, -2:]
    top2_acc = float(np.mean([yt in row for yt, row in zip(y_true, top2)]))
    return {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, y_pred)),
        "macro_f1": float(f1_score(y_true, y_pred, average="macro")),
        "weighted_f1": float(f1_score(y_true, y_pred, average="weighted")),
        "cohen_kappa": float(cohen_kappa_score(y_true, y_pred)),
        "top2_accuracy": top2_acc,
        "report": classification_report(
            y_true, y_pred, labels=list(range(len(classes))),
            target_names=classes, output_dict=True, zero_division=0,
        ),
    }
