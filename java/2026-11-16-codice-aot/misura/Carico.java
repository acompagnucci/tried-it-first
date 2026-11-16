package misura;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

final class Carico {

    static final int LOTTI = 20;
    static final int RICHIESTE_PER_LOTTO = 500;
    static final int HTTP_OK = 200;

    private static final URI INDIRIZZO = URI.create("http://localhost:8080");
    private static final long SEME = 20261116L;
    private static final List<String> CONTI = List.of(
            "IT51X0000000000000000000001", "IT24X0000000000000000000002", "IT94X0000000000000000000003",
            "IT67X0000000000000000000004", "IT40X0000000000000000000005", "IT13X0000000000000000000006",
            "IT83X0000000000000000000007", "IT56X0000000000000000000008", "IT29X0000000000000000000009",
            "IT02X0000000000000000000010");
    private static final String IBAN_CON_CIFRE_DI_CONTROLLO_SBAGLIATE = "IT00X0000000000000000000001";
    private static final int PERCENTUALE_MOVIMENTI = 10;
    private static final int PERCENTUALE_IBAN_SBAGLIATI = 3;
    private static final int CENTESIMI_MASSIMI = 50_000;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final Random caso = new Random(SEME);
    private final Map<Integer, Integer> risposte = new TreeMap<>();

    int movimentiDelPrimoConto() throws IOException, InterruptedException {
        return client.send(movimenti(CONTI.getFirst()), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    Duration lotto() throws IOException, InterruptedException {
        var inizio = System.nanoTime();
        for (var i = 0; i < RICHIESTE_PER_LOTTO; i++) {
            var stato = client.send(prossimaRichiesta(), HttpResponse.BodyHandlers.discarding()).statusCode();
            risposte.merge(stato, 1, Integer::sum);
        }
        return Duration.ofNanos(System.nanoTime() - inizio);
    }

    Map<Integer, Integer> risposte() {
        return new TreeMap<>(risposte);
    }

    private HttpRequest prossimaRichiesta() {
        var tiro = caso.nextInt(100);
        if (tiro < PERCENTUALE_MOVIMENTI) {
            return movimenti(contoACaso());
        }
        var ordinante = contoACaso();
        var beneficiario = contoDiversoDa(ordinante);
        var importo = BigDecimal.valueOf(1L + caso.nextInt(CENTESIMI_MASSIMI), 2);
        return tiro < PERCENTUALE_MOVIMENTI + PERCENTUALE_IBAN_SBAGLIATI
                ? bonifico(IBAN_CON_CIFRE_DI_CONTROLLO_SBAGLIATE, beneficiario, importo)
                : bonifico(ordinante, beneficiario, importo);
    }

    private String contoACaso() {
        return CONTI.get(caso.nextInt(CONTI.size()));
    }

    private String contoDiversoDa(String iban) {
        var altri = CONTI.stream().filter(conto -> !conto.equals(iban)).toList();
        return altri.get(caso.nextInt(altri.size()));
    }

    private static HttpRequest movimenti(String iban) {
        return HttpRequest.newBuilder(INDIRIZZO.resolve("/conti/" + iban + "/movimenti")).GET().build();
    }

    private static HttpRequest bonifico(String ordinante, String beneficiario, BigDecimal importo) {
        var json = """
                {"ordinante":"%s","beneficiario":"%s","importo":%s,"causale":"rata di novembre"}"""
                .formatted(ordinante, beneficiario, importo.toPlainString());
        return HttpRequest.newBuilder(INDIRIZZO.resolve("/bonifici"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
