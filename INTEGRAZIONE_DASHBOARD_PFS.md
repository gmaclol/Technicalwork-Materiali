# Integrazione Dashboard: Feature Toggle Accesso PFS per Dispositivo

> **Destinatario:** Sessione di sviluppo / AI Agent del progetto **Dashboard Web (PWA)**  
> **Data:** 05 Settembre 2026  
> **Oggetto:** Aggiunta dello slider/switch per abilitare/disabilitare l'accesso alla sezione PFS sull'applicazione Android dei singoli tecnici, con etichette esplicative sopra ciascun comando.

---

## 1. Contesto & Obiettivo di Sicurezza

I dati PFS (dati georeferenziati, coordinate, schede tecniche e geonavigazione delle aree) sono asset riservati e critici aziendali.  
Per evitare che finiscano nelle mani di aziende terze o personale non autorizzato:

1. **Default Blindato:** Al primo avvio e su ogni nuova installazione dell'applicazione Android, la sezione PFS è **completamente disabilitata e invisibile** di default (`pfs_enabled = false`).
2. **Nessun Punto d'Accesso:** L'icona del logo PFS nel menu laterale (Drawer) è nascosta (`View.GONE`), il deep-link / routing automatico all'avvio è bloccato e le Activity `PfsActivity` e `GeoNavActivity` rifiutano l'accesso in `onCreate`.
3. **Controllo Remoto Istantaneo:** L'amministratore (Stefano) dalla **Dashboard Web** deve poter decidere chi abilitare attivando un interruttore dedicato per ciascun tecnico. Quando l'interruttore viene attivato o disattivato, l'app Android riceve l'evento in tempo reale (entro ~500ms): l'icona compare/scompare all'istante, e se un tecnico a cui viene revocato l'accesso è dentro la schermata PFS viene espulso immediatamente verso la schermata principale con messaggio di avviso.

---

## 2. Struttura Dati Firestore

Tutto passa dal documento centralizzato delle impostazioni dispositivi:

* **Collezione:** `settings`
* **Documento:** `devices_names`
* **Chiave:** `{deviceId}` (identificativo univoco hardware Android, es. `9a8b7c6d5e4f3a2b`)
* **Nuovo Campo:** `pfs_enabled` (booleano: `true` | `false`)

### Esempio Record Dispositivo in `settings/devices_names`

```json
{
  "9a8b7c6d5e4f3a2b": {
    "name": "Matias",
    "pfsAreas": ["TOH_1", "Asti"],
    "updatedAt": 1725558000000,
    "disabled": false,
    "banned": false,
    "pfs_enabled": true
  },
  "c4d3e2f1a0b98765": {
    "name": "Cristian",
    "updatedAt": 1725557000000,
    "disabled": false,
    "banned": false,
    "pfs_enabled": false
  }
}
```

> [!NOTE]
> Se il campo `pfs_enabled` è assente o `undefined`, l'app Android lo considera automaticamente **`false`**.

---

## 3. Requisiti UI nella Dashboard Web

Nella sezione **Tecnici** della Dashboard (attualmente visualizzata come nell'immagine allegata dall'utente):
- Ogni riga tecnico possiede: Nome, Badge SO, Modello dispositivo, Versione app, Batteria, GPS, Appalti/Aziende, Ultimo sync, e i pulsanti azione `[Rinomina]`, `[Blocca]`, `[Elimina]`.
- All'estrema destra è attualmente presente un singolo switch/slider che controlla la visibilità/stato del tecnico (`disabled`).

### ⚠️ Requisito Esplicito dell'Utente: Testo Sopra agli Slider
L'utente vuole **un testo ben chiaro sopra ad ogni slider** per ricordare immediatamente cosa fa ciascuno senza possibilità di confusione.

Devono quindi esserci **due slider affiancati** per ogni riga, raggruppati con una chiara intestazione verticale:

```
+---------------------------------------------------------------------------------------------------------------------------------------------+
| Matias [Android]                                                                                                VISIBILITÀ       ACCESSO    |
| samsung Galaxy A14 EU · Ver 2.6 · 18% · GPS Attivo · Elecnor, Sertori  [Rinomina] [Blocca] [Elimina]             DASHBOARD       PFS APP    |
|                                                                                                                  [ (O) ]         [ (O) ]    |
|                                                                                                                  (Verde)         (Verde)    |
+---------------------------------------------------------------------------------------------------------------------------------------------+
```

