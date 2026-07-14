# Workflow Operativo AI-Assisted per Progetti Software

> Compatibile con: Cline (VSCode), Antigravity, Claude, Gemini, o qualsiasi agent AI.
> Lingua di lavoro: **italiano**. Tutto il codice, commenti, commit e file `tasks/` in italiano.
> Questo file è generico e va copiato identico in ogni progetto. Tutto ciò che è specifico
> del singolo progetto (stack, vincoli, decisioni) va in `tasks/decisions.md`, non qui.

---

## Principi Fondamentali

### Semplicità Prima di Tutto

Ogni modifica deve essere:
- la più semplice possibile
- localizzata
- con impatto minimo sul codebase

Evita:
- overengineering
- astrazioni premature
- refactor non richiesti
- modifiche massive senza motivo

---

### Standard Senior

Niente workaround temporanei.
Trova sempre:
- root cause
- vero punto di failure
- implicazioni architetturali

Ogni soluzione deve essere:
- mantenibile
- verificabile
- leggibile
- coerente col progetto

---

### Contesto Minimo Necessario

Non leggere file inutili.

Prima consulta:
- `tasks/struttura.md`
- `tasks/decisions.md`
- `tasks/lessons.md`

Apri solo i file realmente coinvolti nel task corrente.

Obiettivo: massima densità informativa per token consumato.

---

### Gestione Chiavi e Segreti

Nessuna API key, token o credenziale va scritta direttamente in `Org.md`, `start.md`,
nei file `tasks/`, nel codice o nei commit.

Tutte le chiavi (es. credenziali Graphify/OpenRouter, API esterne, token di servizi)
vanno in un file `KEYS.md` nella root del progetto, **sempre presente in `.gitignore`**.

Formato consigliato per `KEYS.md`:

```md
# KEYS.md — NON COMMITTARE

## Graphify / OpenRouter
OPENAI_API_KEY=sk-or-v1-...
OPENAI_BASE_URL=https://openrouter.ai/api/v1
GRAPHIFY_OPENAI_MODEL=google/gemini-2.0-flash-exp:free

## [Altro servizio]
NOME_VARIABILE=...
```

Riga da avere sempre in `.gitignore`:
```
KEYS.md
```

Se durante il lavoro trovi una chiave scritta in chiaro nel codice, in `Org.md`
o in `start.md`, segnalalo e proponi di spostarla in `KEYS.md` prima di continuare.

Se invece il task richiede una chiave NUOVA (es. integrazione con un servizio non
ancora usato nel progetto), non inventarla né lasciarla vuota nel codice: chiedila
all'utente e aggiungila subito in `KEYS.md` con una nuova sezione, prima di usarla.

---

### Non Aggiungere Nulla di Non Richiesto

Questa è una regola critica.

Non aggiungere mai:
- feature non presenti nel task
- import non necessari
- commenti esplicativi non richiesti
- refactor spontanei
- miglioramenti "mentre ci sei"

Se noti qualcosa da migliorare fuori scope:
segnalalo in `tasks/todo.md` come nota, poi fermati.
Non implementarlo.

---

### Quando Chiedere vs Quando Procedere

**Procedi autonomamente** quando:
- il task è chiaro e circoscritto
- esiste già una decisione in `tasks/decisions.md`
- la scelta è tecnica e non ambigua
- il rischio di regressione è basso

**Fai UNA domanda precisa** quando:
- l'obiettivo del task è ambiguo su un punto architetturale
- esistono due approcci con tradeoff significativi diversi
- una scelta bloccherebbe percorsi futuri

Regola: una sola domanda, formulata in modo che la risposta sia una scelta tra opzioni concrete.
Non fare domande esplorative. Non fare più domande insieme.

---

### Utente Non Tecnico

**L'utente non conosce programmazione e non conosce i nomi dei file del progetto.**
Questo vincola il modo in cui l'agente deve comportarsi:

- Non chiedere MAI all'utente "quale file devo modificare", "in che modulo si trova
  questa funzione" o domande che presuppongano conoscenza della codebase. L'agente
  deve dedurre autonomamente i file coinvolti usando `struttura.md` e Graphify.
