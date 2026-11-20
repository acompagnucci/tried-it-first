package banca;

import static software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure.ALL_OLD;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

final class Conti {

    private static final String CONTO = "conto";
    private static final String SALDO = "saldo";
    private static final String IMPORTO = ":importo";
    private static final String CODICE_CONFLITTO = "TransactionConflict";
    private static final String CODICE_CONDIZIONE_NON_RISPETTATA = "ConditionalCheckFailed";
    private static final int PRELIEVO = 0;
    private static final int VERSAMENTO = 1;

    private final DynamoDbClient dynamo;
    private final String tabella;

    Conti(DynamoDbClient dynamo, String tabella) {
        this.dynamo = dynamo;
        this.tabella = tabella;
    }

    void apri(String conto, BigDecimal saldo) {
        dynamo.putItem(richiesta -> richiesta
                .tableName(tabella)
                .item(Map.of(CONTO, testo(conto), SALDO, numero(saldo))));
    }

    BigDecimal saldo(String conto) {
        var item = dynamo.getItem(richiesta -> richiesta
                .tableName(tabella)
                .key(chiave(conto))
                .consistentRead(true)).item();
        return new BigDecimal(item.get(SALDO).n());
    }

    void esegui(Bonifico bonifico) {
        var importo = bonifico.importo();
        var movimenti = new TransactWriteItem[2];
        movimenti[PRELIEVO] = aggiornamento(bonifico.da(),
                "SET saldo = saldo - :importo", "saldo >= :importo", importo);
        movimenti[VERSAMENTO] = aggiornamento(bonifico.a(),
                "SET saldo = saldo + :importo", "attribute_exists(conto)", importo);
        dynamo.transactWriteItems(richiesta -> richiesta
                .clientRequestToken(bonifico.id())
                .transactItems(movimenti));
    }

    static Optional<Esito> rifiuto(TransactionCanceledException e) {
        var motivi = e.cancellationReasons();
        if (motivi.stream().anyMatch(motivo -> CODICE_CONFLITTO.equals(motivo.code()))) {
            return Optional.of(Esito.CONFLITTO);
        }
        if (nonRispettata(motivi, PRELIEVO)) {
            return Optional.of(Esito.SALDO_INSUFFICIENTE);
        }
        if (nonRispettata(motivi, VERSAMENTO)) {
            return Optional.of(Esito.CONTO_DI_ARRIVO_INESISTENTE);
        }
        return Optional.empty();
    }

    private static boolean nonRispettata(List<CancellationReason> motivi, int movimento) {
        return motivi.size() > movimento
                && CODICE_CONDIZIONE_NON_RISPETTATA.equals(motivi.get(movimento).code());
    }

    private TransactWriteItem aggiornamento(String conto, String espressione, String condizione,
                                            BigDecimal importo) {
        return TransactWriteItem.builder()
                .update(aggiorna -> aggiorna
                        .tableName(tabella)
                        .key(chiave(conto))
                        .updateExpression(espressione)
                        .conditionExpression(condizione)
                        .expressionAttributeValues(Map.of(IMPORTO, numero(importo)))
                        .returnValuesOnConditionCheckFailure(ALL_OLD))
                .build();
    }

    private static Map<String, AttributeValue> chiave(String conto) {
        return Map.of(CONTO, testo(conto));
    }

    private static AttributeValue testo(String valore) {
        return AttributeValue.fromS(valore);
    }

    private static AttributeValue numero(BigDecimal valore) {
        return AttributeValue.fromN(valore.toPlainString());
    }
}
