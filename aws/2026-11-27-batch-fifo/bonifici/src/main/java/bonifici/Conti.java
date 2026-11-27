package bonifici;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

final class Conti {

    private static final String PROVA = "prova";
    private static final String CONTO = "conto";
    private static final String SALDO = "saldo";
    private static final String ULTIMO = "ultimo";
    private static final String REGISTRATI = "registrati";
    private static final String RIFIUTATI = "rifiutati";
    private static final String SCARTATI = "scartati";

    private final DynamoDbClient dynamo;
    private final String tabella;

    Conti(DynamoDbClient dynamo, String tabella) {
        this.dynamo = dynamo;
        this.tabella = tabella;
    }

    Stato leggi(String prova, int conto) {
        var item = dynamo.getItem(richiesta -> richiesta
                .tableName(tabella)
                .key(chiave(prova, conto))
                .consistentRead(true)).item();
        return item.isEmpty() ? Stato.NUOVO : stato(item);
    }

    void salva(String prova, int conto, Stato prima, Stato dopo) {
        var item = new HashMap<>(chiave(prova, conto));
        item.put(SALDO, numero(dopo.saldo()));
        item.put(ULTIMO, numero(dopo.ultimo()));
        item.put(REGISTRATI, numero(dopo.registrati()));
        item.put(RIFIUTATI, numero(dopo.rifiutati()));
        item.put(SCARTATI, numero(dopo.scartati()));
        dynamo.putItem(richiesta -> richiesta
                .tableName(tabella)
                .item(item)
                .conditionExpression("attribute_not_exists(prova) OR ultimo = :ultimo")
                .expressionAttributeValues(Map.of(":ultimo", numero(prima.ultimo()))));
    }

    SortedMap<Integer, Stato> tutti(String prova) {
        var conti = new TreeMap<Integer, Stato>();
        dynamo.queryPaginator(richiesta -> richiesta
                        .tableName(tabella)
                        .keyConditionExpression("prova = :prova")
                        .expressionAttributeValues(Map.of(":prova", AttributeValue.fromS(prova)))
                        .consistentRead(true))
                .items()
                .forEach(item -> conti.put(intero(item, CONTO), stato(item)));
        return conti;
    }

    private static Stato stato(Map<String, AttributeValue> item) {
        return new Stato(Long.parseLong(item.get(SALDO).n()), intero(item, ULTIMO),
                intero(item, REGISTRATI), intero(item, RIFIUTATI), intero(item, SCARTATI));
    }

    private static Map<String, AttributeValue> chiave(String prova, int conto) {
        return Map.of(PROVA, AttributeValue.fromS(prova), CONTO, numero(conto));
    }

    private static int intero(Map<String, AttributeValue> item, String attributo) {
        return Integer.parseInt(item.get(attributo).n());
    }

    private static AttributeValue numero(long valore) {
        return AttributeValue.fromN(Long.toString(valore));
    }
}
