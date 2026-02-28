package com.technicalwork.materiali

class MaterialMerger {

    /**
     * Unisce i materiali inviati dal tecnico con la lista master aziendale.
     * 
     * Logica:
     * 1. Aggiunge i materiali del tecnico che NON sono presenti nella masterList.
     * 2. Scorre la masterList nell'ordine originale:
     *    - Se il tecnico ha quel materiale, lo aggiunge con la sua quantità.
     *    - Se il tecnico NON ha quel materiale, lo aggiunge con quantità vuota.
     * 
     * Il confronto dei nomi è case-insensitive e ignora gli spazi iniziali/finali (trim).
     */
    fun merge(techMaterials: List<Pair<String, String>>, masterList: List<String>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        
        // Creiamo una versione normalizzata della masterList per ricerche veloci e sicure
        val normalizedMaster = masterList.map { it.trim().lowercase() }.toSet()
        
        // Mappa dei materiali del tecnico per un accesso rapido (Key = nome normalizzato)
        val techMap = techMaterials.associateBy { it.first.trim().lowercase() }

        // 1. Aggiungiamo prima i materiali "Extra" (quelli del tecnico non in masterList)
        techMaterials.forEach { (name, quantity) ->
            if (!normalizedMaster.contains(name.trim().lowercase())) {
                result.add(Pair(name, quantity))
            }
        }

        // 2. Scorriamo la masterList mantenendo l'ordine del file
        masterList.forEach { masterName ->
            val normalizedMasterName = masterName.trim().lowercase()
            val techVersion = techMap[normalizedMasterName]
            
            if (techVersion != null) {
                // Il tecnico ha questo materiale della lista master
                result.add(Pair(masterName, techVersion.second))
            } else {
                // Il tecnico non ha questo materiale, lo aggiungiamo vuoto
                result.add(Pair(masterName, ""))
            }
        }

        return result
    }
}
