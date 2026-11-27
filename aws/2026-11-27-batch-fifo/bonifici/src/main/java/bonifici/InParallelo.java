package bonifici;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class InParallelo {

    private InParallelo() {
    }

    static <T> List<T> esegui(int fili, List<Callable<T>> lavori) {
        try (var esecutore = Executors.newFixedThreadPool(fili)) {
            var futuri = lavori.stream().map(esecutore::submit).toList();
            var risultati = new ArrayList<T>(futuri.size());
            for (var futuro : futuri) {
                risultati.add(risultato(futuro));
            }
            return risultati;
        }
    }

    private static <T> T risultato(Future<T> futuro) {
        try {
            return futuro.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
