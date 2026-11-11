package bonifico;

class BonificoRifiutato extends RuntimeException {

    private static final long serialVersionUID = 1L;

    BonificoRifiutato(String motivo) {
        super(motivo);
    }
}
