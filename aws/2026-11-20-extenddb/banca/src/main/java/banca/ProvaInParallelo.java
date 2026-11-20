package banca;

import static banca.Uscita.OUT;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public final class ProvaInParallelo {

    private static final int BONIFICI = 100;
    private static final BigDecimal IMPORTO = new BigDecimal("100");
    private static final BigDecimal SALDO_INIZIALE = IMPORTO.multiply(BigDecimal.valueOf(BONIFICI));
    private static final String PARTENZA = "C010";
    private static final String ARRIVO = "C011";
    private static final long PAUSA_MASSIMA_MS = 100;

    private final Conti conti;
    private final int tentativiMassimi;
    private final Map<Esito, LongAdder> esiti = new ConcurrentHashMap<>();
    private final LongAdder tentativi = new LongAdder();
    private final CountDownLatch via = new CountDownLatch(1);

    private ProvaInParallelo(Conti conti, int tentativiMassimi) {
        this.conti = conti;
        this.tentativiMassimi = tentativiMassimi;
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "uso: ProvaInParallelo nome-tabella tentativi-massimi");
        }
        var tentativiMassimi = Integer.parseInt(args[1]);
        if (tentativiMassimi < 1) {
            throw new IllegalArgumentException("tentativi-massimi deve essere almeno 1");
        }
        try (var dynamo = DynamoDbClient.create()) {
            new ProvaInParallelo(new Conti(dynamo, args[0]), tentativiMassimi).avvia();
        }
    }

    private void avvia() {
        conti.apri(PARTENZA, SALDO_INIZIALE);
        conti.apri(ARRIVO, BigDecimal.ZERO);
        var corsa = System.currentTimeMillis();

        var inizio = System.nanoTime();
        try (var esecutore = Executors.newFixedThreadPool(BONIFICI)) {
            for (var numero = 1; numero <= BONIFICI; numero++) {
                var bonifico = new Bonifico(corsa + "-" + numero, PARTENZA, ARRIVO, IMPORTO);
                esecutore.execute(() -> conta(esegui(bonifico)));
            }
            via.countDown();
        }
        var tempo = Duration.ofNanos(System.nanoTime() - inizio);

        var rimasti = conti.saldo(PARTENZA);
        var arrivati = conti.saldo(ARRIVO);
        stampa(tempo, rimasti, arrivati);
        controlla(rimasti, arrivati);
    }

    private Esito esegui(Bonifico bonifico) {
        aspettaLaPartenza();
        for (var tentativo = 1; ; tentativo++) {
            tentativi.increment();
            try {
                conti.esegui(bonifico);
                return Esito.ESEGUITO;
            } catch (TransactionCanceledException e) {
                var rifiuto = Conti.rifiuto(e).orElseThrow(() -> e);
                if (rifiuto != Esito.CONFLITTO || tentativo == tentativiMassimi) {
                    return rifiuto;
                }
                pausa();
            }
        }
    }

    private void conta(Esito esito) {
        esiti.computeIfAbsent(esito, chiave -> new LongAdder()).increment();
    }

    private long eseguiti() {
        var quanti = esiti.get(Esito.ESEGUITO);
        return quanti == null ? 0 : quanti.sum();
    }

    private void aspettaLaPartenza() {
        try {
            via.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrotto prima della partenza", e);
        }
    }

    private static void pausa() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, PAUSA_MASSIMA_MS + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrotto durante la pausa", e);
        }
    }

    private void stampa(Duration tempo, BigDecimal rimasti, BigDecimal arrivati) {
        var limite = tentativiMassimi == 1 ? "un tentativo ciascuno"
                : "fino a " + tentativiMassimi + " tentativi ciascuno";
        OUT.printf("%d bonifici da %s euro da %s a %s, partiti insieme, %s%n",
                BONIFICI, IMPORTO, PARTENZA, ARRIVO, limite);
        OUT.printf("%s: %d%n", Esito.ESEGUITO.descrizione(), eseguiti());
        for (var esito : Esito.values()) {
            var quanti = esiti.get(esito);
            if (esito != Esito.ESEGUITO && quanti != null) {
                OUT.printf("%s: %d%n", esito.descrizione(), quanti.sum());
            }
        }
        OUT.printf("tentativi in tutto: %d%n", tentativi.sum());
        OUT.printf("saldi finali: %s %s, %s %s%n", PARTENZA, rimasti.toPlainString(),
                ARRIVO, arrivati.toPlainString());
        OUT.printf("tempo: %d,%03d secondi%n", tempo.toSeconds(), tempo.toMillisPart());
    }

    private void controlla(BigDecimal rimasti, BigDecimal arrivati) {
        var attesi = IMPORTO.multiply(BigDecimal.valueOf(eseguiti()));
        var totale = rimasti.add(arrivati);
        if (totale.compareTo(SALDO_INIZIALE) != 0 || arrivati.compareTo(attesi) != 0) {
            throw new IllegalStateException("I saldi non tornano con i bonifici eseguiti");
        }
    }
}