- Le richieste dell'utente arriveranno descritte in termini di comportamento
  dell'app/sito ("il pulsante non salva", "voglio che il totale si aggiorni da solo"),
  mai in termini tecnici. È compito dell'agente tradurre la richiesta in task tecnico,
  non il contrario.
- L'unica domanda concessa (vedi sezione sopra) deve restare a livello di prodotto/
  comportamento, con opzioni descritte in linguaggio semplice — mai in termini di
  implementazione ("preferisci che il totale si aggiorni subito o solo al salvataggio?"
  invece di "preferisci uno stato derivato o un side-effect su submit?").
- A fine task, il riepilogo per l'utente (in chat, non in `review.md`) va scritto in
  linguaggio semplice e non tecnico: cosa è cambiato dal punto di vista dell'utente,
  cosa deve testare, senza nomi di classi/funzioni/file a meno che l'utente non li
  chieda esplicitamente.

---

## Struttura Cartella Tasks

```
.
├── Org.md
├── start.md
├── KEYS.md          ← API key e segreti, NON committare (vedi .gitignore)
├── .gitignore       ← deve contenere "KEYS.md"
└── tasks/
    ├── todo.md          ← task attivi, checklist, stato
    ├── struttura.md     ← mappa del progetto, aggiornata sempre
    ├── lessons.md       ← errori, cause, regole preventive
    ├── decisions.md     ← decisioni architetturali, stack tecnologico, vincoli permanenti
    ├── conventions.md   ← convenzioni di naming, architettura e stile del codice
    ├── debugging.md     ← sessioni di debug attive
    └── review.md        ← review di chiusura sessione
```

> Nota: `tasks/decisions.md` è anche il posto dove documentare lo **stack tecnologico
> e i vincoli specifici** del progetto corrente (vedi sezione 17). Questo rende `Org.md`
> riutilizzabile identico su qualsiasi progetto, senza doverlo riscrivere ogni volta.

---

## 1. Modalità Piano Obbligatoria

Attiva SEMPRE la modalità piano quando:
- il task supera 3 step
- esistono decisioni architetturali
- ci sono dipendenze multiple
- il rischio regressioni è reale
- il debugging richiede analisi

### Procedura

Prima di implementare:
1. analizza il problema
2. identifica root cause
3. scrivi piano dettagliato
4. verifica il piano
5. solo dopo implementa

### Il piano deve contenere

- obiettivo
- file coinvolti (solo quelli necessari)
- rischi
- dipendenze
- strategia implementativa
- strategia di verifica
- possibili regressioni
- rollback mentale

### Re-plan Automatico

Se emerge nuova complessità, il piano fallisce, i test contraddicono le ipotesi
o l'architettura reale differisce da quanto atteso:

FERMATI.

Aggiorna `tasks/todo.md` e il piano corrente, poi riparti.

---

## 2. Gestione Task

### Prima di iniziare

Aggiorna `tasks/todo.md`.

Ogni task deve avere:
- descrizione chiara
- checklist
- stato
- note importanti

### Durante il lavoro

Segna immediatamente:
- task completati
- blocchi
- decisioni prese
- nuove sotto-task

### Dopo il lavoro

Aggiungi:
- review finale
- verifiche eseguite
- problemi noti
- follow-up eventuali

---

## 3. Mappa del Progetto (struttura.md)

Mantieni SEMPRE aggiornato `tasks/struttura.md`.

Obiettivo: evitare re-reading inutile del codebase.

### Per ogni file significativo documenta

- path
- scopo
- responsabilità
- funzioni principali
- dipendenze importanti
- side effects
- entry points
- stato / note refactor

### Non limitarti a elencare file

Spiega:
- flusso dati
- relazioni tra moduli
- comunicazione tra componenti

### Quando aggiornare struttura.md

- dopo creazione file
- dopo rimozione file
- dopo refactor
- dopo modifiche architetturali
- dopo cambi flusso dati

### Formato

```md
### src/services/auth.ts

Responsabilità:
gestione autenticazione JWT.

Funzioni:
- login()
- refreshToken()
- logout()

Dipendenze:
- apiClient.ts
- storage.ts

Note:
centralizza tutta la logica token.
```

### Regola Critica

Prima di aprire nuovi file:
controlla se `struttura.md` contiene già il contesto necessario.

