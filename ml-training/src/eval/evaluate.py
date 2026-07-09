"""Turns the out-of-fold (OOF) predictions into human-readable metrics and plots.

Outputs (written to artifacts/):
  - report.txt            classification report + summary
  - confusion_matrix.png  normalized confusion matrix
  - roc_pr.png            one-vs-rest ROC and Precision-Recall curves
  - calibration.png       reliability diagram + ECE (after temperature scaling)
  - metrics.json          all numeric metrics (incl. optional baselines)
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

import numpy as np

import matplotlib
matplotlib.use("Agg")  # headless (Colab/CI safe)
import matplotlib.pyplot as plt  # noqa: E402


def _softmax(z: np.ndarray) -> np.ndarray:
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def expected_calibration_error(conf: np.ndarray, correct: np.ndarray, bins: int = 10):
    edges = np.linspace(0.0, 1.0, bins + 1)
    ece = 0.0
    xs, ys = [], []
    for i in range(bins):
        mask = (conf > edges[i]) & (conf <= edges[i + 1])
        if not mask.any():
            continue
        acc = float(correct[mask].mean())
        avg_conf = float(conf[mask].mean())
        ece += (mask.mean()) * abs(acc - avg_conf)
        xs.append(avg_conf)
        ys.append(acc)
    return float(ece), np.array(xs), np.array(ys)


def _plot_confusion(y_true, y_pred, classes, out: Path) -> None:
    from sklearn.metrics import confusion_matrix

    cm = confusion_matrix(y_true, y_pred, labels=range(len(classes)))
    cm_norm = cm / np.clip(cm.sum(axis=1, keepdims=True), 1, None)
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(cm_norm, cmap="Blues", vmin=0, vmax=1)
    ax.set_xticks(range(len(classes)))
    ax.set_yticks(range(len(classes)))
    ax.set_xticklabels(classes, rotation=45, ha="right")
    ax.set_yticklabels(classes)
    ax.set_xlabel("Predicted")
    ax.set_ylabel("True")
    ax.set_title("Confusion matrix (row-normalized)")
    for i in range(len(classes)):
        for j in range(len(classes)):
            ax.text(j, i, f"{cm_norm[i, j]:.2f}", ha="center", va="center",
                    color="black" if cm_norm[i, j] < 0.6 else "white", fontsize=8)
    fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


def _plot_roc_pr(y_true, probs, classes, out: Path) -> dict:
    from sklearn.metrics import auc, precision_recall_curve, roc_curve

    y_oh = np.eye(len(classes))[y_true]
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4.5))
    roc_auc, pr_auc = {}, {}
    for c, name in enumerate(classes):
        if y_oh[:, c].sum() == 0:
            continue
        fpr, tpr, _ = roc_curve(y_oh[:, c], probs[:, c])
        pr, rc, _ = precision_recall_curve(y_oh[:, c], probs[:, c])
        roc_auc[name] = float(auc(fpr, tpr))
        pr_auc[name] = float(auc(rc, pr))
        ax1.plot(fpr, tpr, label=f"{name} ({roc_auc[name]:.2f})")
        ax2.plot(rc, pr, label=f"{name} ({pr_auc[name]:.2f})")
    ax1.plot([0, 1], [0, 1], "k--", lw=1)
    ax1.set_title("ROC (one-vs-rest)")
    ax1.set_xlabel("FPR")
    ax1.set_ylabel("TPR")
    ax1.legend(fontsize=8)
    ax2.set_title("Precision-Recall (one-vs-rest)")
    ax2.set_xlabel("Recall")
    ax2.set_ylabel("Precision")
    ax2.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)
    return {"roc_auc": roc_auc, "pr_auc": pr_auc}


def _plot_calibration(conf, correct, ece, out: Path) -> None:
    _, xs, ys = expected_calibration_error(conf, correct)
    fig, ax = plt.subplots(figsize=(5, 5))
    ax.plot([0, 1], [0, 1], "k--", lw=1, label="perfect")
    ax.plot(xs, ys, "o-", label=f"model (ECE={ece:.3f})")
    ax.set_xlabel("Confidence")
    ax.set_ylabel("Accuracy")
    ax.set_title("Reliability diagram")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


def compute_baselines(X, y, groups, num_classes, seed) -> dict:
    """Majority + Logistic Regression + Random Forest baselines (grouped CV)."""
    from sklearn.dummy import DummyClassifier
    from sklearn.ensemble import RandomForestClassifier
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import f1_score
    from sklearn.model_selection import StratifiedGroupKFold

    n_splits = min(5, len(np.unique(groups)),
                   min(len(np.unique(groups[y == c])) for c in np.unique(y)))
    if n_splits < 2:
        return {}
    sgkf = StratifiedGroupKFold(n_splits=n_splits, shuffle=True, random_state=seed)
    models = {
        "majority": DummyClassifier(strategy="most_frequent"),
        "logreg": LogisticRegression(max_iter=1000, class_weight="balanced"),
        "random_forest": RandomForestClassifier(
            n_estimators=300, class_weight="balanced", random_state=seed
        ),
    }
    out: dict[str, float] = {}
    for name, clf in models.items():
        scores = []
        for tr, va in sgkf.split(X, y, groups):
            clf.fit(X[tr], y[tr])
            scores.append(f1_score(y[va], clf.predict(X[va]), average="macro"))
        out[name] = float(np.mean(scores))
    return out


def run(config: dict, X: Optional[np.ndarray] = None, y: Optional[np.ndarray] = None,
        groups: Optional[np.ndarray] = None) -> dict:
    from sklearn.metrics import classification_report

    artifacts = Path(config["paths"]["artifacts_dir"])
    classes = config["classes"]
    meta = json.loads((artifacts / "metadata.json").read_text())
    temperature = meta.get("metrics", {}).get("temperature", 1.0)

    data = np.load(artifacts / "oof.npz")
    y_true, probs, logits = data["y_true"], data["probs"], data["logits"]
    y_pred = probs.argmax(1)

    _plot_confusion(y_true, y_pred, classes, artifacts / "confusion_matrix.png")
    auc_info = _plot_roc_pr(y_true, probs, classes, artifacts / "roc_pr.png")

    cal_probs = _softmax(logits / max(temperature, 1e-3))
    conf = cal_probs.max(axis=1)
    correct = (cal_probs.argmax(1) == y_true).astype(np.float32)
    ece, _, _ = expected_calibration_error(conf, correct)
    _plot_calibration(conf, correct, ece, artifacts / "calibration.png")

    report_txt = classification_report(
        y_true, y_pred, labels=list(range(len(classes))),
        target_names=classes, zero_division=0,
    )
    metrics = dict(meta.get("metrics", {}))
    metrics.update({"ece": ece, **auc_info})
    if X is not None and y is not None and groups is not None:
        metrics["baselines"] = compute_baselines(
            X, y, groups, len(classes), config["seed"]
        )

    (artifacts / "report.txt").write_text(
        f"Baby Cry AI - evaluation\n\n{report_txt}\n\n"
        f"macro-F1: {metrics.get('macro_f1')}\n"
        f"balanced accuracy: {metrics.get('balanced_accuracy')}\n"
        f"top-2 accuracy: {metrics.get('top2_accuracy')}\n"
        f"ECE (calibrated): {ece:.3f}\n"
        f"baselines: {metrics.get('baselines')}\n"
    )
    (artifacts / "metrics.json").write_text(json.dumps(metrics, indent=2, default=float))
    print("[eval] wrote report.txt, confusion_matrix.png, roc_pr.png, calibration.png")
    print(report_txt)
    return metrics
