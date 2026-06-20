package com.technicalwork.materiali

import okhttp3.OkHttpClient

/**
 * Provider singleton per OkHttpClient condiviso all'interno dell'app.
 * Evita il sovraccarico di connessioni e thread derivanti dalla creazione
 * di molteplici client OkHttp indipendenti.
 */
object NetworkClient {
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient()
    }
}