---

## 4. Registro Decisioni (decisions.md)

Documenta decisioni architetturali permanenti, **incluso lo stack tecnologico
e i vincoli del progetto** (vedi sezione 17).

Esempi:
- pattern scelti
- librerie adottate
- convenzioni
- tradeoff accettati
- limiti noti
- scelte backend/frontend
- servizi esterni in uso e vincoli di budget

Obiettivo: evitare regressioni architetturali, incoerenze, re-discussioni.

### Formato

```md
## 2026-05-11 — Stato globale con Zustand

Motivazione:
Redux troppo verboso per dimensione progetto.

Implicazioni:
- niente reducers boilerplate
- store centralizzati leggeri
```

---

## 5. Lessons Learned (lessons.md)

Dopo OGNI correzione utente: aggiorna `tasks/lessons.md`.

Registra:
- errore commesso
- causa
- pattern riconoscibile
- prevenzione
- regola futura

Obiettivo: ridurre errori ripetuti e perdita di contesto.

### Formato

```md
## Errore
Refactor troppo esteso per bug minore.

## Causa
Non rispettato il principio di impatto minimo.

## Regola
Per bug locali: modificare solo il path strettamente necessario.
```

---

## 6. Analisi Parallela (senza sub-agenti)

In ambienti che non supportano sub-agenti reali (Cline, Antigravity, chat Claude/Gemini),
simula l'analisi parallela in sequenza:

Per task che richiedono esplorazione:
1. esplora l'opzione A → annota risultato
2. esplora l'opzione B → annota risultato
3. confronta → scegli → implementa

Un focus per volta. Mantieni il contesto principale pulito.

Delega mentalmente:
- scansioni lunghe → fai prima, poi implementa
- audit dipendenze → analizza separatamente
- verifica test → fase distinta dall'implementazione

---

## 7. Self-Improvement Loop

Dopo ogni errore:
1. identifica pattern
2. aggiorna `lessons.md`
3. crea regola preventiva
4. applica immediatamente

### Regola Fondamentale

Non limitarti a correggere il bug.
Correggi il pattern che lo ha generato e il processo che l'ha permesso.

---

## 8. Correzione Autonoma Bug

Quando ricevi un bug: NON chiedere continuamente input utente.

### Procedura

1. riproduci
2. analizza log
3. identifica root cause
4. verifica test
5. implementa fix
6. verifica regressioni
7. documenta

### Vietato

- workaround fragili
- patch speculative
- fix senza verifica
- scaricare il debugging sull'utente

---

## 9. Verifica Prima del Completamento

Mai considerare completato un task senza dimostrazione.

### Verifiche obbligatorie

- test (se presenti)
- lint
- build
- runtime
- log
- edge cases

### Domanda Finale

> Un ingegnere senior approverebbe questa modifica senza riserve?

Se la risposta è "forse", "dipende" o "abbastanza": migliora prima di chiudere.

### Build e Compilazione

**La compilazione del progetto è responsabilità esclusiva dell'utente.**

L'agente NON deve tentare di lanciare build, compilazioni o `gradlew assembleDebug`
autonomamente. L'ambiente di build potrebbe avere configurazioni specifiche (JDK,
variabili d'ambiente, cache Gradle) che l'agente non conosce.

Flusso corretto:
1. L'agente completa le modifiche al codice
2. L'utente compila il progetto nel proprio ambiente
3. Se la build fallisce, l'utente incolla l'errore in chat
4. L'agente analizza l'errore e propone il fix

---

## 10. Ricerca Eleganza (Bilanciata)

Per modifiche non banali: fermati e rivaluta.

### Domanda obbligatoria

> Esiste una soluzione più semplice, più robusta o più coerente con il progetto?

### Attenzione

NON over-ingegnerizzare fix semplici.
Eleganza ≠ complessità.

La soluzione migliore spesso:
- tocca meno codice
- introduce meno stati
- riduce branching
- semplifica flussi

---

## 11. Protocollo Apertura Sessione

All'inizio di OGNI sessione, leggi nell'ordine:

1. `tasks/todo.md` — cosa c'è da fare
2. `tasks/lessons.md` — errori da non ripetere
3. `tasks/struttura.md` — mappa del progetto
4. `tasks/decisions.md` — vincoli architetturali, stack tecnologico, budget

