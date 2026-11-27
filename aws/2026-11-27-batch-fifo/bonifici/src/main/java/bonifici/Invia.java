package bonifici;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

public final class Invia {

    private static final int MESSAGGI_PER_CHIAMATA = 10;
    private static final int FILI = 16;
    private static final int EURO_MINIMI = 100;
    private static final int IMPORTI_DIVERSI = 900;
    private static final long CENTESIMI_PER_EURO = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SqsClient sqs;
    private final String coda;
    private final String prova;
    private final int conti;
    private final int perConto;
    private final String dataValuta = LocalDate.now().toString();

    private Invia(SqsClient sqs, String coda, String prova, int conti, int perConto) {
        this.sqs = sqs;
        this.coda = coda;
        this.prova = prova;
        this.conti = conti;
        this.perConto = perConto;
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException("uso: Invia url-della-coda prova conti bonifici");
        }
        var conti = Integer.parseInt(args[2]);
        var bonifici = Integer.parseInt(args[3]);
        if (conti < 1 || bonifici % (2 * conti) != 0) {
            throw new IllegalArgumentException(
                    "ogni conto deve avere lo stesso numero di bonifici, e pari");
        }
        try (var sqs = SqsClient.create()) {
            new Invia(sqs, args[0], args[1], conti, bonifici / conti).invia();
        }
    }

    private void invia() {
        var fili = Math.min(FILI, conti);
        var lavori = IntStream.range(0, fili)
                .<Callable<Integer>>mapToObj(filo -> () -> inviaIContiDelFilo(filo, fili))
                .toList();
        var inviati = InParallelo.esegui(fili, lavori).stream().mapToInt(Integer::intValue).sum();
        var suConti = conti == 1 ? "1 conto" : conti + " conti";
        Uscita.OUT.printf("Inviati %d bonifici su %s%n", inviati, suConti);
    }

    private int inviaIContiDelFilo(int filo, int fili) {
        var inviati = 0;
        var gruppo = new ArrayList<SendMessageBatchRequestEntry>(MESSAGGI_PER_CHIAMATA);
        for (var progressivo = 1; progressivo <= perConto; progressivo++) {
            for (var conto = filo + 1; conto <= conti; conto += fili) {
                gruppo.add(messaggio(gruppo.size(), bonifico(conto, progressivo)));
                if (gruppo.size() == MESSAGGI_PER_CHIAMATA) {
                    inviati += spedisci(gruppo);
                }
            }
        }
        return gruppo.isEmpty() ? inviati : inviati + spedisci(gruppo);
    }

    private Bonifico bonifico(int conto, int progressivo) {
        var coppia = (progressivo + 1) / 2;
        var euro = EURO_MINIMI + Math.floorMod(Objects.hash(conto, coppia), IMPORTI_DIVERSI);
        var accredito = progressivo % 2 == 1;
        var causale = accredito
                ? "Incasso fattura %d/%d".formatted(conto, coppia)
                : "Pagamento fornitore %d/%d".formatted(conto, coppia);
        var centesimi = euro * CENTESIMI_PER_EURO * (accredito ? 1 : -1);
        return new Bonifico(prova, conto, progressivo, centesimi, causale, dataValuta);
    }

    private SendMessageBatchRequestEntry messaggio(int posizione, Bonifico bonifico) {
        return SendMessageBatchRequestEntry.builder()
                .id(Integer.toString(posizione))
                .messageBody(json(bonifico))
                .messageGroupId("conto-" + bonifico.conto())
                .messageDeduplicationId(
                        prova + "-" + bonifico.conto() + "-" + bonifico.progressivo())
                .build();
    }

    private int spedisci(List<SendMessageBatchRequestEntry> gruppo) {
        var risposta = sqs.sendMessageBatch(richiesta -> richiesta.queueUrl(coda).entries(gruppo));
        if (!risposta.failed().isEmpty()) {
            throw new IllegalStateException("SQS ha rifiutato dei messaggi: " + risposta.failed());
        }
        var spediti = gruppo.size();
        gruppo.clear();
        return spediti;
    }

    private static String json(Bonifico bonifico) {
        try {
            return JSON.writeValueAsString(bonifico);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
