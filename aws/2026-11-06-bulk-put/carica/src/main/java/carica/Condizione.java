package carica;

import java.util.Map;
import java.util.function.Function;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

enum Condizione {
    NESSUNA(null, Map.of(), item -> Map.of()),
    SOLO_SE_NUOVO("attribute_not_exists(id)", Map.of(), item -> Map.of()),
    SOLO_SE_PIU_NUOVO("attribute_not_exists(#v) OR #v < :nuova", Map.of("#v", "versione"),
            item -> Map.of(":nuova", item.get("versione")));

    private final String espressione;
    private final Map<String, String> nomi;
    private final Function<Map<String, AttributeValue>, Map<String, AttributeValue>> valori;

    Condizione(String espressione, Map<String, String> nomi,
               Function<Map<String, AttributeValue>, Map<String, AttributeValue>> valori) {
        this.espressione = espressione;
        this.nomi = nomi;
        this.valori = valori;
    }

    String espressione() {
        return espressione;
    }

    Map<String, String> nomi() {
        return nomi;
    }

    Map<String, AttributeValue> valori(Map<String, AttributeValue> item) {
        return valori.apply(item);
    }
}