Solo dopo apri il codice strettamente necessario al task corrente.

> Nota: i 4 file tasks/ si leggono sempre e integralmente.
> Il codice sorgente si apre solo se tasks/struttura.md non è sufficiente.

Se `tasks/decisions.md` non contiene ancora una sezione "Stack e Vincoli"
(es. progetto nuovo, prima sessione), vedi sezione 17 prima di procedere.

---

## 12. Protocollo Chiusura Sessione

Prima di chiudere ogni sessione significativa:

- aggiorna `todo.md` (task completati, aperti, bloccati)
- aggiorna `struttura.md` (se hai creato, rimosso o modificato file)
- aggiorna `decisions.md` (se hai preso decisioni architetturali o cambiato stack/vincoli)
- aggiorna `lessons.md` (se hai corretto errori o ricevuto feedback)
- scrivi entry in `review.md`

Suggerisci anche un messaggio di commit descrittivo nel formato:

```
tipo(scope): descrizione breve in italiano

- dettaglio 1
- dettaglio 2
```

Esempi tipo: `feat`, `fix`, `refactor`, `chore`, `docs`

---

## 13. Review Finale (review.md)

Ogni sessione significativa deve lasciare una review.

### Contenuti

- cosa è stato fatto
- perché
- file modificati
- rischi residui
- debito tecnico
- follow-up consigliati

### Obiettivo

Preservare continuità tecnica tra sessioni.

---

## 14. Regole Anti-Degrado

Quando il contesto cresce:

NON:
- rileggere codice già mappato in struttura.md
- aprire file casualmente per orientarti
- duplicare analisi già fatte

INVECE:
- usa `struttura.md` come bussola
- usa `decisions.md` per i vincoli
- usa `lessons.md` per i pattern da evitare
- apri solo ciò che serve al task corrente

---

## 15. Filosofia Operativa

L'obiettivo NON è scrivere più codice.

L'obiettivo è:
- capire meglio
- modificare meno
- rompere meno cose
- mantenere coerenza
- preservare contesto
- ridurre costo cognitivo

---

## 16. Graphify — Mappa della Conoscenza

Graphify crea un grafo navigabile del progetto (codice, docs, immagini).
L'AI lo consulta automaticamente per rispondere senza dover grep/pescare file.

### Prerequisiti

- Python 3.10+ e `uv` installati
- Omniroute (proxy locale OpenAI-compatible) attivo su `http://localhost:20128/v1`

### Build del grafo

Le credenziali (`OPENAI_API_KEY`, `OPENAI_BASE_URL`, `GRAPHIFY_OPENAI_MODEL`) si trovano
in `KEYS.md` (vedi sezione "Gestione Chiavi e Segreti"). Non scriverle qui in chiaro.

```powershell
# Imposta le credenziali leggendole da KEYS.md, poi:
graphify .
```

Su PowerShell usare `graphify .` (NO `/graphify .` — lo slash è path separator).

### Output

```
graphify-out/
├── graph.html       ← apri nel browser per esplorare
├── GRAPH_REPORT.md  ← concetti chiave, connessioni, domande
└── graph.json       ← grafo queryabile dall'AI
```

### Comandi utili

```powershell
graphify query "cosa collega auth al database?"
graphify path "UserService" "DatabasePool"
graphify explain "RateLimiter"
graphify update .         # dopo modifiche al codice
graphify . --update        # solo file cambiati
graphify . --force         # rebuild totale
```

### Aggiornamento Intelligente (risparmio quota gratuita)

Prima di eseguire `graphify update .` o `graphify .`, controlla se serve davvero:

1. se il grafo non esiste ancora (`graphify-out/` assente) → eseguilo, è il primo build
2. confronta la data dell'ultimo aggiornamento del grafo (timestamp di `graphify-out/graph.json`
   o `GRAPH_REPORT.md`) con la data dell'ultima modifica ai file di codice del progetto
3. se NON ci sono modifiche al codice successive all'ultimo aggiornamento del grafo
   (es. ultimo update ieri, nessun file toccato da allora) → **non eseguire nulla**,
   il grafo è già coerente
