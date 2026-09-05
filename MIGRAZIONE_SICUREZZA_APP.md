# 🛡️ Guida di Sicurezza: Migrazione Dashboard & Tasklist per App Android

> **Destinazione d'uso:** Questo documento serve come promemoria e come **prompt/guida per la sessione AI sul repository dell'applicazione Android (`Technicalwork-Materiali`)** per completare la blindatura dell'intero ecosistema Firebase.

---

## 📌 1. Riepilogo di quanto già fatto sulla Dashboard (`Technicalwork-Dashboard`)

Nel repository della Dashboard abbiamo sanato tutte le criticità rilevate durante l'audit di sicurezza:

1. **Eliminate le credenziali in chiaro dal codice (`js/state.js`)**:
   - Cancellata definitivamente la tabella `USERS` e gli hash SHA-256. Nessuna password o impronta vive più nel codice sorgente pubblico.
2. **Integrazione Firebase Authentication nativo (`js/auth.js`)**:
   - Il login ora autentica via server con `signInWithEmailAndPassword`.
   - Abbiamo inserito il supporto all'accesso rapido: gli utenti possono digitare solo `Stefano` o `Piero` (il codice completa in automatico con `@technicalwork.it`).
3. **Gestione Ruoli sicura su Cloud Firestore (`userRoles/{uid}`)**:
   - Creata la raccolta principale `userRoles` su Firestore con due documenti:
     - **Stefano** (`mAGQuE3YdYaNQWhREQzybaoR6iL2`): `{ name: "Stefano", role: "admin" }`
     - **Piero** (`G976ERgZdKYQDuy8kPkt2bPvOOg2`): `{ name: "Piero", role: "viewer" }`
   - Le regole vietano la modifica di questa raccolta via client: solo l'amministratore dalla console può cambiare i ruoli.
4. **Segregazione dei dati personali e domicilio tecnici (GDPR)**:
   - Spostata la lettura e il salvataggio di `homeAddress`, `homeLat`, `homeLng` nella collezione riservata **`tecniciPrivate/{deviceId}`**, accessibile esclusivamente dall'Admin.
5. **Sessione sicura e automatica**:
   - Rimossa la chiave non sicura `tw_session` da `localStorage`. La persistenza dell'accesso è gestita nativamente da Firebase con `onAuthStateChanged`.
6. **Regole di transizione attive (VERSIONE A)**:
   - Attualmente Firestore è protetto per la Dashboard e i dati sensibili, ma lascia aperte le scritture dei materiali (`{appalto}/{docId}`, `settings`, `pfs_segnalati`, `exchanges`) per **permettere all'attuale app Android dei tecnici di continuare a lavorare senza interruzioni**.

---

## 📱 2. Cosa bisogna fare sull'App Android (`Technicalwork-Materiali`)

Per consentire a Firebase di chiudere la porta anche a chiunque provi a scrivere dall'esterno senza autorizzazione, l'app Android deve autenticarsi.  
**I tecnici non dovranno fare nulla né inserire credenziali**: useremo l'accesso anonimo in background.

### 📋 Prompt pronto da incollare quando aprirai la sessione sul repo Android:

```text
Sei un senior Android/Kotlin developer. Lavori nel repository "Technicalwork-Materiali" (l'app Android utilizzata dai tecnici sul campo per sincronizzare materiali e cantieri su Firebase Firestore, progetto "technicalwork-cloud").

OBIETTIVO:
Integrare l'autenticazione anonima Firebase (FirebaseAuth.getInstance().signInAnonymously()) all'avvio dell'applicazione.

VINCOLI:
1. Zero impatto per i tecnici: NESSUNA schermata di login, nessuna password o email richiesta. L'autenticazione deve avvenire in modo totalmente silenzioso e trasparente in background.
2. Continuità di funzionamento: le operazioni di sync materiali su Firestore (cantieri Elecnor, Sertori, Sirti, Consumo, devices_names, pfs_segnalati, exchanges) devono continuare a funzionare esattamente come prima, sfruttando il token della sessione anonima.
3. Resilienza offline: se il dispositivo è offline o la connessione è assente, l'app deve comunque consentire al tecnico di operare localmente tramite la cache offline di Firestore senza crash.

COSA FARE:
1. Verifica e aggiungi se mancante la dipendenza di Firebase Auth nel build.gradle (es. implementation("com.google.firebase:firebase-auth-ktx") o tramite Firebase BoM).
2. Nel punto di ingresso dell'app (es. Application class o MainActivity/SplashActivity prima delle chiamate Firestore), verifica se FirebaseAuth.getInstance().currentUser == null:
   - Se null, avvia signInAnonymously() e attendi il completamento prima del primo sync di rete.
   - Se l'utente è già loggato (sessione anonima persistita su Android), procedi direttamente.
3. Compila il progetto e verifica che non ci siano regressioni nei sync o negli scambi materiali.
```

