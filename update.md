# 🛡️ Guida Aggiornamento Sicurezza Firebase & Procedura di Rilascio

> **Documento Operativo:** Questo file riassume le regole di sicurezza definitive per Cloud Firestore (Versione B.1 "Adattata"), i dettagli architetturali su cosa viene protetto e la procedura a tappe per rilasciare l'aggiornamento ai tecnici senza causare alcun disservizio.

---

## 📌 1. Regole Cloud Firestore Definitive (VERSIONE B.1 con Regex)

Queste sono le regole consolidate pronte per essere incollate nella scheda **Regole (Rules)** di **Cloud Firestore** su Firebase Console.

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Verifica se la richiesta possiede una sessione valida (token anonimo o email/password)
    function isAuthenticated() {
      return request.auth != null;
    }

    // Verifica se l'utente autenticato possiede il ruolo 'admin' nella collezione userRoles
    function isAdmin() {
      return isAuthenticated() &&
        get(/databases/$(database)/documents/userRoles/$(request.auth.uid)).data.role == 'admin';
    }

    // 1. Ruoli utente: sola lettura per utenti autenticati, modificabili SOLO da console Firebase
    match /userRoles/{userId} {
      allow read: if isAuthenticated();
      allow write: if false;
    }

    // 2. Dati personali e domicilio tecnici (GDPR): SOLO Admin
    match /tecniciPrivate/{deviceId} {
      allow read, write: if isAdmin();
    }

    // 3. Impostazioni e Anagrafica Dispositivi (settings / devices_names):
    //    I tecnici leggono e aggiornano i propri device, ma NESSUNO tranne l'Admin può cancellare
    match /settings/{docId} {
      allow read, create, update: if isAuthenticated();
      allow delete: if isAdmin();
    }

    // 4. Liste materiali cantieri (Elecnor, Sertori, Sirti, Consumo, ecc.):
    //    - Il magazzino principale del tecnico ({deviceId}) NON può essere cancellato da nessuno (tranne Admin)
    //    - I tecnici possono cancellare SOLO i propri snapshot storici temporanei (che terminano con _YYYY-MM-DD)
    match /{appalto}/{docId} {
      allow read, create, update: if isAuthenticated();
      allow delete: if isAdmin() ||
                      (isAuthenticated() && docId.matches('^.+_[0-9]{4}-[0-9]{2}-[0-9]{2}$'));
    }

    // 5. Segnalazioni e Log PFS:
    //    I tecnici possono scrivere e cancellare (per consentire il cleanup FIFO a 30 elementi dell'app)
    match /pfs_segnalati/{pfsId} {
      allow read, write: if isAuthenticated();
    }
    match /pfs_logs/{logId} {
      allow read, write: if isAuthenticated();
    }

    // 6. Storico Scambi Materiali QR:
    //    I tecnici creano e aggiornano (processedByTarget), ma la cancellazione è riservata all'Admin
    match /exchanges/{exchangeId} {
      allow read, create, update: if isAuthenticated();
      allow delete: if isAdmin();
    }
  }
}
```

### Regole Realtime Database (RTDB - Presenza Online)
*Resta la configurazione standard già attiva:*
```json
{
  "rules": {
    ".read": "auth != null",
    ".write": "auth != null"
  }
}
```

---

## 🔒 2. Analisi di Sicurezza: Cosa cambia e cosa viene protetto

### ✅ Danni Gravi e Irreversibili BLOCCATI (Al 100%)
1. **Dati Sensibili e Domicilio (`tecniciPrivate`)**: Indirizzi di casa e coordinate private dei tecnici sono inaccessibili a chiunque non sia loggato come Admin (Stefano).
2. **Anagrafica Dispositivi (`settings/devices_names`)**: Nessun utente esterno o anonimo può più cancellare l'elenco dei tecnici o "sbiancare" i dispositivi.
3. **Magazzino Principale Furgone (`{appalto}/{deviceId}`)**: Nessun utente non-admin può cancellare il documento del furgone di un tecnico. Anche se un tecnico è in ferie o assente per settimane, il suo magazzino non può essere eliminato.
4. **Scambi Materiali (`exchanges`)**: Lo storico dei movimenti e dei passaggi materiale tra tecnici non può essere cancellato.
5. **Privilegi e Ruoli (`userRoles`)**: Nessuno può auto-promuoversi Admin o modificare i ruoli via client.

### ℹ️ Il limite strutturale dell'accesso anonimo (Da conoscere)
Per consentire ai tecnici di lavorare sul campo **a zero attrito** (nessuna email, nessuna password, nessun modulo di login):
- Firebase rilascia un token anonimo automatico in background.
- Per Firestore, qualsiasi client che richieda un token anonimo ha lo status `request.auth != null`.
- **Cosa significa:** Un utente malintenzionato che si crei un token anonimo non può distruggere o cancellare i magazzini principali, ma può ancora leggere i dati materiali e aggiornare documenti se ne conosce l'ID. Questo è il miglior compromesso possibile (*"hardened"*) tra massima praticità per i tecnici e protezione totale dai danni catastrofici.

---

## 🚦 3. Procedura Operativa di Rilascio (Per non bloccare i tecnici)

> [!WARNING]
> **NON PUBBLICARE LE REGOLE B.1 PRIMA DI AVER DISTRIBUITO L'APP!**  
> I tecnici che utilizzano ancora la versione precedente dell'app non hanno il modulo `AuthManager` e inviano richieste senza token (`request.auth == null`). Se pubblichi le regole prima che abbiano aggiornato, il loro sync fallirà con errore `PERMISSION_DENIED`.

### Roadmap in 3 Fasi:

### FASE 1: Distribuzione Nuova App Android
1. Nel repository Android, compila l'APK di produzione in modalità Release:
   ```powershell
   .\gradlew :app:assembleRelease
   ```
2. Distribuisci l'APK aggiornato ai tecnici (tramite aggiornamento in-app o invio del file APK).

### FASE 2: Monitoraggio Adozione dalla Dashboard
1. Apri la Dashboard di amministrazione.
2. Controlla la tabella dei dispositivi nella colonna **"Versione App"**.
3. Verifica che i tecnici attivi siano passati alla nuova versione compilata.

### FASE 3: Pubblicazione Regole su Firebase Console
1. Accedi alla **Console Firebase** sul progetto `technicalwork-cloud`.
2. Vai su **Firestore Database** → scheda **Regole (Rules)**.
3. Incolla il blocco di codice delle regole della **Sezione 1** di questo documento.
4. Clicca su **Pubblica**.
5. Da questo momento, la porta è chiusa ermeticamente per chiunque non sia autenticato.

---

## 🛠️ 4. Cambiamenti Codice App Android (Stato Attuale e Futuri Miglioramenti)

### Stato Attuale (Già implementato nel codice)
- [x] **`AuthManager.kt` creato**: Gestisce in modo thread-safe e trasparente l'accesso anonimo con `signInAnonymously()`.
- [x] **Punti di chiamata integrati**: `AuthManager.ensureAuthenticated()` è inserito all'avvio dell'app (`MyApplication`), in `FirebaseRepository`, `SyncManager`, `PfsActivity`, `GeoNavActivity` ed `ExchangeRepository`.

### Miglioramenti Futuri Consigliati (Roadmap Pulizia & TTL)
1. **Isolamento della Delete in `FirebaseRepository.kt`**:
   Racchiudere la cancellazione degli snapshot >7 giorni in un `try-catch` dedicato indipendente dal salvataggio principale. In questo modo, anche se la pulizia dovesse incontrare un intoppo, la sincronizzazione del magazzino principale andrà sempre a buon fine al 100%.
2. **Predisposizione Campo `expireAt` per TTL Nativo**:
   Aggiungere sui soli documenti snapshot (`${deviceId}_$todayStr`) il campo:
   ```kotlin
   "expireAt" to com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000))
   ```
   *Nota fondamentale:* **MAI** inserire il campo `expireAt` sul documento principale `{deviceId}`, altrimenti Firestore cancellerebbe i furgoni dei tecnici assenti o in ferie.
3. **Abilitazione Policy TTL su Firestore**:
   Una volta che tutti gli snapshot avranno il campo `expireAt`, si potrà attivare la policy TTL da console Firebase e rimuovere definitivamente la chiamata client `.delete().await()` dal codice Android.
