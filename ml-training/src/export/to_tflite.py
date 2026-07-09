"""Exports everything the Android app needs into `artifacts/model_bundle/`:

  - yamnet.tflite               frozen YAMNet feature extractor + gate (Flex ops)
  - cry_reason.tflite           the trained reason classifier (float in -> softmax out)
  - cry_reason_trainable.tflite optional on-device training model (Tier 2)
  - labels.txt                  class order (uppercase enum names)
  - metadata.json               copied training metadata

YAMNet contains an STFT/mel frontend that needs a few TensorFlow ops not available as
TFLite builtins, so it is converted with SELECT_TF_OPS. The Android app therefore
depends on `tensorflow-lite-select-tf-ops` (the Flex runtime).
"""
from __future__ import annotations

import json
import shutil
from pathlib import Path

import numpy as np


def convert_head(model, out_path: Path, quantize: bool) -> None:
    import tensorflow as tf

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    if quantize:
        conv.optimizations = [tf.lite.Optimize.DEFAULT]  # int8 dynamic-range weights
    out_path.write_bytes(conv.convert())
    print(f"[export] head -> {out_path} ({out_path.stat().st_size // 1024} KB)")


def convert_yamnet(
    handle: str,
    out_path: Path,
    gate_class_name: str = "Baby cry, infant cry",
) -> None:
    """Export YAMNet as a self-contained feature extractor with STATIC output shapes.

    The raw TF-Hub model returns per-frame tensors whose leading (frame) dimension is
    dynamic. On Android that dynamic output does NOT propagate through the Flex ops, so
    reading it fails at runtime ("cannot fill a Java array ... Tensor of 0 bytes"). We wrap
    YAMNet so it mean-pools the frames INTERNALLY and returns a fixed 1024-d embedding plus
    a scalar cry gate - i.e. static shapes that TFLite reads reliably on device. The math is
    identical to the Python training path (same YAMNet, same mean-pool), so the trained head
    needs no changes.
    """
    import csv

    import tensorflow as tf
    import tensorflow_hub as hub

    yamnet = hub.load(handle)

    # Resolve the AudioSet index of the cry class from the model's own class map so the
    # on-device gate matches what training used.
    gate_index = 20  # standard YAMNet index for "Baby cry, infant cry"
    try:
        class_map_path = yamnet.class_map_path().numpy().decode("utf-8")
        with open(class_map_path) as f:
            for row in csv.DictReader(f):
                if row["display_name"].strip() == gate_class_name:
                    gate_index = int(row["index"])
                    break
    except Exception as exc:  # noqa: BLE001
        print(f"[export] yamnet gate-index lookup failed ({exc}); using {gate_index}")

    class PooledYamnet(tf.Module):
        def __init__(self, model, idx: int):
            super().__init__()
            self.model = model
            self.idx = idx

        @tf.function(input_signature=[
            tf.TensorSpec([None], tf.float32, name="waveform"),
        ])
        def __call__(self, waveform):
            scores, embeddings, _ = self.model(waveform)
            embedding = tf.reduce_mean(embeddings, axis=0)   # [1024] static
            gate = tf.reduce_max(scores[:, self.idx])        # scalar
            return {"embedding": embedding, "gate": tf.reshape(gate, [1])}

    module = PooledYamnet(yamnet, gate_index)
    concrete = module.__call__.get_concrete_function()
    conv = tf.lite.TFLiteConverter.from_concrete_functions([concrete], module)
    conv.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    out_path.write_bytes(conv.convert())
    print(f"[export] yamnet (pooled, gate_index={gate_index}) -> {out_path} "
          f"({out_path.stat().st_size // 1024} KB)")


