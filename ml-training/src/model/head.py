"""The trainable classifier head that sits on top of frozen YAMNet embeddings.

Small MLP: (1024) -> Normalization -> [Dense+BN+Dropout]* -> logits -> softmax.

The built-in Normalization layer is adapted on the training embeddings, so the exported
model is self-contained (no separate scaler needs to ship to the phone).
"""
from __future__ import annotations

from typing import Optional

import numpy as np


def build_head(
    input_dim: int = 1024,
    num_classes: int = 5,
    hidden: Optional[list[int]] = None,
    dropout: float = 0.3,
    l2: float = 1e-4,
    norm_mean: Optional[np.ndarray] = None,
    norm_variance: Optional[np.ndarray] = None,
):
    """Build and return the (uncompiled) softmax Keras model."""
    import tensorflow as tf

    hidden = hidden or [256, 128]
    reg = tf.keras.regularizers.l2(l2)

    inputs = tf.keras.Input(shape=(input_dim,), name="embedding")
    norm = tf.keras.layers.Normalization(name="norm")
    if norm_mean is not None and norm_variance is not None:
        norm.adapt(np.zeros((1, input_dim), dtype=np.float32))  # build the layer
        norm.set_weights([
            np.asarray(norm_mean, dtype=np.float32),
            np.asarray(norm_variance, dtype=np.float32),
            np.array(0, dtype=np.int64),
        ])
    x = norm(inputs)

    for i, units in enumerate(hidden):
        x = tf.keras.layers.Dense(units, kernel_regularizer=reg, name=f"dense_{i}")(x)
        x = tf.keras.layers.BatchNormalization(name=f"bn_{i}")(x)
        x = tf.keras.layers.Activation("relu", name=f"relu_{i}")(x)
        x = tf.keras.layers.Dropout(dropout, name=f"drop_{i}")(x)

    logits = tf.keras.layers.Dense(num_classes, name="logits")(x)
    probs = tf.keras.layers.Softmax(name="probs")(logits)
    return tf.keras.Model(inputs=inputs, outputs=probs, name="cry_reason_head")


def adapt_normalization(model, x_train: np.ndarray) -> None:
    """Adapt the model's Normalization layer to the training embeddings."""
    model.get_layer("norm").adapt(x_train)


def logit_model(model):
    """Return a model that outputs the pre-softmax logits (for calibration)."""
    import tensorflow as tf

    return tf.keras.Model(
        inputs=model.input, outputs=model.get_layer("logits").output
    )
