package carica;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

record Riga(Map<String, AttributeValue> item, Condizione condizione) {
}
