package com.babycry.analyzer.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLang(val code: String) { EL("el"), EN("en") }

val LocalAppLang = staticCompositionLocalOf { AppLang.EL }

/** Cached copy so non-Compose code (ViewModel, notifications, repository) can translate too. */
@Volatile
var currentAppLang: AppLang = AppLang.EL

@Composable
@ReadOnlyComposable
fun tr(el: String): String = translate(LocalAppLang.current, el)

/** Non-Compose translation (uses the cached [currentAppLang]). */
fun trS(el: String): String = translate(currentAppLang, el)

fun translate(lang: AppLang, el: String): String =
    if (lang == AppLang.EN) EN[el] ?: el else el

private val EN: Map<String, String> = mapOf(
    // ---- Navigation & app shell ----
    "Αρχική" to "Home",
    "Ιστορικό" to "History",
    "Ηχογραφήσεις" to "Recordings",
    "Στατιστικά" to "Stats",
    "Ρυθμίσεις" to "Settings",
    "Σχετικά" to "About",
    "Αναφορά" to "Report",
    "Ηρέμησε το μωρό" to "Soothe the baby",
    "Πότε να ανησυχήσεις" to "When to worry",
    "Revekka" to "Revekka",
    "Πίσω" to "Back",
    "Μενού" to "Menu",
    "Χρειάζεται άδεια μικροφώνου για την ηχογράφηση." to "Microphone permission is required for recording.",
    "Το backup αποθηκεύτηκε." to "Backup saved.",
    "Δεν υπάρχουν ακόμη επιβεβαιωμένες ηχογραφήσεις για εξαγωγή." to "No confirmed recordings available for export yet.",
    "Κοινοποίηση αποτελέσματος" to "Share result",
    "Δεν υπάρχει αποτέλεσμα για κοινοποίηση." to "Nothing to share.",

    // ---- Language selector ----
    "Γλώσσα" to "Language",
    "Ελληνικά" to "Ελληνικά",

    // ---- Home screen ----
    "Άκου το μωρό" to "Listen to baby",
    "AI μοντέλο ενεργό" to "AI model active",
    "Πρόχειρη εκτίμηση (χωρίς μοντέλο)" to "Rough estimate (no model)",
    "Τάισμα" to "Feeding",
    "Ηρέμησέ το" to "Soothe",
    "Πότε να ανησυχήσω;" to "When should I worry?",
    "Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή. Αν ανησυχείς για την υγεία του μωρού, ρώτησε παιδίατρο." to
        "Informational aid, not medical advice. If you're worried about your baby's health, ask a pediatrician.",

    // ---- Diaper changes ----
    "Αλλαγή πάνας" to "Diaper change",
    "Τι είχε η πάνα; Βοηθά να βλέπεις μοτίβα (π.χ. πόσο συχνά κάνει κακά)." to
        "What was in the diaper? Helps you spot patterns (e.g. how often baby poops).",
    "Βρεγμένη (πιπί)" to "Wet (pee)",
    "Κακά" to "Poop",
    "Βρεγμένη + κακά" to "Wet + poop",
    "Πάνα" to "Diaper",
    "Αλλαγές πάνας" to "Diaper changes",
    "Πάνες ανά ημέρα (τελευταίες 7)" to "Diapers per day (last 7)",
    "Καταγράφηκε η αλλαγή πάνας." to "Diaper change logged.",
    // Report
    "Πάνες" to "Diapers",
    "Σύνολο αλλαγών" to "Total changes",
    "Μέσος όρος αλλαγών/ημέρα" to "Avg changes/day",
    "Κακά (σύνολο)" to "Poops (total)",
    "Κακά ανά ημέρα (μ.ο.)" to "Poops per day (avg)",
    "Πάνες ανά ημέρα (τελευταίες 14)" to "Diapers per day (last 14)",
    // Settings: pediatrician-confirmed colic/gas (per-baby prior)
    "Επιβεβαιωμένοι κολικοί/αέρια από γιατρό" to "Doctor-confirmed colic/gas",
    "Αν ο παιδίατρος έχει επιβεβαιώσει κολικούς ή αέρια στο μωρό, δίνουμε μεγαλύτερο βάρος στο κοιλόπονο/ρέψιμο — ιδίως ανάμεσα στα γεύματα. Ισχύει για το ενεργό μωρό." to
        "If a pediatrician has confirmed colic or gas for your baby, we give more weight to belly-pain/burping — especially between feeds. Applies to the active baby.",
    "Προαιρετικό — μόνο αν το έχει πει ο παιδίατρος. Δίνει μεγαλύτερο βάρος στο κοιλόπονο/αέρια. Αλλάζει όποτε θες από τις Ρυθμίσεις." to
        "Optional — only if your pediatrician has said so. Gives more weight to belly-pain/gas. You can change it anytime in Settings.",

    "Ηχογράφηση" to "Record",
    "Ακούω το μωρό..." to "Listening to baby...",
    "Κράτα το κινητό κοντά του" to "Hold the phone close to baby",
    "Συγκεντρώνω τον ήχο..." to "Focusing on the sound...",
    "Σχεδόν εκεί..." to "Almost there...",
    "Θα σταματήσω μόνο μου μόλις καταλάβω" to "I'll stop on my own once I understand",
    "Άκυρο" to "Cancel",
    "Εντάξει" to "OK",
    "Αναλύω το κλάμα..." to "Analyzing the cry...",
    "Πάτα για να ακούσω το μωρό" to "Tap to listen to baby",
    "Αποτέλεσμα" to "Result",
    "Δεν άκουσα καθαρό κλάμα" to "Didn't hear a clear cry",
    "Άκουσα για λίγα δευτερόλεπτα αλλά δεν ξεχώρισα κλάμα. Πάτα ξανά το μικρόφωνο και κράτα το κινητό πιο κοντά στο μωρό ή δοκίμασε σε πιο ήσυχο χώρο." to
        "I listened for a few seconds but couldn't pick out a cry. Tap the mic again and hold the phone closer to baby, or try a quieter room.",
    "Βεβαιότητα" to "Confidence",
    "Πρόχειρη εκτίμηση - δεν υπάρχει εκπαιδευμένο μοντέλο." to "Rough estimate — no trained model available.",
    "Δεν είμαι σίγουρο - δες και τις άλλες πιθανές αιτίες." to "Not sure — check the other possible causes too.",
    "Άκου ξανά" to "Listen again",
    "Παύση" to "Pause",
    "Συνέχεια" to "Resume",
    "Κοινοποίηση" to "Share",
    "Πιθανές αιτίες" to "Possible causes",
    "Ευχαριστώ! Θα μάθω από αυτό." to "Thanks! I'll learn from this.",
    "Ξέρεις ήδη γιατί έκλαψε;" to "Already know why baby cried?",
    "Αν όχι, μην ανησυχείς — θα σε ρωτήσουμε σε λίγα λεπτά, μόλις καταλάβεις (π.χ. αφού το ταΐσεις κι ηρεμήσει)." to
        "If not, don't worry — we'll ask you in a few minutes once you figure it out (e.g. after feeding and calming).",
    "Ναι, η εκτίμηση ήταν σωστή" to "Yes, the estimate was correct",
    "...ή διάλεξε τη σωστή αιτία:" to "...or pick the correct cause:",
    "Δεν ξέρω ακόμη" to "Don't know yet",
    "Τώρα που ξέρεις την αιτία, διάλεξέ την:" to "Now that you know the cause, pick it:",
    "Η αρχική εκτίμηση:" to "Initial estimate:",
    "Έκλαψε" to "Cried",

    // ---- Cry reasons (displayName) ----
    "Πείνα" to "Hunger",
    "Κούραση / Υπνηλία" to "Tired / Sleepy",
    "Δυσφορία" to "Discomfort",
    "Κοιλόπονος / Αέρια" to "Belly pain / Gas",
    "Ρέψιμο (θέλει να βγάλει αέρα)" to "Burping (needs to burp)",

    // ---- Cry reasons (advice) ----
    "Δοκίμασε τάισμα. Ψάξε για σημάδια πείνας: πιπίλισμα, στρέψιμο του κεφαλιού, γλείψιμο χειλιών." to
        "Try feeding. Look for hunger cues: sucking, head turning, lip licking.",
    "Μπορεί να θέλει ύπνο. Χαμήλωσε τα ερεθίσματα, νανούρισμα, ήρεμο περιβάλλον." to
        "Baby may need sleep. Lower stimulation, soothing, calm environment.",
    "Έλεγξε πάνα, ρούχα, θερμοκρασία ή στάση. Κάτι μπορεί να το ενοχλεί." to
        "Check diaper, clothes, temperature, or position. Something may be bothering baby.",
    "Πιθανά αέρια ή κολικός. Δοκίμασε απαλό μασάζ στην κοιλιά ή ποδηλατάκι με τα πόδια." to
        "Possible gas or colic. Try gentle belly massage or bicycle legs.",
    "Κράτησέ το όρθιο στον ώμο σου και χτύπα απαλά την πλάτη για ρέψιμο." to
        "Hold baby upright on your shoulder and gently pat the back to burp.",

    // ---- History screen ----
    "Καθαρισμός" to "Clear",
    "Δεν υπάρχουν ακόμα καταγραφές. Πάτα «Άκου το μωρό» ή κατέγραψε ένα τάισμα." to
        "No records yet. Tap \"Listen to baby\" or log a feeding.",
    "Χρονολόγιο" to "Timeline",
    "Πάτησε μια καταγραφή για να ορίσεις/διορθώσεις την αιτία." to
        "Tap a record to set or correct the cause.",
    "Καθαρισμός ιστορικού;" to "Clear history?",
    "Θα διαγραφούν όλα τα καταγεγραμμένα κλάματα (μαζί με τις αποθηκευμένες ηχογραφήσεις), τα ταΐσματα, οι αλλαγές πάνας, το tummy time και τα γραφήματα. Αυτό που έμαθε το μοντέλο από εσένα ΔΕΝ επηρεάζεται." to
        "All recorded cries (including saved recordings), feedings, diaper changes, tummy time, and charts will be deleted. What the model learned from you will NOT be affected.",
    "Διαγραφή" to "Delete",
    "Διαγραφή κλάματος;" to "Delete this cry?",
    "Θα διαγραφεί οριστικά αυτή η καταγραφή κλάματος (και η ηχογράφησή της). Δεν μπορεί να αναιρεθεί." to
        "This cry record (and its recording) will be permanently deleted. This can't be undone.",
    "Ποια ήταν η αιτία;" to "What was the cause?",
    "Διάλεξε τι ήταν τελικά. Ενημερώνονται τα στατιστικά και μαθαίνει το μοντέλο." to
        "Pick what it actually was. Stats update and the model learns.",
    "Κλείσιμο" to "Close",
    "Τελευταίο τάισμα" to "Last feeding",
    "δεν έχει καταγραφεί" to "not recorded",
    "ίσως πεινάει" to "may be hungry",
    "Τελευταίο κλάμα" to "Last cry",
    "Σήμερα" to "Today",
    "Κλάματα" to "Cries",
    "Ταΐσματα" to "Feedings",
    "Πιο συχνή αιτία" to "Most common cause",
    "✓ επιβεβαιώθηκε" to "✓ confirmed",
    "Μοτίβα" to "Patterns",
    "Ώρες αιχμής κλάματος" to "Peak cry hours",
    "Μέσο διάστημα ταϊσμάτων" to "Average feeding interval",
    "Κλάματα ανά ημέρα (τελευταίες 7)" to "Cries per day (last 7)",
    "Χθες" to "Yesterday",

    // ---- Stats screen ----
    "Πώς τα πάει" to "How it's doing",
    "Τα νούμερα βασίζονται στις δικές σου επιβεβαιώσεις και διορθώσεις. Όσο περισσότερο απαντάς, τόσο πιο ακριβή γίνονται." to
        "Numbers are based on your confirmations and corrections. The more you respond, the more accurate they get.",
    "Φόρτωση..." to "Loading...",
    "Τρόπος ανάλυσης" to "Analysis method",
    "Έξυπνο μοντέλο (AI)" to "Smart model (AI)",
    "Απλή εκτίμηση" to "Simple estimate",
    "Κλάματα που καταγράφηκαν" to "Cries recorded",
    "Φορές που έδωσες απάντηση" to "Times you responded",
    "Σωστές προβλέψεις" to "Correct predictions",
    "Όσα έμαθε από εσένα" to "Examples it learned from you",
    "Προσαρμογή στο μωρό σου" to "Adaptation to your baby",
    "δεν υποστηρίζεται εδώ" to "not supported here",
    "ενεργή" to "active",
    "μαθαίνει ακόμη" to "still learning",
    "• «Φορές που έδωσες απάντηση»: πόσες φορές πάτησες ✓ ή διόρθωσες μια πρόβλεψη.\n• «Σωστές προβλέψεις»: από αυτές, πόσες τις είχε βρει σωστά η εφαρμογή.\n• «Προσαρμογή στο μωρό σου»: όταν μαζευτούν αρκετές διορθώσεις, το μοντέλο προσαρμόζεται ειδικά στο δικό σου μωρό." to
        "• \"Times you responded\": how often you tapped ✓ or corrected a prediction.\n• \"Correct predictions\": of those, how many the app got right.\n• \"Adaptation to your baby\": once enough corrections are collected, the model adapts specifically to your baby.",
    "Πόσο συχνά εμφανίζεται κάθε αιτία" to "How often each cause appears",
    "Η κατανομή όλων των κλαμάτων που κατέγραψες." to "Distribution of all cries you've recorded.",
    "Δεν υπάρχουν ακόμη καταγραφές." to "No records yet.",
    "Πόσο καλά αναγνωρίζει κάθε αιτία" to "How well each cause is recognized",
    "Από τις φορές που η αιτία ήταν πραγματικά αυτή (με βάση τις διορθώσεις σου), πόσες τις βρήκε σωστά. Υψηλότερο = καλύτερα." to
        "Of the times the cause was actually that one (based on your corrections), how many it got right. Higher = better.",
    "Δώσε μερικές απαντήσεις/διορθώσεις για να εμφανιστούν αυτά τα ποσοστά." to
        "Give a few answers/corrections for these percentages to appear.",
    "Τι μπερδεύει με τι" to "What gets confused with what",
    "Κάθε γραμμή = η πραγματική αιτία, κάθε στήλη = τι μάντεψε η εφαρμογή. Τα νούμερα στη διαγώνιο (ίδιο εικονίδιο) είναι τα σωστά· τα υπόλοιπα, τα μπερδέματα." to
        "Each row = the actual cause, each column = what the app guessed. Diagonal numbers (same icon) are correct; the rest are mix-ups.",
    "Θα εμφανιστεί μόλις αρχίσεις να δίνεις διορθώσεις." to "Will appear once you start giving corrections.",
    "Διαχείριση/μηδενισμός δεδομένων: στις Ρυθμίσεις (μενού ⋮ πάνω δεξιά)." to
        "Manage/reset data: in Settings (⋮ menu top right).",
    "Ημέρα του μωρού" to "Baby's day",
    "Μια γρήγορη γραμμή με τα σημερινά κλάματα, ταΐσματα, πάνες και tummy time." to
        "A quick line with today's cries, feedings, diapers and tummy time.",
    "Κλάμα" to "Cry",
    "Δεν υπάρχουν ακόμη σημερινές καταγραφές." to "No records for today yet.",
    "Δείχνει τις τελευταίες σημερινές καταγραφές με σειρά ώρας." to
        "Shows today's latest records in time order.",
    "Έξυπνες παρατηρήσεις" to "Smart insights",
    "Μικρά μοτίβα που ξεχωρίζουν από τις τελευταίες μέρες." to
        "Small patterns that stand out from the last few days.",
    "Δεν υπάρχει ακόμη αρκετό μοτίβο. Με λίγες ακόμη μέρες χρήσης θα εμφανίζονται παρατηρήσεις εδώ." to
        "No clear pattern yet. With a few more days of use, insights will appear here.",

    // ---- Settings screen ----
    "Μωρά" to "Babies",
    "Δεν έχει προστεθεί μωρό ακόμη." to "No baby added yet.",
    "Χωρίς όνομα" to "No name",
    "Χωρίς ημ. γέννησης" to "No birth date",
    "Διαγραφή μωρού" to "Delete baby",
    "Προσθήκη μωρού" to "Add baby",
    "Στοιχεία ενεργού μωρού" to "Active baby details",
    "Όνομα" to "Name",
    "Φύλο" to "Sex",
    "Αγόρι" to "Boy",
    "Κορίτσι" to "Girl",
    "Ημ. γέννησης" to "Birth date",
    "Δεν έχει οριστεί" to "Not set",
    "Επιλογή" to "Choose",
    "Αποθήκευση προφίλ" to "Save profile",
    "Αποθηκεύτηκε" to "Saved",
    "Ανάλυση" to "Analysis",
    "Μαθαίνει από εσένα" to "Learns from you",
    "Χρησιμοποιεί τις διορθώσεις σου (προσωποποίηση)" to "Uses your corrections (personalization)",
    "Η εφαρμογή λαμβάνει πάντα υπόψη το τελευταίο τάισμα, την ώρα και την ηλικία του μωρού για πιο ακριβή εκτίμηση." to
        "The app always considers the last feeding, time of day, and baby's age for a more accurate estimate.",
    "Δεδομένα" to "Data",
    "Προβολή / Εξαγωγή αναφοράς" to "View / Export report",
    "Ανοίγει μια όμορφη αναφορά· από εκεί την αποθηκεύεις ή τη μοιράζεσαι ως PDF" to
        "Opens a nice report; from there you save or share it as PDF",
    "Δημιουργία backup" to "Create backup",
    "Αποθήκευση όλων των δεδομένων σε αρχείο" to "Save all data to a file",
    "Επαναφορά από backup" to "Restore from backup",
    "Φόρτωση δεδομένων από αρχείο" to "Load data from a file",
    "Δικό μου dataset" to "My dataset",
    "Αποθήκευση ηχογραφήσεων" to "Save recordings",
    "Κρατά τοπικά κάθε κλάμα μαζί με την αιτία, για να χτίσεις δικό σου dataset." to
        "Keeps each cry locally with its cause, so you can build your own dataset.",
    "Υπολογισμός..." to "Calculating...",
    "Εξαγωγή dataset (zip)" to "Export dataset (zip)",
    "Εξάγει τις επιβεβαιωμένες ηχογραφήσεις + labels.csv για εκπαίδευση. Μένουν στη συσκευή σου." to
        "Exports confirmed recordings + labels.csv for training. Stays on your device.",
    "Προσωποποίηση (τι έμαθε από εσένα)" to "Personalization (what it learned from you)",
    "Ανανέωση προσωποποίησης" to "Refresh personalization",
    "Ξαναϋπολογίζει όσα έμαθε από τις διορθώσεις σου. Δεν σβήνει τίποτα." to
        "Recalculates what it learned from your corrections. Doesn't delete anything.",
    "Μηδενισμός προσωποποίησης" to "Reset personalization",
    "Ξεχνά όσα έμαθε από εσένα. Το ιστορικό & τα στατιστικά ΔΕΝ σβήνονται." to
        "Forgets what it learned from you. History & stats are NOT deleted.",
    "Ιστορικό & στατιστικά" to "History & stats",
    "Καθαρισμός ιστορικού & στατιστικών" to "Clear history & stats",
    "Μηδενίζει κλάματα, ηχογραφήσεις, ταΐσματα, αλλαγές πάνας, tummy time και γραφήματα. Η εκμάθηση παραμένει." to
        "Clears cries, recordings, feedings, diaper changes, tummy time, and charts. Learning remains.",
    "Μηδενισμός προσωποποίησης;" to "Reset personalization?",
    "Το μοντέλο θα ξεχάσει όσα έμαθε από τις διορθώσεις σου και θα επιστρέψει στη βασική του κατάσταση. Το ιστορικό & τα στατιστικά μένουν." to
        "The model will forget what it learned from your corrections and return to its default state. History & stats remain.",
    "Ναι" to "Yes",
    "Διαγραφή μωρού;" to "Delete baby?",
    "Θα αφαιρεθεί το προφίλ" to "The profile will be removed",

    // ---- Onboarding ----
    "Καλώς ήρθες!" to "Welcome!",
    "Συμπλήρωσε τα στοιχεία του μωρού σου. Η ηλικία βοηθά την εφαρμογή να εκτιμά καλύτερα την αιτία του κλάματος (π.χ. πόσο συχνά πεινά ανάλογα με την ηλικία). Μπορείς να τα αλλάξεις όποτε θες από τις Ρυθμίσεις." to
        "Fill in your baby's details. Age helps the app better estimate the cause of crying (e.g. how often baby gets hungry by age). You can change this anytime in Settings.",
    "Όνομα μωρού" to "Baby's name",
    "Ημερομηνία γέννησης" to "Date of birth",
    "Ξεκίνα" to "Get started",
    "Θα το κάνω αργότερα" to "I'll do this later",

    // ---- About screen ----
    "Έκδοση" to "Version",
    "Δημιουργήθηκε από τον Μάριο Θεοτή" to "Created by Mario Theotis",
    "Τι κάνει" to "What it does",
    "Ηχογραφεί το κλάμα του μωρού και εκτιμά την πιθανή αιτία (πείνα, κούραση, κοιλόπονος, ρέψιμο, δυσφορία) με ένα μοντέλο AI που τρέχει τοπικά στο κινητό. Μαθαίνει από τις διορθώσεις σου." to
        "Records your baby's cry and estimates the likely cause (hunger, tiredness, belly pain, burping, discomfort) with an AI model running locally on your phone. It learns from your corrections.",
    "Ιδιωτικότητα" to "Privacy",
    "Όλα γίνονται τοπικά στη συσκευή. Καμία ηχογράφηση ή δεδομένο δεν ανεβαίνει στο διαδίκτυο και δεν μοιράζεται με κανέναν." to
        "Everything happens locally on your device. No recordings or data are uploaded to the internet or shared with anyone.",
    "Τεχνολογία & credits" to "Technology & credits",
    "• YAMNet (Google, AudioSet) — εξαγωγή χαρακτηριστικών ήχου\n• TensorFlow Lite — inference στο κινητό\n• Σύνολα δεδομένων: donateacry-corpus, InfantCry-DBL (Mendeley Data), ESC-50\n• Jetpack Compose · Material 3" to
        "• YAMNet (Google, AudioSet) — audio feature extraction\n• TensorFlow Lite — on-device inference\n• Datasets: donateacry-corpus, InfantCry-DBL (Mendeley Data), ESC-50\n• Jetpack Compose · Material 3",
    "Σημαντική σημείωση" to "Important note",
    "Ενημερωτικό βοήθημα, όχι ιατρική συσκευή ή διάγνωση. Για οτιδήποτε αφορά την υγεία του μωρού, συμβουλέψου παιδίατρο." to
        "Informational aid, not a medical device or diagnosis. For anything concerning your baby's health, consult a pediatrician.",

    // ---- Safety screen ----
    "Η εφαρμογή είναι ένα βοηθητικό εργαλείο, ΟΧΙ ιατρική διάγνωση. Αν κάτι σε ανησυχεί, εμπιστέψου το ένστικτό σου και επικοινώνησε με τον παιδίατρό σου." to
        "This app is a helper tool, NOT a medical diagnosis. If something worries you, trust your instincts and contact your pediatrician.",
    "Ζήτησε άμεσα βοήθεια αν:" to "Seek immediate help if:",
    "Δυσκολία στην αναπνοή, πολύ γρήγορη/θορυβώδης αναπνοή, ή μπλε/μωβ χείλη & πρόσωπο." to
        "Difficulty breathing, very fast/noisy breathing, or blue/purple lips & face.",
    "Πυρετός σε μωρό κάτω των 3 μηνών (≥38°C), ή επίμονος/πολύ υψηλός πυρετός." to
        "Fever in a baby under 3 months (≥38°C), or persistent/very high fever.",
    "Ασταμάτητο, οξύ ή ασυνήθιστο κλάμα που δεν σταματά με τίποτα (πάνω από ~2 ώρες)." to
        "Non-stop, sharp, or unusual crying that won't stop (over ~2 hours).",
    "Υπερβολική νωθρότητα, δυσκολεύεσαι να το ξυπνήσεις ή είναι «πεσμένο»." to
        "Excessive drowsiness, hard to wake, or baby seems \"floppy\".",
    "Επαναλαμβανόμενοι εμετοί, εμετός πράσινος/με αίμα, ή αίμα στα κόπρανα." to
        "Repeated vomiting, green/bloody vomit, or blood in stool.",
    "Σημάδια αφυδάτωσης: πολύ λίγες βρεγμένες πάνες, στεγνό στόμα, βυθισμένη πηγή." to
        "Signs of dehydration: very few wet diapers, dry mouth, sunken fontanelle.",
    "Σπασμοί/τινάγματα, ή εξάνθημα που δεν ασπρίζει όταν το πιέζεις." to
        "Seizures/twitching, or a rash that doesn't blanch when pressed.",
    "Πτώση ή χτύπημα στο κεφάλι, ή αν αρνείται εντελώς να φάει." to
        "Fall or head injury, or completely refuses to eat.",
    "Πριν από αυτά, συνήθως αρκεί να ελέγξεις:" to "Before that, usually just check:",
    "Πείνα — πότε έφαγε τελευταία φορά;" to "Hunger — when did baby last eat?",
    "Πάνα — βρεγμένη ή λερωμένη;" to "Diaper — wet or dirty?",
    "Θερμοκρασία — μήπως κρυώνει ή ζεσταίνεται; (ένα στρώμα παραπάνω από εσένα)" to
        "Temperature — too cold or too warm? (one layer more than you)",
    "Αέρια/ρέψιμο — κράτησέ το όρθιο μετά το τάισμα." to "Gas/burping — hold upright after feeding.",
    "Κούραση/υπερδιέγερση — ησυχία, χαμηλό φως, αγκαλιά." to "Tiredness/overstimulation — quiet, dim light, cuddles.",
    "Ανάγκη για αγκαλιά και επαφή." to "Need for cuddles and contact.",
    "Οι πληροφορίες είναι γενικές και βασίζονται σε δημόσιες οδηγίες (τύπου AAP/NHS). Δεν αντικαθιστούν τη γνώμη γιατρού." to
        "Information is general and based on public guidelines (like AAP/NHS). It does not replace a doctor's opinion.",

    // ---- Soothe screen ----
    "Απαλοί ήχοι που θυμίζουν το περιβάλλον της κοιλιάς και βοηθούν το μωρό να ηρεμήσει. Ρύθμισε την ένταση με τα κουμπιά έντασης του τηλεφώνου." to
        "Gentle sounds reminiscent of the womb that help baby calm down. Adjust volume with your phone's volume buttons.",
    "Παίζει (χωρίς χρονικό όριο)" to "Playing (no time limit)",
    "Σταμάτα" to "Stop",
    "Χρονόμετρο" to "Timer",
    "Ήχοι" to "Sounds",
    "Παίζει τώρα" to "Playing now",
    "Απομένει:" to "Remaining:",

    // ---- Sound types ----
    "Λευκός θόρυβος" to "White noise",
    "Σταθερός «σσσ» - καλύπτει ήχους του σπιτιού" to "Steady \"shhh\" — masks household sounds",
    "Ροζ θόρυβος" to "Pink noise",
    "Πιο απαλό «σσσ», σαν καταρράκτης" to "Softer \"shhh\", like a waterfall",
    "Ηλεκτρική σκούπα" to "Vacuum cleaner",
    "Χαμηλό βουητό που ηρεμεί τα νεογέννητα" to "Low hum that calms newborns",
    "Κύματα" to "Ocean waves",
    "Αργά κύματα που πάνε κι έρχονται" to "Slow waves coming and going",
    "Καρδιακός παλμός" to "Heartbeat",
    "Σαν μέσα στην κοιλιά της μαμάς" to "Like inside mom's belly",
    "Νανούρισμα" to "Lullaby",
    "Απαλή μελωδία" to "Gentle melody",

    // ---- Report screen ----
    "Αποθήκευση / Κοινοποίηση PDF" to "Save / Share PDF",

    // ---- ViewModel / share / notifications ----
    "Σφάλμα ηχογράφησης" to "Recording error",
    "Άκουσα αλλά δεν ξεχώρισα καθαρό κλάμα. Δοκίμασε ξανά, πιο κοντά στο μωρό ή σε πιο ήσυχο χώρο." to
        "I heard something but couldn't pick out a clear cry. Try again, closer to baby or in a quieter room.",
    "Σφάλμα ανάλυσης:" to "Analysis error:",
    "Καταγράφηκε το τάισμα." to "Feeding logged.",
    "Ενημερώθηκε η αιτία." to "Cause updated.",
    "Συμπλήρωσε τα στοιχεία του νέου μωρού." to "Fill in the new baby's details.",
    "Το προφίλ αποθηκεύτηκε." to "Profile saved.",
    "Το ιστορικό & τα στατιστικά μηδενίστηκαν." to "History & stats cleared.",
    "Ανανεώθηκε." to "Refreshed.",
    "Η προσωποποίηση μηδενίστηκε." to "Personalization reset.",
    "άκυρο αρχείο" to "invalid file",
    "Σφάλμα επαναφοράς:" to "Restore error:",
    "«Revekka»: δεν ανιχνεύτηκε καθαρό κλάμα." to "\"Revekka\": no clear cry detected.",
    "«Revekka» — αποτέλεσμα" to "\"Revekka\" — result",
    "Πιθανές αιτίες:" to "Possible causes:",
    "Υπενθυμίσεις επιβεβαίωσης" to "Confirmation reminders",
    "Σε ρωτά λίγο μετά το κλάμα ποια ήταν τελικά η αιτία." to "Asks you shortly after a cry what the cause actually was.",
    "το μωρό" to "baby",
    "Πάτησε για να επιβεβαιώσεις γιατί έκλαψε." to "Tap to confirm why baby cried.",

    // ---- HTML report (optional) ----
    "Ακρίβεια (feedback)" to "Accuracy (feedback)",
    "Επιβεβαιώσεις" to "Confirmations",
    "Δείγματα εκμάθησης" to "Training examples",
    "Κατανομή αιτιών" to "Cause distribution",
    "Καταγραφές" to "Records",
    "Ώρα" to "Time",
    "Αιτία" to "Cause",
    "Ανατροφοδότηση" to "Feedback",
    "✓ σωστό" to "✓ correct",
    "Δημιουργήθηκε" to "Generated",
    "Σύνοψη για παιδίατρο" to "Doctor visit summary",
    "Σύντομη εικόνα για επίσκεψη: κλάματα, ταΐσματα, πάνες και tummy time από τα πρόσφατα δεδομένα." to
        "A compact visit overview: cries, feedings, diapers and tummy time from recent data.",
    "Κλάματα τελευταίου 24ώρου" to "Cries in last 24h",
    "Κλάματα τελευταίων 7 ημερών" to "Cries in last 7 days",
    "Ταΐσματα τελευταίου 24ώρου" to "Feedings in last 24h",
    "Πάνες τελευταίου 24ώρου" to "Diapers in last 24h",
    "κακά" to "poop",
    "Tummy time τελευταίου 24ώρου" to "Tummy time in last 24h",
    "Αν υπάρχουν κόκκινες σημαίες (πυρετός, δυσκολία στην αναπνοή, αφυδάτωση, ασυνήθιστο/επίμονο κλάμα), επικοινώνησε άμεσα με γιατρό." to
        "If there are red flags (fever, breathing difficulty, dehydration, unusual/persistent crying), contact a doctor promptly.",
    "«Revekka» — Δημιουργήθηκε από τον Μάριο Θεοτή. Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή." to
        "\"Revekka\" — Created by Mario Theotis. Informational aid, not medical advice.",

    // ---- Feedback (result card) ----
    "Ήταν σωστή η εκτίμηση;" to "Was the estimate correct?",
    "Ναι, σωστή" to "Yes, correct",
    "Όχι, άλλη αιτία" to "No, another reason",
    "Διάλεξε τη σωστή αιτία:" to "Choose the correct reason:",
    "Δεν ξέρω ακόμα" to "Don't know yet",
    "Κανένα πρόβλημα — θα σε ρωτήσουμε ξανά σε λίγα λεπτά, μόλις καταλάβεις." to
        "No problem — we'll ask again in a few minutes, once you figure it out.",
    "Αν δεν ξέρεις ακόμα, θα σε ρωτήσουμε σε λίγα λεπτά μόλις καταλάβεις (π.χ. αφού το ταΐσεις κι ηρεμήσει). Μπορείς να το αλλάξεις και αργότερα από το Ιστορικό." to
        "If you don't know yet, we'll ask in a few minutes once you figure it out (e.g. after feeding and calming). You can also change it later from History.",

    // ---- History: hour-of-day heatmap ----
    "Κλάμα ανά ώρα της ημέρας" to "Cries by hour of day",
    "Πιο σκούρο = περισσότερα κλάματα εκείνη την ώρα. Δείχνει τις «δύσκολες» ώρες (συχνά αργά το απόγευμα/βράδυ)." to
        "Darker = more cries in that hour. Shows the \"hard\" hours (often late afternoon/evening).",
    "Ώρα αιχμής:" to "Peak hour:",

    // ---- Saved recordings library ----
    "Αποθηκευμένα κλάματα" to "Saved cries",
    "Οι επιβεβαιωμένες ηχογραφήσεις με την αιτία τους. Πάτησε ▶ για να τις ακούσεις ξανά." to
        "Your confirmed recordings with their cause. Tap ▶ to play them again.",
    "Δεν υπάρχουν ακόμη αποθηκευμένες ηχογραφήσεις. Μόλις επιβεβαιώσεις γιατί έκλαψε το μωρό, η ηχογράφηση αποθηκεύεται εδώ αυτόματα." to
        "No saved recordings yet. Once you confirm why baby cried, the recording is saved here automatically.",

    // ---- Feeding reminder (notification channel) ----
    "Υπενθυμίσεις ταΐσματος" to "Feeding reminders",
    "Σε ειδοποιεί λίγο πριν την ώρα που συνήθως πεινά το μωρό." to
        "Notifies you shortly before your baby's usual feeding time.",

    // ---- Report: long-term overview ----
    "Συνολική εικόνα" to "Overview",
    "Ημέρες καταγραφής" to "Days tracked",
    "Μέσος όρος κλαμάτων/ημέρα" to "Average cries/day",
    "Ώρα αιχμής" to "Peak hour",
    "Πιο δύσκολη ημέρα" to "Busiest day",
    "Πιο σκούρο = περισσότερα κλάματα εκείνη την ώρα." to "Darker = more cries in that hour.",
    "Κλάματα ανά ημέρα (τελευταίες 14)" to "Cries per day (last 14)",

    // ---- Tummy time: home card + logging ----
    "Tummy Time" to "Tummy Time",
    "Οδηγός" to "Guide",
    "Έκανα Tummy Time" to "I did Tummy Time",
    "Καταγράφηκε το tummy time. Μπράβο!" to "Tummy time logged. Nice!",

    // ---- Tummy time: history & report ----
    "Tummy time ανά ημέρα (τελευταίες 7)" to "Tummy time per day (last 7)",
    "Σύνολο συνεδριών" to "Total sessions",
    "Μέσος όρος/ημέρα" to "Average/day",
    "Tummy time ανά ημέρα (τελευταίες 14)" to "Tummy time per day (last 14)",

    // ---- Tummy time: reminder (settings + notification) ----
    "Υπενθυμίσεις" to "Reminders",
    "Υπενθύμιση Tummy Time" to "Tummy Time reminder",
    "Δύο ήπιες ειδοποιήσεις την ημέρα (πρωί & απόγευμα), ανάλογα με την ηλικία, με το πόσες φορές απομένουν." to
        "Two gentle notifications a day (morning & afternoon), based on age, with how many sessions are left.",
    "Πρωινή υπενθύμιση" to "Morning reminder",
    "Απογευματινή υπενθύμιση" to "Afternoon reminder",
    "Υπενθυμίσεις tummy time" to "Tummy time reminders",
    "Δύο ήπιες υπενθυμίσεις την ημέρα για tummy time, ανάλογα με την ηλικία." to
        "Two gentle tummy time reminders a day, based on baby's age.",

    // ---- Tummy time: info screen ----
    "Χρόνος που το μωρό είναι ξύπνιο και ξαπλωμένο μπρούμυτα, υπό επίβλεψη. Δυναμώνει αυχένα, ώμους και κορμό και βοηθά στην κινητική ανάπτυξη (και προλαμβάνει το πλατύ/επίπεδο κεφαλάκι)." to
        "Time when baby is awake and lying on their tummy, supervised. It strengthens the neck, shoulders and trunk and supports motor development (and helps prevent a flat head).",
    "Μέρα 1" to "Day 1",
    "Ξεκίνα μόλις γυρίσεις σπίτι" to "Start once you're home",
    "Σύνολο/ημέρα έως ~7 εβδομάδες (AAP)" to "Total/day by ~7 weeks (AAP)",
    "Σύνολο/ημέρα έως 3–6 μηνών" to "Total/day by 3–6 months",
    "WHO: ελάχιστο/ημέρα" to "WHO: minimum/day",
    "Πρόγραμμα ανά ηλικία" to "Program by age",
    "Οι «φορές/ημέρα» είναι ενδεικτικές (σύνολο ÷ διάρκεια ανά φορά)· οι πηγές ορίζουν κυρίως το σύνολο των λεπτών." to
        "The \"times/day\" are indicative (total ÷ per-session length); sources mainly define the total minutes.",
    "Φορές/μέρα" to "Times/day",
    "Διάρκεια/φορά" to "Duration/session",
    // Age labels
    "Νεογέννητο · 1η εβδομάδα" to "Newborn · week 1",
    "Εβδομάδες 2–4" to "Weeks 2–4",
    "~7 εβδομάδες" to "~7 weeks",
    "Μήνας 2" to "Month 2",
    "Μήνας 3" to "Month 3",
    "Μήνες 4–5" to "Months 4–5",
    "Μήνας 6+ (μέχρι το μπουσούλημα)" to "Month 6+ (until crawling)",
    // Duration / total phrases (word-based ones only)
    "λίγα λεπτά" to "a few minutes",
    "σταδιακή αύξηση" to "gradual increase",
    "όσο αντέχει" to "as long as baby tolerates",
    // What to expect
    "Ξεκίνα από την 1η μέρα στο σπίτι — μετράει και η αγκαλιά στο στήθος (tummy-to-chest)." to
        "Start from day 1 at home — chest cuddles (tummy-to-chest) count too.",
    "«Λίγο & συχνά» — π.χ. μετά την αλλαγή πάνας ή μόλις ξυπνήσει." to
        "\"Little & often\" — e.g. after a diaper change or right after waking.",
    "Ορόσημο AAP: περίπου 15–30′ συνολικά μέσα στην ημέρα." to
        "AAP milestone: about 15–30′ total across the day.",
    "Σηκώνει πιο ψηλά και πιο σταθερά το κεφάλι." to "Lifts the head higher and more steadily.",
    "Στηρίζεται στους πήχεις και κρατά το κεφάλι 45–90°." to
        "Props on the forearms and holds the head at 45–90°.",
    "Αρχίζει να γυρίζει από μπρούμυτα σε ανάσκελα." to "Starts rolling from tummy to back.",
    "Παιχνίδι, κύλισμα, άπλωμα — συνέχισε μέχρι να μπουσουλήσει." to
        "Play, rolling, reaching — keep going until baby crawls.",
    // Safety & practical
    "Ασφάλεια & πρακτικά" to "Safety & practical tips",
    "Πάντα ξύπνιο και υπό επίβλεψη, σε επίπεδη σταθερή επιφάνεια." to
        "Always awake and supervised, on a flat firm surface.",
    "Ο ύπνος πάντα ανάσκελα (Back to Sleep) — το tummy time είναι μόνο για ξύπνιες ώρες παιχνιδιού." to
        "Sleep is always on the back (Back to Sleep) — tummy time is only for awake playtime.",
    "Καλή στιγμή: μετά την αλλαγή πάνας ή μόλις ξυπνήσει — όχι με γεμάτο στομάχι." to
        "Good moment: after a diaper change or right after waking — not on a full stomach.",
    "Αν δυσανασχετεί, δοκίμασε πλάγια θέση ή ένα ρολό πετσέτας κάτω από τις μασχάλες." to
        "If baby fusses, try a side position or a rolled towel under the armpits.",
    "Σταμάτα αν κουραστεί ή κλάψει· ξαναδοκίμασε αργότερα. «Λίγο & συχνά»." to
        "Stop if baby tires or cries; try again later. \"Little & often\".",
    "Συνέχισε καθημερινά μέχρι να μπουσουλήσει." to "Keep it up daily until baby crawls.",
    // Home-screen explainer + sources
    "Στην αρχική οθόνη" to "On the home screen",
    "Πάτα «Έκανα Tummy Time» κάθε φορά που το κάνεις. Θα βλέπεις πόσες φορές έχεις κάνει σήμερα και πόσες απομένουν για τον στόχο της ηλικίας — και θα σου το θυμίζουμε δύο φορές την ημέρα (πρωί & απόγευμα)." to
        "Tap \"I did Tummy Time\" each time you do it. You'll see how many you've done today and how many are left for the age goal — and we'll remind you twice a day (morning & afternoon).",
    "Γιατί διαφέρουν οι πηγές" to "Why sources differ",
    "WHO: τουλάχιστον 30′/ημέρα (ελάχιστο). AAP & Pathways: σταδιακά προς ~60′/ημέρα έως 3–6 μηνών. NHS: πιο συντηρητικά, ~20–30′/ημέρα έως 3–4 μηνών. Όλες συμφωνούν στο βασικό: ξεκίνα νωρίς, «λίγο & συχνά», κάθε μέρα, μέχρι το μπουσούλημα." to
        "WHO: at least 30′/day (minimum). AAP & Pathways: gradually toward ~60′/day by 3–6 months. NHS: more conservative, ~20–30′/day by 3–4 months. All agree on the basics: start early, \"little & often\", every day, until crawling.",
    "Πηγές: AAP/HealthyChildren, Pathways.org, NHS, WHO. Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή." to
        "Sources: AAP/HealthyChildren, Pathways.org, NHS, WHO. Informational aid, not medical advice.",
)
