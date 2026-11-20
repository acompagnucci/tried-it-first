package banca;

import static banca.Uscita.OUT;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public final class ProvaBonifici {

    private static final String PARTENZA = "C001";
    private static final String ARRIVO = "C002";
    private static final String INESISTENTE = "C999";
    private static final BigDecimal SALDO_INIZIALE = new BigDecimal("1000");

    private final Conti conti;
    private final String corsa;

    private ProvaBonifici(Conti conti, String corsa) {
        this.conti = conti;
        this.corsa = corsa;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("uso: ProvaBonifici nome-tabella");
        }
        try (var dynamo = DynamoDbClient.create()) {
            var corsa = Long.toString(System.currentTimeMillis());
            new ProvaBonifici(new Conti(dynamo, args[0]), corsa).avvia();
        }
    }

    private void avvia() {
        conti.apri(PARTENZA, SALDO_INIZIALE);
        conti.apri(ARRIVO, BigDecimal.ZERO);
        OUT.printf("Conti aperti: %s con %s euro, %s con %s%n", PARTENZA, SALDO_INIZIALE, ARRIVO,
                BigDecimal.ZERO);

        var primo = bonifico(1, ARRIVO, "300");
        prova(1, descrizione(primo), primo);
        var troppoAlto = bonifico(2, ARRIVO, "5000");
        prova(2, descrizione(troppoAlto), troppoAlto);
        var versoIlNulla = bonifico(3, INESISTENTE, "100");
        prova(3, descrizione(versoIlNulla) + ", che non esiste", versoIlNulla);
        prova(4, "Di nuovo il bonifico 1, con lo stesso id", primo);
        var cambiato = new Bonifico(primo.id(), PARTENZA, ARRIVO, new BigDecimal("400"));
        prova(5, "Lo stesso id del bonifico 1, ma con " + cambiato.importo() + " euro invece di "
                + primo.importo(), cambiato);
    }

    private Bonifico bonifico(int numero, String arrivo, String importo) {
        return new Bonifico(corsa + "-" + numero, PARTENZA, arrivo, new BigDecimal(importo));
    }

    private static String descrizione(Bonifico bonifico) {
        return "Bonifico di %s euro da %s a %s"
                .formatted(bonifico.importo(), bonifico.da(), bonifico.a());
    }

    private void prova(int passo, String descrizione, Bonifico bonifico) {
        OUT.printf("%n%d. %s%n", passo, descrizione);
        try {
            conti.esegui(bonifico);
            OUT.println("   eseguito");
        } catch (TransactionCanceledException e) {
            stampa(e);
            var motivi = e.cancellationReasons();
            for (var i = 0; i < motivi.size(); i++) {
                OUT.printf("   motivo %d: %s%n", i + 1, motivo(motivi.get(i)));
            }
        } catch (DynamoDbException e) {
            stampa(e);
        }
        OUT.printf("   saldi: %s %s, %s %s%n", PARTENZA, conti.saldo(PARTENZA).toPlainString(),
                ARRIVO, conti.saldo(ARRIVO).toPlainString());
    }

    private static void stampa(DynamoDbException e) {
        var dettagli = e.awsErrorDetails();
        OUT.printf("   rifiutato: %s, HTTP %d%n", dettagli.errorCode(), e.statusCode());
        OUT.printf("   messaggio: %s%n", dettagli.errorMessage());
    }

    private static String motivo(CancellationReason motivo) {
        var testo = new StringBuilder(motivo.code());
        if (motivo.message() != null) {
            testo.append(" (").append(motivo.message()).append(')');
        }
        if (motivo.hasItem()) {
            testo.append(", item com'era: ").append(attributi(motivo.item()));
        }
        return testo.toString();
    }

    private static String attributi(Map<String, AttributeValue> item) {
        return new TreeMap<>(item).entrySet().stream()
                .map(attributo -> attributo.getKey() + "=" + valore(attributo.getValue()))
                .collect(Collectors.joining(", "));
    }

    private static String valore(AttributeValue valore) {
        return valore.n() != null ? valore.n() : valore.s();
    }
}
