# Baby Cry AI - training pipeline

Supervised training of a baby-cry **reason** classifier (5 classes: hungry, tired,
discomfort, belly_pain, burping) using **transfer learning**:

```
audio -> YAMNet (frozen deep CNN) -> 1024-d embedding -> MLP head (trained) -> softmax
```

The same YAMNet runs on the phone, so on-device features match training exactly.

## Quickstart (Google Colab - recommended)

1. Push this whole project to a (private) GitHub repo.
2. Open `train_baby_cry.ipynb` in Colab (File -> Open notebook -> GitHub).
3. Set `REPO_URL` in cell 1, then Run all.
4. Download `artifacts/model_bundle.zip` at the end and unzip its files into
   `app/src/main/assets/` of the Android app.

No local Python needed. A GPU runtime is optional (CPU works).

## Quickstart (local)

```bash
cd ml-training
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python -m src.main --config configs/default.yaml
```

## Datasets

Enabled in `configs/default.yaml`. Free ones download automatically; the rest need
credentials (all optional - the pipeline runs on donateacry alone):

| Dataset | Access | Used for |
|---|---|---|
| donateacry-corpus | free (GitHub) | reason labels (5 classes) |
| Kaggle infant-cry (8-class) | `kaggle.json` | reason labels (more balanced) |
| InfantCry-DBL | Mendeley (may need manual download) | reason labels (DBL mapped) |
| ESC-50 | free | non-cry negatives (optional gate) |
| CryCeleb2023 | Hugging Face login | representation/gate (no reason labels) |
| BABYCRY-UJM-AXA | Zenodo (huge, manual) | optional pretraining/gate |

### Credentials
- **Kaggle:** create a token at Kaggle -> Settings -> *Create New Token*, then in Colab
  upload `kaggle.json` (cell 2a) or locally put it at `~/.kaggle/kaggle.json`.
- **Hugging Face:** accept the CryCeleb terms on its dataset page, then `huggingface-cli
  login` (or `login()` in the notebook).

## Outputs (`artifacts/`)

- `model_bundle/cry_reason.tflite` - trained classifier (int8 weights).
- `model_bundle/yamnet.tflite` - feature extractor + gate (built with SELECT_TF_OPS).
- `model_bundle/cry_reason_trainable.tflite` - optional on-device training model (Tier 2).
- `model_bundle/labels.txt` - class order (uppercase, matches `CryReason` enum).
- `metadata.json`, `metrics.json`, `report.txt`, `confusion_matrix.png`, `roc_pr.png`,
  `calibration.png` - evaluation.
- `model_bundle/parity_sample.wav` + `parity_expected.json` - for the Android parity test.

## How the model gets into the app

Two options (see the app README):
1. Copy `cry_reason.tflite`, `yamnet.tflite`, `labels.txt` into `app/src/main/assets/`
   and rebuild.
2. Or copy the `.tflite` files onto the phone (the app can load them from its external
   files dir), so you do not need to rebuild after retraining.

## Honest limitations

- Reason labels are parent-reported (noisy) and imbalanced; report **macro-F1** and the
  **confusion matrix**, not raw accuracy.
- The large datasets (CryCeleb/BABYCRY) have no reason labels - they help representation
  and the cry gate, not the 5-class accuracy directly.
- Offline CV numbers are optimistic vs your baby at home (domain shift). The app's own
  feedback loop is the real, ongoing evaluation. Not a medical device.
