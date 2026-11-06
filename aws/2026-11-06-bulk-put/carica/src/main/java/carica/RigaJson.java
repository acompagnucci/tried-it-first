package carica;

import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

final class RigaJson {

    private RigaJson() {
    }

    static String di(Riga riga) {
        var json = new StringBuilder("{\"Item\":").append(attributi(riga.item()));
        var condizione = riga.condizione();
        if (condizione != Condizione.NESSUNA) {
            json.append(",\"ConditionExpression\":\"").append(condizione.espressione()).append('"');
            if (!condizione.nomi().isEmpty()) {
                json.append(",\"ExpressionAttributeNames\":").append(nomi(condizione.nomi()));
            }
            Map<String, AttributeValue> valori = condizione.valori(riga.item());
            if (!valori.isEmpty()) {
                json.append(",\"ExpressionAttributeValues\":").append(attributi(valori));
            }
        }
        return json.append('}').toString();
    }

    private static String attributi(Map<String, AttributeValue> attributi) {
        return attributi.entrySet().stream()
                .map(attributo -> "\"%s\":%s".formatted(attributo.getKey(), valore(attributo.getValue())))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String valore(AttributeValue valore) {
        return switch (valore.type()) {
            case S -> "{\"S\":\"%s\"}".formatted(valore.s());
            case N -> "{\"N\":\"%s\"}".formatted(valore.n());
            default -> throw new IllegalArgumentException("Tipo non gestito: " + valore.type());
        };
    }

    private static String nomi(Map<String, String> nomi) {
        return nomi.entrySet().stream()
                .map(nome -> "\"%s\":\"%s\"".formatted(nome.getKey(), nome.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }
}
