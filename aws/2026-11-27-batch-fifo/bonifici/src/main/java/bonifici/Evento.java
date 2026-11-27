package bonifici;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

record Evento(@JsonProperty("Records") List<Messaggio> messaggi) {

    record Messaggio(String messageId, String body, Map<String, String> attributes) {

        int ricezioni() {
            return Integer.parseInt(attributes.get("ApproximateReceiveCount"));
        }
    }
}
