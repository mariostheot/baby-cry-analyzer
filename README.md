# Γιατί Κλαίει; - Baby Cry Analyzer

Android εφαρμογή που ηχογραφεί το κλάμα του μωρού (Shazam-style, με το πάτημα ενός κουμπιού)
και εκτιμά **γιατί** κλαίει: πείνα, κούραση, δυσφορία, κοιλόπονος/αέρια ή ρέψιμο. Μαθαίνει
από τις δικές σου διορθώσεις και βελτιώνεται με τον καιρό - όλα **τοπικά στο κινητό**.

> Ενημερωτικό βοήθημα, ΟΧΙ ιατρική συσκευή. Για ανησυχίες υγείας, ρώτησε παιδίατρο.

## Τι κάνει

- Ηχογράφηση κατ' απαίτηση (χωρίς background), ζωντανό «μετρητή» έντασης.
- Ανίχνευση κλάματος (gate) + κατηγοριοποίηση αιτίας με AI μοντέλο.
- «Δεν είμαι σίγουρο» (OOD) όταν η βεβαιότητα είναι χαμηλή.
- Feedback: επιβεβαίωσε ή διόρθωσε την πρόβλεψη -> το μοντέλο προσαρμόζεται σε σένα.
- Ιστορικό/timeline με μοτίβα ανά ώρα και καταγραφή «τάισμα τώρα».
- Context prior: σταθμίζει το αποτέλεσμα με ώρες από το τελευταίο τάισμα και ώρα ημέρας.
- Στατιστικά ακρίβειας (accuracy, recall ανά κατηγορία και αμερόληπτο προσωπικό holdout).

## Πώς δουλεύει (αρχιτεκτονική)

```mermaid
flowchart TD
    mic[Μικρόφωνο 16kHz mono] --> yamnet[YAMNet TFLite: embedding 1024d + cry gate]
    yamnet -->|gate < κατώφλι| nocry[Χωρίς κλάμα]
    yamnet -->|embedding| head[Trained MLP head TFLite -> 5 πιθανότητες]
    head --> pers[Προσωποποίηση: prototypes + on-device fine-tune]
    pers --> ctx[Context prior: τάισμα / ώρα]
    ctx --> result[Αποτέλεσμα + top-3 + βεβαιότητα]
    result --> feedback[Feedback χρήστη]
    feedback --> store[(Room: feedback + ιστορικό)]
    store --> pers
```

Μοντέλο = **Supervised Deep Learning (transfer learning)**: το YAMNet (προεκπαιδευμένο σε
AudioSet) δίνει ένα 1024-d embedding, και ένα μικρό MLP «head» εκπαιδεύεται από εμάς πάνω
στα δημόσια datasets κλάματος. Το ίδιο YAMNet τρέχει και στο κινητό, ώστε τα χαρακτηριστικά
inference να ταιριάζουν ακριβώς με της εκπαίδευσης (parity test το επιβεβαιώνει).

## Δομή του project

- `ml-training/` - Python pipeline (τρέχει σε Google Colab) που παράγει τα μοντέλα TFLite.
  Δες [ml-training/README.md](ml-training/README.md).
- `app/` - το Android app (Kotlin + Jetpack Compose).

## Βήμα 1: Εκπαίδευση μοντέλου (Google Colab)

Δεν χρειάζεται Python/GPU στον υπολογιστή σου.

1. Ανέβασε το project σε (ιδιωτικό) GitHub repo.
2. Άνοιξε το [ml-training/train_baby_cry.ipynb](ml-training/train_baby_cry.ipynb) στο Colab.
3. Βάλε το `REPO_URL` και τρέξε όλα τα κελιά. (Προαιρετικά ανέβασε `kaggle.json` για
   περισσότερα δεδομένα.)
4. Κατέβασε το `artifacts/model_bundle.zip`.

Παράγει: `cry_reason.tflite`, `yamnet.tflite`, `labels.txt`, `cry_reason_trainable.tflite`,
`metadata.json`, `parity_sample.wav`, `parity_expected.json`, και γραφήματα αξιολόγησης
(confusion matrix, ROC/PR, calibration). Για credentials, επιλογές datasets και GPU, δες
αναλυτικά το [ml-training/README.md](ml-training/README.md).

## Βήμα 2: Τοποθέτηση μοντέλου

Δύο τρόποι:

- Α) Αντέγραψε `cry_reason.tflite`, `yamnet.tflite`, `labels.txt`,
  `cry_reason_trainable.tflite` και `metadata.json` στο
  [app/src/main/assets/](app/src/main/assets) και ξαναχτίσε.
- Β) (χωρίς rebuild) Αντέγραψε τα ίδια αρχεία με USB ή με τον file manager στο κινητό,
  στον φάκελο `Android/data/com.babycry.analyzer/files/models/`.

Για κάθε cloud build, ανέβασε το `model_bundle.zip` ως asset σε GitHub Release με tag
`model`. Το workflow το κατεβάζει και το ενσωματώνει στο APK. Αν λείπει το release, το APK
χτίζεται επίτηδες με heuristic fallback. Το CI αναπαράγει το pooled YAMNet που χρειάζεται το
Android και ελέγχει YAMNet embedding parity όταν το bundle περιέχει τα δύο parity αρχεία.

Αν δεν βρεθεί μοντέλο, το app τρέχει με πρόχειρη ευρετική (heuristic) εκτίμηση.

