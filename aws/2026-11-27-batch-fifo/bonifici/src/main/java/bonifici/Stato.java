package bonifici;

record Stato(long saldo, int ultimo, int registrati, int rifiutati, int scartati) {

    static final Stato NUOVO = new Stato(0, 0, 0, 0, 0);

    Stato applica(Bonifico bonifico) {
        var progressivo = bonifico.progressivo();
        if (progressivo <= ultimo) {
            return new Stato(saldo, ultimo, registrati, rifiutati, scartati + 1);
        }
        var nuovoSaldo = saldo + bonifico.centesimi();
        if (nuovoSaldo < 0) {
            return new Stato(saldo, progressivo, registrati, rifiutati + 1, scartati);
        }
        return new Stato(nuovoSaldo, progressivo, registrati + 1, rifiutati, scartati);
    }
}
