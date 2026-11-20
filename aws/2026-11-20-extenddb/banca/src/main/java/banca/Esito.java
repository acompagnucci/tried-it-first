package banca;

enum Esito {
    ESEGUITO("eseguiti"),
    CONFLITTO("rifiutati per conflitto"),
    SALDO_INSUFFICIENTE("rifiutati per saldo insufficiente"),
    CONTO_DI_ARRIVO_INESISTENTE("rifiutati per conto di arrivo inesistente");

    private final String descrizione;

    Esito(String descrizione) {
        this.descrizione = descrizione;
    }

    String descrizione() {
        return descrizione;
    }
}
