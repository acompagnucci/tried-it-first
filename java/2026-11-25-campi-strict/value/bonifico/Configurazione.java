package bonifico;

value class Configurazione {

    private static final String ABI_DI_PROVA = "00001";

    static final String INTESTAZIONE_RICEVUTA = "ABI " + Banca.ABI;

    private Configurazione() {
    }

    static String abi() {
        return System.getProperty("banca.abi", ABI_DI_PROVA);
    }
}
