package banca;

import static banca.Uscita.OUT;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public final class CaricaScadenze {

    private static final int MOVIMENTI = 1000;
    private static final int PER_RICHIESTA = 25;
    private static final Duration GIA_SCADUTI_DA = Duration.ofMinutes(1);
    private static final Duration PAUSA_SE_AVANZANO = Duration.ofMillis(200);

    private CaricaScadenze() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 1) {
            throw new IllegalArgumentException("uso: CaricaScadenze nome-tabella");
        }
        var tabella = args[0];
        var scadenza = Instant.now().minus(GIA_SCADUTI_DA).getEpochSecond();
        var scritture = IntStream.rangeClosed(1, MOVIMENTI)
                .mapToObj(numero -> scrittura(numero, scadenza))
                .toList();

        try (var dynamo = DynamoDbClient.create()) {
            for (var da = 0; da < scritture.size(); da += PER_RICHIESTA) {
                var lotto = scritture.subList(da, Math.min(da + PER_RICHIESTA, scritture.size()));
                scrivi(dynamo, tabella, lotto);
            }
        }
        OUT.printf("scritti %d movimenti già scaduti: scade = %d (%s)%n",
                MOVIMENTI, scadenza, Instant.ofEpochSecond(scadenza));
    }

    private static WriteRequest scrittura(int numero, long scadenza) {
        return WriteRequest.builder()
                .putRequest(put -> put.item(Map.of(
                        "id", AttributeValue.fromS("M%04d".formatted(numero)),
                        "conto", AttributeValue.fromS("C001"),
                        "importo", AttributeValue.fromN("10"),
                        "scade", AttributeValue.fromN(Long.toString(scadenza)))))
                .build();
    }

    private static void scrivi(DynamoDbClient dynamo, String tabella, List<WriteRequest> lotto)
            throws InterruptedException {
        var daScrivere = lotto;
        while (!daScrivere.isEmpty()) {
            var richieste = Map.of(tabella, daScrivere);
            var avanzati = dynamo.batchWriteItem(batch -> batch.requestItems(richieste))
                    .unprocessedItems()
                    .getOrDefault(tabella, List.of());
            if (!avanzati.isEmpty()) {
                Thread.sleep(PAUSA_SE_AVANZANO);
            }
            daScrivere = avanzati;
        }
    }
}
