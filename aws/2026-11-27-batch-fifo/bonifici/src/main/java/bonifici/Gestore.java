package bonifici;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public abstract class Gestore implements RequestStreamHandler {

    private static final int CONTI_IN_PARALLELO = 32;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Controlli controlli = Controlli.daAmbiente();
    private final Conti conti;
    private final Invocazioni invocazioni;

    protected Gestore() {
        var dynamo = DynamoDbClient.builder()
                .httpClientBuilder(Apache5HttpClient.builder().maxConnections(CONTI_IN_PARALLELO))
                .build();
        conti = new Conti(dynamo, System.getenv("CONTI"));
        invocazioni = new Invocazioni(dynamo, System.getenv("INVOCAZIONI"));
    }

    @Override
    public final void handleRequest(InputStream ingresso, OutputStream uscita, Context contesto)
            throws IOException {
        var inizio = System.currentTimeMillis();
        var evento = ingresso.readAllBytes();
        var ricevuti = JSON.readValue(evento, Evento.class).messaggi().stream()
                .map(Gestore::ricevuto)
                .toList();
        var perConto = ricevuti.stream().collect(
                groupingBy(ricevuto -> ricevuto.bonifico().conto(), LinkedHashMap::new, toList()));
        try {
            var lavori = perConto.values().stream()
                    .<Callable<List<String>>>map(delConto -> () -> elaboraConto(delConto))
                    .toList();
            var daRiprovare = InParallelo.esegui(CONTI_IN_PARALLELO, lavori).stream()
                    .flatMap(List::stream)
                    .toList();
            JSON.writeValue(uscita, Risposta.di(daRiprovare));
        } finally {
            var ricevutiDiNuovo = (int) ricevuti.stream().filter(Ricevuto::ricevutoDiNuovo).count();
            invocazioni.registra(new Invocazione(ricevuti.getFirst().bonifico().prova(),
                    contesto.getAwsRequestId(), ricevuti.size(), perConto.size(), evento.length,
                    ricevutiDiNuovo, inizio, System.currentTimeMillis()));
        }
    }

    protected abstract Stato applica(Stato prima, List<Ricevuto> delConto,
                                     List<String> daRiprovare);

    protected final void verifica(Ricevuto ricevuto) {
        controlli.verifica(ricevuto);
    }

    private List<String> elaboraConto(List<Ricevuto> delConto) {
        var bonifico = delConto.getFirst().bonifico();
        var prima = conti.leggi(bonifico.prova(), bonifico.conto());
        var daRiprovare = new ArrayList<String>();
        var dopo = applica(prima, delConto, daRiprovare);
        if (!dopo.equals(prima)) {
            conti.salva(bonifico.prova(), bonifico.conto(), prima, dopo);
        }
        return daRiprovare;
    }

    private static Ricevuto ricevuto(Evento.Messaggio messaggio) {
        try {
            return new Ricevuto(messaggio.messageId(),
                    JSON.readValue(messaggio.body(), Bonifico.class), messaggio.ricezioni());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
