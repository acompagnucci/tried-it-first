package bonifico;

import java.time.Duration;
import java.util.function.Supplier;

record Retry(int tentativi, Duration pausa) {

    Retry {
        if (tentativi < 1) {
            throw new IllegalArgumentException("serve almeno un tentativo, non " + tentativi);
        }
    }

    <T> T esegui(Supplier<T> caricamento) {
        for (var tentativo = 1; ; tentativo++) {
            try {
                return caricamento.get();
            } catch (DatabaseNonDisponibile e) {
                if (tentativo == tentativi) {
                    throw e;
                }
                aspettaPrimaDiRiprovare(e);
            }
        }
    }

    private void aspettaPrimaDiRiprovare(DatabaseNonDisponibile errore) {
        try {
            Thread.sleep(pausa);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw errore;
        }
    }
}
