package bonifico;

value class ImportoInValuta extends Importo {

    private final long centesimi;
    private final String valuta;

    ImportoInValuta(long centesimi, String valuta) {
        this.centesimi = centesimi;
        this.valuta = valuta;
    }

    @Override
    long centesimi() {
        return centesimi;
    }

    @Override
    String valuta() {
        return valuta;
    }
}
