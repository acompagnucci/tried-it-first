package misura;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

final class Carico {

    static final int RICHIESTE_AL_SECONDO_ALL_INIZIO = 20;
    static final int RICHIESTE_AL_SECONDO_A_REGIME = 600;
    static final int SECONDI_DI_RAMPA = 10;
    static final int SECONDI_A_REGIME = 120;
    static final int SECONDI = SECONDI_DI_RAMPA + SECONDI_A_REGIME;
    static final List<String> PAGINE =
            List.of("/owners/1", "/owners?lastName=Davis", "/vets.html", "/owners/5", "/owners?lastName=");

    private static final int HTTP_OK = 200;
    private static final long NANO_IN_UN_SECONDO = Duration.ofSeconds(1).toNanos();

    record Secondo(int richieste, Duration p50, Duration p99) {
    }

    record Esito(List<Secondo> secondi, int errori) {
    }

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    Esito esegui() {
        var durate = IntStream.range(0, SECONDI)
                .<Queue<Long>>mapToObj(secondo -> new ConcurrentLinkedQueue<>())
                .toList();
        var errori = new AtomicInteger();
        var inizio = System.nanoTime();
        try (var invii = Executors.newVirtualThreadPerTaskExecutor()) {
            var istante = 0.0;
            var inviate = 0;
            while (istante < SECONDI) {
                var previsto = inizio + (long) (istante * NANO_IN_UN_SECONDO);
                aspettaFinoA(previsto);
                var richiesta = HttpRequest.newBuilder(paginaNumero(inviate)).build();
                inviate++;
                var duratePerQuestoSecondo = durate.get((int) istante);
                invii.submit(() -> {
                    if (!rispondeOk(richiesta)) {
                        errori.incrementAndGet();
                    }
                    duratePerQuestoSecondo.add(System.nanoTime() - previsto);
                });
                istante += 1.0 / richiesteAlSecondo(istante);
            }
        }
        return new Esito(durate.stream().map(Carico::riassumi).toList(), errori.get());
    }

    private boolean rispondeOk(HttpRequest richiesta) {
        try {
            return client.send(richiesta, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static URI paginaNumero(int numero) {
        return PetClinic.INDIRIZZO.resolve(PAGINE.get(numero % PAGINE.size()));
    }

    private static double richiesteAlSecondo(double istante) {
        if (istante >= SECONDI_DI_RAMPA) {
            return RICHIESTE_AL_SECONDO_A_REGIME;
        }
        var aumento = RICHIESTE_AL_SECONDO_A_REGIME - RICHIESTE_AL_SECONDO_ALL_INIZIO;
        return RICHIESTE_AL_SECONDO_ALL_INIZIO + aumento * istante / SECONDI_DI_RAMPA;
    }

    private static void aspettaFinoA(long nanoTime) {
        for (var attesa = nanoTime - System.nanoTime(); attesa > 0; attesa = nanoTime - System.nanoTime()) {
            LockSupport.parkNanos(attesa);
        }
    }

    private static Secondo riassumi(Queue<Long> durate) {
        var ordinate = new ArrayList<>(durate);
        ordinate.sort(null);
        return new Secondo(ordinate.size(), percentile(ordinate, 50), percentile(ordinate, 99));
    }

    private static Duration percentile(List<Long> ordinate, int percentile) {
        var posizione = (int) Math.ceil(ordinate.size() * percentile / 100.0) - 1;
        return Duration.ofNanos(ordinate.get(posizione));
    }
}
