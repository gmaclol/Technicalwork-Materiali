Ciao! Prima di procedere con qualsiasi compito, leggi e segui queste istruzioni fondamentali:

1. **Regole e Workflow**: tutte le regole del progetto, i vincoli e le linee guida sono definite in `Org.md`. Leggilo subito per allinearti.

2. **Protocollo di Apertura Sessione**: segui rigorosamente il protocollo descritto in `Org.md`. Come prima cosa, leggi integralmente i seguenti file di contesto:
   - `tasks/todo.md` (cosa c'è da fare e checklist attiva)
   - `tasks/lessons.md` (errori passati da evitare)
   - `tasks/struttura.md` (mappa e relazioni dei file)
   - `tasks/decisions.md` (decisioni architetturali, stack tecnologico e vincoli del progetto, es. budget e servizi consentiti)

3. **Stack del progetto**: se `tasks/decisions.md` non contiene ancora una sezione "Stack e Vincoli" (es. è la prima sessione su questo progetto), chiedimi quali tecnologie e servizi stiamo usando e documentala subito in `tasks/decisions.md` prima di iniziare il task.

4. **Esplorazione con Graphify**: per capire rapidamente la struttura del progetto, navigare tra i file o rispondere a domande sul codebase, NON fare letture massive o grep casuali. Utilizza invece i comandi di graphify (es. `graphify query "la tua domanda"`, `graphify path`, `graphify explain`). Se esiste, fai riferimento anche a `graphify-out/wiki/index.md` per l'orientamento generale.

5. **Aggiornamento del Grafo (risparmio quota gratuita)**: se hai creato, eliminato o modificato file di codice, esegui `graphify update .` per tenere il grafo aggiornato. Se invece il grafo è già coerente con lo stato attuale del codice (es. ultimo aggiornamento recente e nessuna modifica successiva), NON rieseguirlo: evita di sprecare la quota gratuita dell'API per rebuild inutili.

6. **Lingua di Lavoro**: la lingua di lavoro ufficiale è l'italiano (per spiegazioni, commenti nel codice, messaggi di commit e file sotto `tasks/`).

7. **Vincoli del Progetto**: rispetta sempre i vincoli (budget, servizi consentiti o vietati, ecc.) documentati in `tasks/decisions.md`. Non assumere uno stack o un vincolo che non è stato documentato lì.

Conferma di aver letto i file indicati e di aver compreso le regole prima di iniziare a lavorare sul task che ti assegnerò.