class _TrainableHead:
    """Builds a tf.Module exposing train/infer/get_weights/set_weights signatures.

    Only the final linear ("logits") layer is trainable on-device; everything before it
    (normalization + hidden layers + batchnorm) is frozen. Fine-tuning just the linear
    head on frozen features is stable even with the handful of corrections a parent
    provides, and avoids catastrophic forgetting.
    """

    def __init__(self, model, input_dim: int, num_classes: int, lr: float):
        import tensorflow as tf

        self.model = model
        self.input_dim = input_dim
        self.num_classes = num_classes
        self.opt = tf.keras.optimizers.SGD(lr)
        self.logits_layer = model.get_layer("logits")
        self.trainable_vars = self.logits_layer.trainable_variables

    def build_module(self):
        import tensorflow as tf

        model = self.model
        trainable_vars = self.trainable_vars
        opt = self.opt
        input_dim = self.input_dim

        module = tf.Module()

        @tf.function(input_signature=[
            tf.TensorSpec([None, input_dim], tf.float32),
            tf.TensorSpec([None], tf.int32),
        ])
        def train(x, y):
            with tf.GradientTape() as tape:
                probs = model(x, training=False)
                loss = tf.reduce_mean(
                    tf.keras.losses.sparse_categorical_crossentropy(y, probs)
                )
            grads = tape.gradient(loss, trainable_vars)
            opt.apply_gradients(zip(grads, trainable_vars))
            return {"loss": tf.reshape(loss, [1])}  # [1] is easier to read from Kotlin

        @tf.function(input_signature=[tf.TensorSpec([None, input_dim], tf.float32)])
        def infer(x):
            return {"probs": model(x, training=False)}

        @tf.function(input_signature=[])
        def get_weights():
            return {"kernel": trainable_vars[0], "bias": trainable_vars[1]}

        @tf.function(input_signature=[
            tf.TensorSpec(trainable_vars[0].shape, tf.float32),
            tf.TensorSpec(trainable_vars[1].shape, tf.float32),
        ])
        def set_weights(kernel, bias):
            trainable_vars[0].assign(kernel)
            trainable_vars[1].assign(bias)
            return {"ok": tf.constant([1.0], dtype=tf.float32)}

        module.train = train
        module.infer = infer
        module.get_weights = get_weights
        module.set_weights = set_weights
        module._model = model  # keep references so all variables are tracked/saved
        module._opt = opt
        return module


def export_trainable(model, out_path: Path, config: dict) -> None:
    import tensorflow as tf

    meta = json.loads((Path(config["paths"]["artifacts_dir"]) / "metadata.json").read_text())
    helper = _TrainableHead(
        model, meta["input_dim"], meta["num_classes"], lr=0.01
    )
    module = helper.build_module()
    tmp_dir = out_path.parent / "_trainable_saved_model"
    tf.saved_model.save(
        module, str(tmp_dir),
        signatures={
            "train": module.train,
            "infer": module.infer,
            "get_weights": module.get_weights,
            "set_weights": module.set_weights,
        },
    )
    conv = tf.lite.TFLiteConverter.from_saved_model(str(tmp_dir))
    conv.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    conv.experimental_enable_resource_variables = True
    try:
        out_path.write_bytes(conv.convert())
        print(f"[export] trainable head -> {out_path}")
    except Exception as exc:  # noqa: BLE001
        print(f"[export] trainable head export skipped ({exc}). "
              "Tier 1 (prototype) personalization still works.")
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


def write_labels(classes: list[str], out_path: Path) -> None:
    out_path.write_text("\n".join(c.upper() for c in classes) + "\n")
    print(f"[export] labels -> {out_path}")


def export_all(result: dict, config: dict) -> Path:
    artifacts = Path(config["paths"]["artifacts_dir"])
    bundle = artifacts / "model_bundle"
    bundle.mkdir(parents=True, exist_ok=True)

    convert_head(result["model"], bundle / "cry_reason.tflite",
                 quantize=config["export"]["quantize"])
    write_labels(result["classes"], bundle / "labels.txt")

    try:
        convert_yamnet(
            config["features"]["yamnet_handle"],
            bundle / "yamnet.tflite",
            gate_class_name=config["gate"]["infant_cry_class_name"],
        )
    except Exception as exc:  # noqa: BLE001
        print(f"[export] YAMNet conversion failed ({exc}). "
              "You can instead ship a prebuilt yamnet.tflite (see README).")

    if config["export"].get("ondevice_training"):
        export_trainable(result["model"], bundle / "cry_reason_trainable.tflite", config)

    meta_src = artifacts / "metadata.json"
    if meta_src.exists():
        shutil.copy(meta_src, bundle / "metadata.json")

    # Zip for easy download from Colab.
    zip_path = artifacts / "model_bundle.zip"
    shutil.make_archive(str(zip_path.with_suffix("")), "zip", bundle)
    print(f"[export] bundle ready: {bundle}  (zipped: {zip_path})")
    return bundle
