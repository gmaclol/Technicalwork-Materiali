# Convenzioni di Codice e Architettura — [nome progetto]

> Questo file va in `tasks/conventions.md`. È lo scheletro generico; va compilato
> con le convenzioni reali del progetto corrente durante la prima sessione (o non
> appena esistono pattern consolidati), esattamente come si fa con la sezione
> "Stack e Vincoli" di `decisions.md`.
>
> Obiettivo: evitare che agenti diversi (Gemini, Claude, sessioni diverse) inventino
> pattern diversi da modulo a modulo. Se una convenzione non è scritta qui, l'agente
> deve dedurla guardando il codice esistente più vicino, non inventarne una nuova.

---

## Naming

- File: `...`
- Classi / componenti: `...`
- Funzioni / metodi: `...`
- Variabili: `...`
- Costanti: `...`

Esempio reale dal progetto:
```
...
```

---

## Architettura — Dove Vive la Logica

Per progetti MVVM (es. app Android Kotlin):

- **View / UI (Activity, Fragment, Composable):** solo rendering e input utente,
  zero logica di business.
- **ViewModel:** orchestrazione, esposizione di stato (StateFlow/LiveData), NESSUNA
  chiamata diretta a rete/DB/filesystem.
- **Repository:** unica fonte di accesso a dati (rete, DB, file, remote config).
  I ViewModel non parlano mai direttamente con Retrofit/Room/API esterne.
- **Model/Domain:** strutture dati pure, senza dipendenze da Android framework
  dove possibile.

Per progetti web (React/Vite ecc.):

- **Componenti UI:** solo presentazione, props in ingresso, eventi in uscita.
- **Hooks/servizi:** logica di business, chiamate API, stato condiviso.
- **Client API/Supabase:** centralizzato in un solo modulo, mai chiamato da
  componenti sparsi.

> Compila questa sezione con la mappa reale del progetto corrente (nomi cartelle,
> pattern effettivamente in uso) invece del generico sopra, non appena chiara.

---

## Gestione Stato Asincrono

- Coroutines / StateFlow: `...` (es. quando usare `repeatOnLifecycle`, come evitare
  ri-emissioni di stato stale — vedi `lessons.md` per bug già presi)
- Gestione errori di rete: `...`
- Loading/error/empty state: pattern standard usato nel progetto: `...`

---

## Gestione Errori

- Come vengono loggati gli errori: `...`
- Come vengono mostrati all'utente (Toast, Snackbar, dialog, banner): `...`
- Errori silenziosi: quando sono accettabili e quando no: `...`

---

## Commenti e Documentazione nel Codice

- Commentare solo logica non ovvia (motivazione, non descrizione di cosa fa la riga).
- Niente commenti tipo "aggiungo variabile x" — richiesto in `Org.md` sezione
  "Non Aggiungere Nulla di Non Richiesto".
- Lingua dei commenti: italiano (vedi `Org.md`).

---

## Test e Verifica

- Esistono test automatici? `Sì/No — dove si trovano`
- Come si esegue la build/verifica locale prima di considerare un task completo:
  `...` (comando esatto, es. `./gradlew assembleDebug`, `npm run build`)
- Cosa verificare manualmente quando non ci sono test (checklist minima):
  `...`

---

## Versionamento (se applicabile, es. app Android)

- `versionCode` / `versionName`: **gestiti manualmente da Stefano**, l'agente non li
  tocca mai autonomamente, nemmeno a fine task.
- Changelog / release notes: `...`

---

## Storico Modifiche a Questo File

Aggiungi qui una riga ogni volta che una convenzione viene introdotta o cambiata,
così le sessioni future capiscono il *perché* e non solo il *cosa*.

```md
## 2026-07-03 — Creazione file
Prima stesura delle convenzioni per [nome progetto].
```
