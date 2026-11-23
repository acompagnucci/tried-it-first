package bonifico;

import java.io.Serial;

final class DatabaseNonDisponibile extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    DatabaseNonDisponibile(String messaggio) {
        super(messaggio);
    }

    DatabaseNonDisponibile(String messaggio, Throwable causa) {
        super(messaggio, causa);
    }
}
