package bonifico;

final class ImportoOltreIlLimite extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ImportoOltreIlLimite(long centesimi, String valuta) {
        super(Importi.formatta(centesimi, valuta) + " oltre il limite di "
                + Importi.formatta(Importo.LIMITE_IN_CENTESIMI) + " per bonifico");
    }
}
