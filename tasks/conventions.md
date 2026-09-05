# Convenzioni di Codice e Architettura — Technicalwork Materiali

> Compilato deducendo i pattern reali dal codebase, da `tasks/struttura.md`, `tasks/decisions.md` e `tasks/lessons.md`.
> Obiettivo: preservare la coerenza architetturale e stilistica tra diverse sessioni e agenti AI.

---

## Naming

- **File sorgente Kotlin**: `PascalCase.kt` (es. `MainActivity.kt`, `MainViewModel.kt`, `ExcelRepository.kt`, `SyncWorker.kt`, `StockParser.kt`)
- **Classi / Componenti**: `PascalCase` coerente con il nome del file
- **Funzioni / Metodi**: `camelCase` con verbi d'azione chiari (es. `saveExcelFile()`, `loadExcelFile()`, `performFullSync()`, `syncToFirestore()`)
- **Variabili / Proprietà**: `camelCase` (es. `currentFileUri`, `isConsumoMode`, `lastSelectedCompany`)
- **Costanti**: `UPPER_SNAKE_CASE` (es. `TAG`, `SELECTED_AREA`)
- **Layout XML**: `snake_case.xml` con prefisso per tipologia:
  - Schermate: `activity_*.xml`
  - Elementi liste/adapter: `item_*.xml` (es. `item_data_row.xml`, `item_pfs.xml`, `item_exchange_row.xml`)
  - Dialoghi / Bottom sheet: `dialog_*.xml` / `bottom_sheet_*.xml`
- **Colori e Risorse XML**: `snake_case` (es. `gemini_bg`, `gemini_card_bg`, `gemini_accent_blue`)

---

## Architettura — Dove Vive la Logica

Pattern architetturale: **Ibrido MVVM / Activity-Repository**.

- **UI Layer (Activity / Adapter / Dialogs)**:
  - `MainActivity`: Schermata principale per la gestione dei fogli Excel dei materiali, appalti e consumi. Delega la logica UI complessa ad helper dedicati (`MainActivityDialogs.kt`, `MainActivityDrawerHelper.kt`).
  - Altre Activity (`GeoNavActivity`, `PfsActivity`, `ExchangeActivity`, `BannedActivity`): Activity-based con interazione diretta verso Repository e Helper.
  - `Adapters` (`ExcelDataAdapter`, `PfsAdapter`, `ExchangeAdapter`): Gestione binding, rendering condizionale ed eventi di interazione (click, pulsanti step +/-).
- **Presentation / State Layer (`MainViewModel`)**:
  - Utilizzato per `MainActivity`. Espone `StateFlow<UiState>` immutabile (`UiState.Initial`, `Loading`, `Success`, `Error`).
  - Mantiene lo stack della cronologia Undo (`Stack<UndoSnapshot>`) sopravvivendo alle ricreazioni dell'Activity (rotazione/tilt).
  - Nessuna dipendenza diretta da Context o chiamate bloccanti.
- **Data / Repository Layer**:
  - `ExcelRepository` & `ConsumoRepository`: Lettura/scrittura su Storage Access Framework (SAF) con Apache POI; gestione stream protetti con `use {}`.
  - `FirebaseRepository`: Operazioni su Firestore (sync collezioni aziende, snapshot storici con prefix query, telemetria e dispositivi).
  - `ExchangeRepository`: Gestione scambi materiali via QR e collezioni Firestore dedicate.
  - `SettingsRepository`: Wrapper `SharedPreferences` per tutte le configurazioni persistite dell'utente.
  - `HistoryRepository`: Persistenza atomica su file JSON (`history_{company}.json`) per l'Undo.
- **Background & Sync Layer**:
  - `MyApplication`: Gestore globale del ciclo di vita (`DefaultLifecycleObserver`). All'avvio e al resume (`onStart`), gestisce listener Firestore in tempo reale, telemetria e avvio sync.
  - `SyncWorker`: `CoroutineWorker` (`WorkManager`) che orchestra la sincronizzazione in background come rete di sicurezza.
  - Approccio Ibrido: Al salvataggio si esegue prima una scrittura best-effort immediata su `Dispatchers.IO` in-app per la reattività istantanea della dashboard, poi si accoda `SyncWorker.enqueue()` per la persistenza garantita.

---

## Gestione Stato Asincrono

- **Coroutines & Dispatchers**:
  - Tutte le operazioni di I/O (disco, rete, Firestore) devono essere dichiarate `suspend` ed eseguite esplicitamente su `withContext(Dispatchers.IO)`.
  - Main thread riservato esclusivamente all'aggiornamento UI.
- **StateFlow & Lifecycle**:
  - Nelle Activity l'osservazione dello `StateFlow` avviene con `repeatOnLifecycle(Lifecycle.State.STARTED)`.
  - Prima di iniziare un nuovo caricamento dati, chiamare `viewModel.clearState()` per garantire la corretta transizione `Initial → Loading → Success`.
  - All'aggiornamento dello stato, verificare se i dati sono realmente mutati prima di sostituire l'adapter per evitare perdite di focus o sfarfallii.
- **Intent & IPC Binder**:
  - MAI passare payload pesanti o liste serializzate negli Intent (`TransactionTooLargeException`). Passare solo identificatori minimi (es. `SELECTED_AREA` come String). I dati pesanti si leggono in streaming con `JsonReader` direttamente dal disco locale.

---

## Gestione Errori e Logging

- **Logging**:
  - Tutte le classi con logica di business o I/O devono definire un `TAG` costante nel `companion object`.
  - Usare `Log.d(TAG, "...")` per i flussi operativi e `Log.e(TAG, "...", e)` per le eccezioni.
  - VIETATO l'uso di blocchi `catch` vuoti o del semplice `e.printStackTrace()`.
- **Feedback Utente**:
  - Errori operativi o di validazione comunicati tramite `AlertDialog`, `BottomSheet` o `Toast` con messaggi chiari in italiano.
- **Risorse e Memory Leaks**:
  - Chiusura obbligatoria di stream e workbook POI tramite `use { ... }` o blocchi `try/finally`.
  - Nel WorkManager usare sempre `applicationContext` ed evitare riferimenti a contesti di Activity.

---

## Commenti e Documentazione nel Codice

- Lingua ufficiale per commenti, log, commit e documentazione: **italiano**.
- Commentare solo motivazioni architetturali o logiche non banali; evitare commenti ridondanti che ripetono banalmente l'operazione del codice.
- Mantenere aggiornati i file di contesto (`tasks/struttura.md`, `tasks/decisions.md`, `tasks/lessons.md`, `tasks/todo.md`) ad ogni modifica significativa.

---

## Test e Verifica

- **Compilazione**: L'utente compila ed esegue esclusivamente in modalità Release (`:app:assembleRelease`).
- **Test a Runtime**: Eseguiti autonomamente dall'utente sul dispositivo fisico.
- L'agente non lancia compilazioni o build locali se non richiesto, ma si assicura che il codice sia privo di errori di sintassi, import mancanti o incompatibilità di tipi.

---

## Versionamento

- `versionCode` e `versionName` in `app/build.gradle.kts` sono **gestiti manualmente dall'utente (Stefano)**. L'agente non deve mai modificarli autonomamente.

---

## Storico Modifiche a Questo File

## 2026-09-05 — Compilazione Convenzioni Reali
Compilato il file con le convenzioni effettive del progetto Technicalwork Materiali (naming, architettura ibrida MVVM/Repository, gestione asincrona, logging, vincoli di build).