Oppure con un mini-box per ciascuno slider:

```html
<!-- Blocco Slider con etichette esplicative superiori -->
<div class="tech-switches-group" style="display: flex; gap: 16px; align-items: center;">
  
  <!-- Slider 1: Visibilità Dashboard -->
  <div class="switch-item" style="display: flex; flex-direction: column; align-items: center; gap: 4px;">
    <span class="switch-label" style="font-size: 10px; font-weight: 600; text-transform: uppercase; color: #94a3b8; letter-spacing: 0.5px;">
      Visibilità Dashboard
    </span>
    <label class="switch">
      <input type="checkbox" 
             class="tech-toggle-active" 
             data-device-id="${deviceId}" 
             ${!device.disabled ? 'checked' : ''} 
             onchange="handleToggleTechActive('${deviceId}', this.checked)">
      <span class="slider round"></span>
    </label>
  </div>

  <!-- Slider 2: Abilitazione PFS App (NUOVO) -->
  <div class="switch-item" style="display: flex; flex-direction: column; align-items: center; gap: 4px;">
    <span class="switch-label" style="font-size: 10px; font-weight: 600; text-transform: uppercase; color: #38bdf8; letter-spacing: 0.5px;">
      Accesso PFS App
    </span>
    <label class="switch">
      <input type="checkbox" 
             class="tech-toggle-pfs" 
             data-device-id="${deviceId}" 
             ${device.pfs_enabled ? 'checked' : ''} 
             onchange="handleTogglePfsAccess('${deviceId}', this.checked)">
      <span class="slider round slider-pfs"></span>
    </label>
  </div>

</div>
```

---

## 4. Logica JavaScript da Implementare nella Dashboard

### 4.1. Lettura dello stato iniziale
Nel rendering della tabella/elenco tecnici:
```javascript
const isPfsEnabled = Boolean(device.pfs_enabled);
```

### 4.2. Salvataggio su Firestore alla commutazione dello slider
Quando Stefano attiva o disattiva lo slider PFS:

```javascript
/**
 * Abilita o disabilita l'accesso alla sezione PFS per uno specifico dispositivo.
 * @param {string} deviceId ID hardware del dispositivo
 * @param {boolean} isEnabled Nuovo stato (true = abilitato, false = disabilitato)
 */
async function handleTogglePfsAccess(deviceId, isEnabled) {
  try {
    const devicesRef = doc(db, 'settings', 'devices_names');
    
    // Aggiornamento atomico del campo pfs_enabled per il device specificato
    await updateDoc(devicesRef, {
      [`${deviceId}.pfs_enabled`]: isEnabled,
      [`${deviceId}.updatedAt`]: Date.now()
    });

    const techName = state.devices?.[deviceId]?.name || deviceId;
    if (isEnabled) {
      showToast(`PFS abilitato per ${techName}`, 'success');
    } else {
      showToast(`PFS disabilitato per ${techName}`, 'info');
    }
  } catch (error) {
    console.error("Errore aggiornamento PFS per device " + deviceId, error);
    showToast("Errore durante l'aggiornamento PFS: " + error.message, 'error');
    // Ripristina graficamente lo switch in caso di errore di rete
    const checkbox = document.querySelector(`.tech-toggle-pfs[data-device-id="${deviceId}"]`);
    if (checkbox) checkbox.checked = !isEnabled;
  }
}
```

---

## 5. Checklist di Collaudo

1. **Nuovo dispositivo / Dispositivo non abilitato:**
   - Lo slider "Accesso PFS App" appare disattivato (spento).
   - Sull'app Android del tecnico il logo PFS nel Drawer è completamente assente.
2. **Attivazione dello slider dalla Dashboard:**
   - Stefano clicca sullo slider "Accesso PFS App".
   - Lo slider diventa verde.
   - Sull'app Android del tecnico (anche se già aperta in primo piano), l'icona PFS compare immediatamente nel Drawer senza dover riavviare l'app.
3. **Disattivazione dello slider dalla Dashboard:**
   - Stefano disattiva lo slider.
   - Sull'app Android del tecnico l'icona sparisce dal Drawer.
   - Se il tecnico era al momento all'interno della schermata PFS o della mappa, viene mostrato il messaggio "Accesso PFS non abilitato o revocato." e viene riportato all'elenco materiali standard.
