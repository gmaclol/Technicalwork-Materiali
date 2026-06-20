# Task: Analisi Codebase e Popolamento struttura.md

## Obiettivo

Scansiona l'intero codebase del progetto e popola `tasks/struttura.md`
con una mappa completa e navigabile di ogni file significativo.

Questo task si esegue **una sola volta** (o dopo un refactor architetturale maggiore).
Al termine, `struttura.md` deve essere sufficiente per agire sul codebase
senza riaprire i file nella maggior parte dei casi.

---

## Preparazione

1. Leggi `tasks/decisions.md` se esiste — contiene contesto architetturale già noto
2. Leggi `tasks/lessons.md` se esiste — contiene pattern da tenere a mente
3. Se esiste già `graphify-out/` (grafo costruito in precedenza), consultalo per
   farti un'idea rapida di moduli e dipendenze principali — usalo come punto di
   partenza per orientarti più in fretta, NON come sostituto della scansione:
   `struttura.md` resta il documento di riferimento testuale, va comunque popolato
   per intero seguendo il formato sotto
4. Non aprire altro prima di aver completato la scansione

---

## Istruzioni di Scansione

Esplora ricorsivamente tutte le directory del progetto.

**Escludi:**
- `.git/`
- `build/` e `dist/`
- `node_modules/`
- `.gradle/`
- `__pycache__/`
- File binari, immagini, font, file generati automaticamente

---

## Per ogni file significativo documenta in struttura.md

```md
### path/relativo/al/file

Responsabilità:
cosa fa questo file, in 1-3 righe.

Funzioni / Classi chiave:
- NomeClasse — ruolo
- nomeMetodo() — cosa fa

Dipendenze (verso altri file del progetto):
- altro/file.kt
- altro/modulo.py

Entry points:
se applicabile (es. Activity, Fragment, main(), route handler)

Side effects:
scritture su disco, chiamate di rete, modifiche stato globale, ecc.

Stato:
stabile | WIP | legacy | da refactor
```

Non documentare l'implementazione.
Documenta il **ruolo** e le **relazioni**.

---

## Sezioni obbligatorie in struttura.md

### 1. Architettura Generale

- Pattern architetturali adottati (es. MVVM, Repository, Clean Architecture)
- Layer dell'applicazione e loro confini
- Moduli principali e come si relazionano
- Tecnologie e librerie chiave per layer

### 2. Flussi Dati Principali

Per ogni flusso significativo (es. login, caricamento lista, salvataggio):
- chi produce il dato
- chi lo trasforma
- chi lo consuma
- dove viene persistito

### 3. Mappa dei File

Un file per voce, nel formato sopra descritto.
Raggruppa per directory o modulo logico.

### 4. Punti di Attenzione

- Aree di debito tecnico visibile
- File con responsabilità multiple (candidati a split)
- Dipendenze circolari o accoppimenti forti
- Codice duplicato rilevante

*Non fixare nulla in questo task. Solo documenta.*

---

## Criteri di Completamento

- Ogni file aperto ha la sua voce in `struttura.md`
- Nessun file è stato aperto due volte
- Le sezioni Architettura e Flussi Dati sono compilate
- `struttura.md` permette di rispondere alla domanda
  "dove devo agire per modificare X?" senza aprire file

---

## Output Finale

Aggiorna `tasks/review.md` con:
- data esecuzione
- numero di file analizzati
- moduli identificati
- eventuali anomalie architetturali notate