---

## 🔒 3. Cosa fare su Firestore dopo aver aggiornato l'App Android (VERSIONE B - Blindatura Totale)

Una volta che avrai compilato e distribuito la nuova versione dell'app Android ai tecnici, potremo **chiudere ermeticamente Firebase** in modo che **NESSUNA richiesta anonima non autorizzata** possa più leggere o scrivere un singolo byte.

### Procedura:
1. Apri la **Console Firebase** sul progetto `technicalwork-cloud`.
2. Vai su **Firestore Database** → scheda **Regole** (o *Rules*).
3. Sostituisci il testo con le regole della **VERSIONE B** qui sotto e clicca **Pubblica**:

#### Regole Cloud Firestore (VERSIONE B - Definitiva):
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    
    // Helper: verifica se la richiesta proviene da un utente autenticato (Dashboard o App Android)
    function isAuthenticated() {
      return request.auth != null;
    }

    // Helper: verifica se l'utente connesso ha ruolo admin
    function isAdmin() {
      return isAuthenticated() && 
        get(/databases/$(database)/documents/userRoles/$(request.auth.uid)).data.role == 'admin';
    }

    // 1. Ruoli Utente: leggibile solo da autenticati, NON modificabile via client
    match /userRoles/{userId} {
      allow read: if isAuthenticated();
      allow write: if false; // Modificabile SOLO da console Firebase
    }

    // 2. Dati Sensibili Tecnici (Indirizzi casa, coordinate GPS private): SOLO ADMIN
    match /tecniciPrivate/{deviceId} {
      allow read, write: if isAdmin();
    }

    // 3. Impostazioni Dashboard e Dispositivi (accessibile solo ad autenticati)
    match /settings/{docId} {
      allow read, write: if isAuthenticated();
    }

    // 4. Liste Materiali Cantieri (Elecnor, Sertori, Sirti, Consumo, ecc.)
    match /{appalto}/{docId} {
      allow read, write: if isAuthenticated();
    }

    // 5. Segnalazioni e Log PFS
    match /pfs_segnalati/{pfsId} {
      allow read, write: if isAuthenticated();
    }
    match /pfs_logs/{logId} {
      allow read, write: if isAuthenticated();
    }

    // 6. Scambi Materiali QR tra tecnici
    match /exchanges/{exchangeId} {
      allow read, write: if isAuthenticated();
    }
  }
}
```

#### Regole Realtime Database (RTDB - Presenza Online):
*Resta la stessa già pubblicata:*
```json
{
  "rules": {
    ".read": "auth != null",
    ".write": "auth != null"
  }
}
```

---

## 🧹 4. Pulizia opzionale finale dei dati vecchi (GDPR)

Quando avrai completato il passaggio alla Versione B, se vuoi ripulire al 100% anche lo storico preesistente:
1. Nella Console Firebase, vai nel documento `settings/devices_names`.
2. Se sono ancora presenti i campi `homeAddress`, `homeLat`, `homeLng` direttamente dentro i singoli dispositivi, puoi eliminarli (o lasciarli: tanto la dashboard ora li legge e li scrive prioritariamente in `tecniciPrivate`, che è protetta a monte).