## Βήμα 3: Build του APK στο cloud (χωρίς Android Studio)

Πριν από το πρώτο build, δημιούργησε **μία φορά** σταθερό debug keystore και βάλε το
Base64 του ως repository Action secret `DEBUG_KEYSTORE_B64`. Αυτό είναι αναγκαίο ώστε κάθε
νέο APK να ενημερώνει το προηγούμενο χωρίς απεγκατάσταση και χωρίς απώλεια δεδομένων.

```powershell
New-Item -ItemType Directory -Force "$HOME\.android" | Out-Null
keytool -genkeypair -keystore "$HOME\.android\debug.keystore" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\.android\debug.keystore"))
```

Αντέγραψε ολόκληρη τη δεύτερη γραμμή στο GitHub: **Settings → Secrets and variables →
Actions → New repository secret**. Τα secrets εμφανίζονται κενά μετά την αποθήκευση από
σχεδιασμό· δεν διαγράφονται επειδή δεν εμφανίζεται η τιμή τους.

1. Κάνε push στο GitHub. Το GitHub Actions ([.github/workflows/build.yml](.github/workflows/build.yml))
   χτίζει αυτόματα.
2. Προτιμητέο: **Releases → latest → `app-debug.apk`** και κατέβασέ το κατευθείαν στο
   κινητό. Εναλλακτικά: Actions → τελευταίο run → Artifacts → `app-debug-apk` → unzip →
   `app-debug.apk`.
3. Μπορείς επίσης να πατήσεις **Run workflow** από Actions χωρίς νέο push.

## Βήμα 4: Εγκατάσταση στο κινητό

1. Ρυθμίσεις -> Ασφάλεια -> επίτρεψε «Άγνωστες πηγές»/«Εγκατάσταση αγνώστων εφαρμογών»
   για τον file manager ή τον browser.
2. Άνοιξε το `app-debug.apk` και πάτα «Εγκατάσταση».
3. Στην πρώτη ηχογράφηση, δώσε άδεια μικροφώνου.
4. Απαιτεί Android 8.0 ή νεότερο. Μην κάνεις απεγκατάσταση για κανονική ενημέρωση: το
   σταθερά υπογεγραμμένο APK έχει αυξανόμενο build number και κρατά ιστορικό/προφίλ/μάθηση.

## Πώς μετριέται η ακρίβεια

- Offline (Colab): αναφέρουμε **macro-F1** (όχι σκέτο accuracy, λόγω ανισορροπίας κλάσεων),
  **confusion matrix**, precision/recall/F1 ανά κατηγορία, balanced accuracy, Cohen's kappa,
  ROC/PR-AUC, top-2 accuracy και **calibration (ECE)**. Χρησιμοποιούμε
  StratifiedGroupKFold ώστε το ίδιο μωρό να μην εμφανίζεται σε train και test (αποφυγή
  «διαρροής»). Συγκρίνουμε και με baselines (majority, logistic regression, random forest).
- Στο κινητό (πραγματικές συνθήκες): η οθόνη «Στατιστικά» υπολογίζει running accuracy και
  recall ανά κατηγορία. Οι πρώτες 3 επιβεβαιώσεις ανά αιτία μένουν εκτός εκπαίδευσης και
  χρησιμοποιούνται ως σταθερό προσωπικό τεστ «χωρίς / με προσαρμογή».
  - Ένα «false positive» για μια κατηγορία = προβλέφθηκε αυτή αλλά ήταν άλλη.
  - Ένα «false negative» = ήταν αυτή η κατηγορία αλλά προβλέφθηκε άλλη.

## Πώς μαθαίνει από εσένα (προσωποποίηση, τοπικά)

- **Tier 1 (πάντα ενεργό):** για κάθε διόρθωση κρατά το embedding + τη σωστή ετικέτα και
  φτιάχνει «prototypes» ανά κατηγορία· μπλέντάρει μια similarity κατανομή με το μοντέλο.
  Δουλεύει από την 1η κιόλας διόρθωση.
- **Tier 2 (προαιρετικό):** όταν μαζευτούν αρκετά παραδείγματα και υπάρχει το
  `cry_reason_trainable.tflite`, κάνει fine-tune μόνο του τελευταίου γραμμικού επιπέδου
  στο κινητό και αποθηκεύει τα νέα βάρη. Με «Μηδενισμό προσωποποίησης» επανέρχεται.
- Τα holdout παραδείγματα δεν χρησιμοποιούνται ούτε στα prototypes ούτε στο fine-tuning,
  άρα δεν «διαρρέουν» στη μέτρηση βελτίωσης.

## Περιορισμοί / ειλικρίνεια

- Οι ετικέτες των δημόσιων datasets είναι δηλωμένες από γονείς (θορυβώδεις) και άνισες.
- Τα offline νούμερα είναι αισιόδοξα σε σχέση με το σπίτι σου (domain shift)· γι' αυτό
  υπάρχει το feedback loop ως συνεχής, πραγματική αξιολόγηση.
- Δεν είναι ιατρική συσκευή.

## Τεχνολογίες

Kotlin, Jetpack Compose, TensorFlow Lite (+ Flex/select-ops για το YAMNet), Room.
Python: TensorFlow/Keras, TF-Hub (YAMNet), librosa, scikit-learn.
