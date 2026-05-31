# Workflow Operativo AI-Assisted per Progetti Software

## Principi Fondamentali

### Semplicità Prima di Tutto
Ogni modifica deve essere:
- la più semplice possibile
- localizzata
- con impatto minimo sul codebase

Evita:
- overengineering
- astrazioni premature
- refactor non necessari
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

Apri solo i file realmente coinvolti.

Obiettivo:
massima densità informativa per token consumato.

---

### Scope Stretto — Regola Assoluta
Non modificare mai file non direttamente coinvolti nel task corrente,
anche se noti miglioramenti possibili o refactor utili.

Invece:
- apri una nuova voce in `tasks/todo.md`
- descrivi il miglioramento notato
- lascialo per una sessione dedicata

---

# Struttura Cartella Tasks

```txt
tasks/
├── todo.md
├── struttura.md
├── lessons.md
├── decisions.md
├── debugging.md
└── review.md
```

---

# 1. Modalità Piano Obbligatoria (Plan Mode)

Attiva SEMPRE la modalità piano quando:
- il task supera 3 step
- esistono decisioni architetturali
- ci sono dipendenze multiple
- il rischio regressioni è reale
- il debugging richiede analisi

---

## Regole

Prima di implementare:
1. analizza il problema
2. identifica root cause
3. scrivi piano dettagliato
4. verifica il piano
5. solo dopo implementa

---

## Il piano deve contenere

- obiettivo
- file coinvolti
- rischi
- dipendenze
- strategia implementativa
- strategia di verifica
- possibili regressioni
- rollback mentale

---

## Re-plan Automatico

Attiva il re-plan se:
- emerge nuova complessità
- il piano fallisce
- i test contraddicono le ipotesi
- l'architettura reale differisce
- il task risulta sottostimato rispetto allo scope iniziale

FERMATI.

Aggiorna:
- `tasks/todo.md`
- piano corrente
- strategia

Poi riparti.

---

# 2. Quando Chiedere vs Quando Procedere

Procedi autonomamente per:
- bug con root cause chiara e fix localizzato
- task con piano già approvato
- modifiche documentate in `decisions.md`

Chiedi conferma PRIMA di procedere se:
- la root cause implica una scelta architetturale non documentata in `decisions.md`
- il fix richiede modifiche a file non previsti nel piano originale
- emerge un tradeoff con implicazioni funzionali visibili all'utente
- lo scope reale del task è significativamente maggiore di quello dichiarato

Non chiedere input per:
- dettagli implementativi interni
- scelte di stile coerenti col progetto
- debugging esplorativo

---

# 3. Gestione Task

## Prima di iniziare

Aggiorna `tasks/todo.md`.

Ogni task deve avere:
- descrizione chiara
- checklist
- stato
- note importanti

---

## Durante il lavoro

Segna immediatamente:
- task completati
- blocchi
- decisioni prese
- nuove sotto-task

---

## Dopo il lavoro

Aggiungi:
- review finale
- verifiche eseguite
- problemi noti
- follow-up eventuali

---

# 4. Mappa del Progetto (struttura.md)

Mantieni SEMPRE aggiornato `tasks/struttura.md`.

Obiettivo:
evitare re-reading inutile del codebase.

---

## Per ogni file significativo documenta

- path
- scopo
- responsabilità
- funzioni principali
- dipendenze importanti
- side effects
- entry points
- stato/refactor notes

---

## Non limitarti a elencare file

Spiega:
- flusso dati
- relazioni
- responsabilità
- comunicazione tra moduli

---

## Regole

Aggiorna `struttura.md`:
- dopo creazione file
- dopo rimozione file
- dopo refactor
- dopo modifiche architetturali
- dopo cambi flusso dati

---

## Formato consigliato

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

---

## Regola Critica

Prima di aprire nuovi file:
controlla se `struttura.md`
contiene già il contesto necessario.

---

# 5. Registro Decisioni (decisions.md)

Documenta decisioni architetturali permanenti.

---

## Esempi

- pattern scelti
- librerie adottate
- convenzioni
- tradeoff accettati
- limiti noti
- scelte backend/frontend

---

## Obiettivo

Evitare:
- regressioni architetturali
- incoerenze
- re-discussioni continue

---

## Formato

```md
## 2026-05-11 — Stato globale con Zustand

Motivazione:
Redux troppo verboso per dimensione progetto.

Implicazioni:
- niente reducers boilerplate
- store centralizzati leggeri
```

---

# 6. Lessons Learned (lessons.md)

Dopo OGNI correzione utente:
aggiorna `tasks/lessons.md`.

---

## Registra

- errore
- causa
- pattern
- prevenzione
- regola futura

---

## Obiettivo

Ridurre:
- errori ripetuti
- cicli inutili
- perdita contesto

---

## Formato

