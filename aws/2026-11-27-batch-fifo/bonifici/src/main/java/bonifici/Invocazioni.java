package bonifici;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

final class Invocazioni {

    private static final String PROVA = "prova";
    private static final String ID = "id";
    private static final String MESSAGGI = "messaggi";
    private static final String CONTI = "conti";
    private static final String BYTE_EVENTO = "byteEvento";
    private static final String RICEVUTI_DI_NUOVO = "ricevutiDiNuovo";
    private static final String INIZIO = "inizio";
    private static final String FINE = "fine";

    private final DynamoDbClient dynamo;
    private final String tabella;

    Invocazioni(DynamoDbClient dynamo, String tabella) {
        this.dynamo = dynamo;
        this.tabella = tabella;
    }

    void registra(Invocazione invocazione) {
        dynamo.putItem(richiesta -> richiesta
                .tableName(tabella)
                .item(Map.of(
                        PROVA, AttributeValue.fromS(invocazione.prova()),
                        ID, AttributeValue.fromS(invocazione.id()),
                        MESSAGGI, numero(invocazione.messaggi()),
                        CONTI, numero(invocazione.conti()),
                        BYTE_EVENTO, numero(invocazione.byteEvento()),
                        RICEVUTI_DI_NUOVO, numero(invocazione.ricevutiDiNuovo()),
                        INIZIO, numero(invocazione.inizio()),
                        FINE, numero(invocazione.fine()))));
    }

    List<Invocazione> tutte(String prova) {
        return dynamo.queryPaginator(richiesta -> richiesta
                        .tableName(tabella)
                        .keyConditionExpression("prova = :prova")
                        .expressionAttributeValues(Map.of(":prova", AttributeValue.fromS(prova)))
                        .consistentRead(true))
                .items()
                .stream()
                .map(item -> new Invocazione(prova, item.get(ID).s(),
                        intero(item, MESSAGGI), intero(item, CONTI), intero(item, BYTE_EVENTO),
                        intero(item, RICEVUTI_DI_NUOVO),
                        Long.parseLong(item.get(INIZIO).n()), Long.parseLong(item.get(FINE).n())))
                .toList();
    }

    private static int intero(Map<String, AttributeValue> item, String attributo) {
        return Integer.parseInt(item.get(attributo).n());
    }

    private static AttributeValue numero(long valore) {
        return AttributeValue.fromN(Long.toString(valore));
    }
}
