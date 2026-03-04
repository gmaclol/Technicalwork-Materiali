package com.technicalwork.materiali

class MaterialMerger {

    /**
     * Unisce i materiali inviati dal tecnico con la lista master aziendale.
     * 
     * Logica:
     * 1. Aggiunge i materiali del tecnico che NON sono presenti nella masterList (Extra).
     * 2. Scorre la masterList nell'ordine originale:
     *    - Se la riga è un separatore standard (::TESTO::), la aggiunge così com'è.
     *    - Se la riga è un separatore extra (;;TESTO;;), la aggiunge SOLO SE sono stati aggiunti materiali extra.
     *    - Inserisce i materiali extra immediatamente dopo la PRIMA occorrenza di ;;TESTO;;.
     *    - Se non esiste alcun ;;TESTO;;, i materiali extra vanno in cima (fallback).
     */
    fun merge(techMaterials: List<Pair<String, String>>, masterList: List<String>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val separatorRegex = Regex("^::.*::$")
        val separatorExtraRegex = Regex("^;;.*;;$")
        
        // 1. Identifichiamo i materiali "Extra" (tecnico non in masterList e non separatori)
        val normalizedMaster = masterList
            .filter { 
                val trimmed = it.trim()
                !trimmed.matches(separatorRegex) && !trimmed.matches(separatorExtraRegex)
            }
            .map { it.trim().lowercase() }
            .toSet()

        val extraMaterials = techMaterials.filter { (name, _) ->
            val trimmedName = name.trim()
            !trimmedName.matches(separatorRegex) && !trimmedName.matches(separatorExtraRegex) && 
            !normalizedMaster.contains(trimmedName.lowercase())
        }
        
        val hasExtras = extraMaterials.isNotEmpty()
        val hasExtraSeparatorInMaster = masterList.any { it.trim().matches(separatorExtraRegex) }

        // Fallback: se ci sono materiali extra ma NON c'è il separatore ;;.*;; nella masterList, vanno in cima
        if (hasExtras && !hasExtraSeparatorInMaster) {
            result.addAll(extraMaterials)
        }

        // 2. Scorriamo la masterList mantenendo l'ordine del file
        val techMap = techMaterials.associateBy { it.first.trim().lowercase() }
        var extrasAlreadyInjected = !hasExtraSeparatorInMaster // true se già messi in cima o se non c'è separatore in master

        masterList.forEach { masterName ->
            val trimmedMasterName = masterName.trim()
            
            when {
                trimmedMasterName.matches(separatorRegex) -> {
                    // Separatore standard: sempre aggiunto
                    result.add(Pair(masterName, ""))
                }
                trimmedMasterName.matches(separatorExtraRegex) -> {
                    // Separatore extra: aggiunto solo se ci sono materiali extra del tecnico
                    if (hasExtras) {
                        result.add(Pair(masterName, ""))
                        // Inseriamo gli extra subito dopo la PRIMA riga ;;.*;; incontrata
                        if (!extrasAlreadyInjected) {
                            result.addAll(extraMaterials)
                            extrasAlreadyInjected = true
                        }
                    }
                }
                else -> {
                    // Materiale normale
                    val normalizedMasterName = trimmedMasterName.lowercase()
                    val techVersion = techMap[normalizedMasterName]
                    
                    if (techVersion != null) {
                        result.add(Pair(masterName, techVersion.second))
                    } else {
                        result.add(Pair(masterName, ""))
                    }
                }
            }
        }

        return result
    }
}