```md
## Errore
Refactor troppo esteso per bug minore.

## Causa
Non era stato rispettato il principio di impatto minimo.

## Regola
Per bug locali:
modificare solo il path strettamente necessario.
```

---

# 7. Debugging Log (debugging.md)

Aggiorna `tasks/debugging.md` durante ogni sessione di debugging attiva.

---

## Registra

- sintomo iniziale osservato
- ipotesi formulate (anche quelle scartate)
- log / stack trace rilevanti
- esperimenti eseguiti e risultati
- root cause identificata
- fix applicato

---

## Obiettivo

Evitare di ripercorrere lo stesso percorso di analisi in sessioni future.
Se un bug simile riappare, `debugging.md` è il primo posto da consultare.

---

## Formato

```md
## 2026-05-15 — Bug: NullPointerException in AuthViewModel

Sintomo:
crash al login su dispositivi Android < 10.

Ipotesi scartate:
- problema di threading (escluso, MainThread confermato)
- nullable non gestito in Repository (escluso, checked)

Root cause:
SharedPreferences non inizializzato prima del primo accesso
in cold start su API 28.

Fix:
lazy initialization in StorageManager.getInstance().
```

---

# 8. Sub-Agenti e Separazione di Contesto

Quando un task richiede analisi parallele o esplorazione estesa,
separa il lavoro in sotto-task autonomi con scope limitato.

Ogni sotto-task deve avere:
- obiettivo preciso e verificabile
- lista dei file che può aprire
- output atteso (es. "restituisci la lista delle dipendenze circolari")

---

## Delega sempre

- scansioni lunghe del codebase
- ricerca dipendenze
- audit di consistenza
- analisi log estesi
- verifica test su più moduli

Mantieni il contesto del task principale pulito.

---

# 9. Self-Improvement Loop

Dopo ogni errore:
1. identifica pattern
2. aggiorna `lessons.md`
3. crea regola preventiva
4. applica immediatamente

---

## Regola fondamentale

Non limitarti a correggere il bug.

Correggi:
- il pattern che lo ha generato
- il processo che l'ha permesso

---

# 10. Correzione Autonoma Bug

Quando ricevi un bug:
NON chiedere continuamente input utente.

---

## Procedura

1. riproduci
2. analizza log
3. identifica root cause
4. verifica test
5. implementa fix
6. verifica regressioni
7. documenta

---

## Vietato

- workaround fragili
- patch speculative
- fix senza verifica
- scaricare debugging sull'utente

---

# 11. Verifica Prima del Completamento

Mai considerare completato un task senza dimostrazione.

---

## Verifiche obbligatorie

- test
- lint
- build
- runtime
- log
- edge cases

---

## Domanda Finale

> Un ingegnere senior approverebbe questa modifica?

Se la risposta è:
- "forse"
- "dipende"
- "abbastanza"

allora migliora il lavoro.

---

# 12. Ricerca Eleganza (Bilanciata)

Per modifiche non banali:
fermarsi e rivalutare.

---

## Domanda obbligatoria

> Esiste una soluzione più semplice, più robusta o più coerente?

---

## Attenzione

NON over-ingegnerizzare fix semplici.

Eleganza != complessità.

La soluzione migliore spesso:
- tocca meno codice
- introduce meno stati
- riduce branching
- semplifica flussi

---

# 13. Protocollo Apertura Sessione

All'inizio di OGNI sessione:

1. leggi `tasks/todo.md`
2. leggi `tasks/lessons.md`
3. leggi `tasks/struttura.md`
4. leggi `tasks/decisions.md`
5. solo dopo apri il codice necessario

---

# 14. Protocollo Chiusura Sessione

Prima di chiudere:

- aggiorna `todo.md`
- aggiorna `struttura.md`
- aggiorna `decisions.md`
- aggiorna `lessons.md`
- aggiorna `debugging.md` se applicabile
- aggiorna `review.md`

---

# 15. Review Finale (review.md)

Ogni sessione significativa deve lasciare una review.

---

## Contenuti

- cosa è stato fatto
- perché
- file modificati
- rischi residui
- debito tecnico
- follow-up consigliati

---

## Obiettivo

Preservare continuità tecnica tra sessioni.

---

# 16. Regole Anti-Degrado

Quando il contesto cresce:

NON:
- rileggere tutto
- aprire file casualmente
- duplicare analisi

INVECE:
- usa `struttura.md`
- usa `decisions.md`
- usa `lessons.md`
- apri solo ciò che serve

---

# 17. Filosofia Operativa

L'obiettivo NON è scrivere più codice.

L'obiettivo è:
- capire meglio
- modificare meno
- rompere meno cose
- mantenere coerenza
- preservare contesto
- ridurre costo cognitivo

---

# Regola Finale

Ogni modifica deve lasciare il progetto:
- più chiaro
- più stabile
- più documentato
- più prevedibile
- più facile da mantenere
