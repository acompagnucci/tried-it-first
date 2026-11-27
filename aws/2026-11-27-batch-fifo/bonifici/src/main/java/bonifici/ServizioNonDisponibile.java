package bonifici;

final class ServizioNonDisponibile extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ServizioNonDisponibile(String messaggio) {
        super(messaggio);
    }
}
