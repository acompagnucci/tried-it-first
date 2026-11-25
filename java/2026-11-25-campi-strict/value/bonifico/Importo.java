package bonifico;

abstract value class Importo {

    static final long LIMITE_IN_CENTESIMI = 5_000_000L;

    protected Importo() {
        super();
        if (centesimi() > LIMITE_IN_CENTESIMI) {
            throw new ImportoOltreIlLimite(centesimi(), valuta());
        }
    }

    abstract long centesimi();

    abstract String valuta();
}
