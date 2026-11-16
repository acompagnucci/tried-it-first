package misura;

import java.util.List;

enum Modo {

    NESSUNA_CACHE("nessuna-cache", "senza cache AOT", List.of()),
    CACHE_SENZA_CODICE("cache-senza-codice", "cache AOT con classi e profili, senza codice compilato",
            Cache.SENZA_CODICE.perUsare()),
    CACHE_CON_CODICE("cache-con-codice", "cache AOT con classi, profili e codice compilato",
            Cache.CON_CODICE.perUsare()),
    CACHE_SOLO_AVVIO("cache-solo-avvio", "cache AOT con codice, addestrata solo con l'avvio, senza richieste",
            Cache.SOLO_AVVIO.perUsare());

    private final String nome;
    private final String descrizione;
    private final List<String> opzioni;

    Modo(String nome, String descrizione, List<String> opzioni) {
        this.nome = nome;
        this.descrizione = descrizione;
        this.opzioni = opzioni;
    }

    String nome() {
        return nome;
    }

    String descrizione() {
        return descrizione;
    }

    List<String> opzioni() {
        return opzioni;
    }
}
