package giro;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

public class Giro implements RequestHandler<Map<String, Object>, Void> {

    private static final String BUS = System.getenv("BUS");
    private static final int GIRI_MASSIMI = 50;
    private static final String DETTAGLIO = """
            {"prova":"%s","giro":%d}""";

    private final EventBridgeClient eventBridge = EventBridgeClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();

    @Override
    public Void handleRequest(Map<String, Object> evento, Context context) {
        var dettaglio = (Map<?, ?>) evento.get("detail");
        var prova = (String) dettaglio.get("prova");
        int giro = ((Number) dettaglio.get("giro")).intValue();
        Registro.giro(context, prova, giro);

        if (giro < GIRI_MASSIMI) {
            pubblica(prova, giro + 1);
        }
        return null;
    }

    private void pubblica(String prova, int giro) {
        var prossimo = PutEventsRequestEntry.builder()
                .eventBusName(BUS)
                .source("tried-it-first")
                .detailType("giro")
                .detail(DETTAGLIO.formatted(prova, giro))
                .build();
        PutEventsResponse risposta = eventBridge.putEvents(r -> r.entries(prossimo));
        if (risposta.failedEntryCount() > 0) {
            throw new IllegalStateException(risposta.entries().get(0).errorMessage());
        }
    }
}