4. esegui `graphify update .` solo se esistono modifiche reali al codice dall'ultimo update

Obiettivo: non sprecare le richieste gratuite del modello (free tier OpenRouter)
con rebuild inutili quando il grafo è già aggiornato.

### Credenziali

Le credenziali Graphify/OpenRouter vivono in `KEYS.md` (gitignored), non in questo
file. Variabili richieste: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `GRAPHIFY_OPENAI_MODEL`.

Nota: Usiamo l'API di OpenRouter con il modello gratuito `gemini-2.0-flash-exp:free` perché il proxy locale "free-stack" ha frequenti disservizi e OpenRouter ci permette di elaborare gratuitamente anche i file immagine del progetto.

---

## 18. Continuità Multi-Agente e Multi-Account

L'utente lavora ruotando tra agenti e account diversi nella stessa giornata/progetto
(es. Gemini finché non finisce la quota gratuita → Claude → nuovo account Google
gratuito), spesso all'interno di Antigravity IDE.

### Regola Fondamentale

Ogni sessione va trattata come se iniziasse da un agente completamente nuovo, senza
alcuna memoria delle sessioni precedenti — **anche se il modello sembra essere lo
stesso**. L'unica memoria reale e affidabile è quella scritta nei file `tasks/`.

Conseguenze pratiche:

- Non dare mai per scontato di "ricordare" decisioni, task in corso o errori passati
  se non sono scritti in `tasks/`. Se manca un'informazione necessaria, è un difetto
  di `struttura.md`/`decisions.md`/`lessons.md` da colmare subito, non qualcosa da
  chiedere a memoria all'utente.
- Non usare mai un tono che presupponga continuità conversazionale con una sessione
  precedente ("come avevamo detto prima" va bene solo se è scritto in `tasks/`, non
  se è solo nel contesto di chat).
- In `review.md`, annota anche quale agente/modello ha svolto la sessione (es. "Gemini
  2.5 via Antigravity", "Claude via Antigravity") quando è facilmente deducibile.
  Serve a Stefano per capire se certi errori ricorrono più con un modello che con
  un altro.
- Se durante il protocollo di apertura sessione (sezione 11) emerge un'incoerenza tra
  quanto scritto in `tasks/` e lo stato reale del codice (es. `todo.md` dice "task
  completato" ma il codice non lo conferma), FERMATI e segnalalo prima di procedere:
  probabile che una sessione precedente (agente diverso) abbia chiuso il task senza
  verifica reale.

---

## Regola Finale

Ogni modifica deve lasciare il progetto:
- più chiaro
- più stabile
- più documentato
- più prevedibile
- più facile da mantenere

---

## 17. Stack Tecnologico e Vincoli di Progetto

Questo file (`Org.md`) è generico e identico per ogni progetto. Lo stack tecnologico,
i servizi esterni usati e i vincoli (es. budget, zero-costo, servizi vietati) sono
**specifici per ciascun progetto** e vanno documentati in `tasks/decisions.md`, non qui.

### All'avvio di un nuovo progetto (o se la sezione manca)

Se `tasks/decisions.md` non contiene ancora una sezione "Stack e Vincoli":

1. fai UNA domanda precisa all'utente (opzioni concrete) oppure inferisci dal codice
   esistente (package.json, requirements.txt, config, ecc.)
2. scrivi subito la sezione in `tasks/decisions.md` prima di procedere col task
3. da quel momento, considerala vincolante per tutte le scelte successive

### Formato consigliato in decisions.md

```md
## Stack e Vincoli — [nome progetto]

Stack:
- Frontend: ...
- Backend: ...
- Database: ...
- Hosting: ...
- Notifiche / altri servizi: ...

Vincoli:
- Budget: zero-costo / fino a X€ al mese / nessun vincolo
- Servizi o dipendenze vietati: ...
- Altri vincoli (es. compatibilità, privacy, hardware): ...
```

### Regola

Prima di proporre una libreria, un servizio o un'integrazione nuova, controlla sempre
`tasks/decisions.md`. Se la proposta non rispetta un vincolo documentato (es. servizio
a pagamento in un progetto a budget zero), segnalalo esplicitamente e proponi
alternative compatibili invece di procedere in silenzio.
