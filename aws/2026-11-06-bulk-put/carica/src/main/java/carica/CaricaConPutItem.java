package carica;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public final class CaricaConPutItem {

    private static final int IN_PARALLELO = 32;
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private CaricaConPutItem() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("uso: CaricaConPutItem nome-tabella");
        }
        String tabella = args[0];
        List<Riga> righe = Righe.delGiorno();
        var scritte = new LongAdder();
        var scartate = new LongAdder();

        long inizio = System.nanoTime();
        try (var dynamo = DynamoDbClient.create();
             var esecutore = Executors.newFixedThreadPool(IN_PARALLELO)) {
            for (Riga riga : righe) {
                esecutore.execute(() -> {
                    try {
                        dynamo.putItem(richiesta(tabella, riga));
                        scritte.increment();
                    } catch (ConditionalCheckFailedException e) {
                        scartate.increment();
                    }
                });
            }
        }
        var tempo = Duration.ofNanos(System.nanoTime() - inizio);

        OUT.printf("righe: %d%n", righe.size());
        OUT.printf("scritte: %d%n", scritte.sum());
        OUT.printf("scartate dalla condizione: %d%n", scartate.sum());
        OUT.printf("tempo: %d,%03d secondi%n", tempo.toSeconds(), tempo.toMillisPart());
        if (scritte.sum() + scartate.sum() != righe.size()) {
            throw new IllegalStateException("Alcune righe sono finite in errore: guardate le eccezioni sopra.");
        }
    }

    private static PutItemRequest richiesta(String tabella, Riga riga) {
        var condizione = riga.condizione();
        var richiesta = PutItemRequest.builder().tableName(tabella).item(riga.item());
        if (condizione != Condizione.NESSUNA) {
            richiesta.conditionExpression(condizione.espressione());
            if (!condizione.nomi().isEmpty()) {
                richiesta.expressionAttributeNames(condizione.nomi());
            }
            var valori = condizione.valori(riga.item());
            if (!valori.isEmpty()) {
                richiesta.expressionAttributeValues(valori);
            }
        }
        return richiesta.build();
    }
}
