package giro;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;

public class GiroSqs implements RequestHandler<Map<String, Object>, Void> {

    private static final String CODA = System.getenv("CODA");
    private static final int GIRI_MASSIMI = 50;

    private final SqsClient sqs = SqsClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();

    @Override
    public Void handleRequest(Map<String, Object> evento, Context context) {
        for (Object messaggio : (List<?>) evento.get("Records")) {
            int giro = Integer.parseInt((String) ((Map<?, ?>) messaggio).get("body"));
            Registro.giro(context, "sqs", giro);

            if (giro < GIRI_MASSIMI) {
                sqs.sendMessage(r -> r.queueUrl(CODA).messageBody(String.valueOf(giro + 1)));
            }
        }
        return null;
    }
}
