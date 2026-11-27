package bonifici;

import static bonifici.Uscita.OUT;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public final class Riassunto {

    private static final int BYTE_PER_KB = 1024;
    private static final int CONTI_DA_ELENCARE = 3;
    private static final long MS_PER_SECONDO = 1_000;
    private static final long MS_PER_BLOCCO = 10 * MS_PER_SECONDO;

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "uso: Riassunto tabella-conti tabella-invocazioni prova");
        }
        try (var dynamo = DynamoDbClient.create()) {
            var prova = args[2];
            var invocazioni = new Invocazioni(dynamo, args[1]).tutte(prova);
            if (invocazioni.isEmpty()) {
                throw new IllegalStateException("Nessuna invocazione per la prova " + prova);
            }
            stampaInvocazioni(invocazioni);
            stampaConti(new Conti(dynamo, args[0]).tutti(prova));
            stampaCronologia(invocazioni);
        }
    }

    private static void stampaInvocazioni(List<Invocazione> invocazioni) {
        var messaggi = invocazioni.stream().mapToInt(Invocazione::messaggi).sorted().toArray();
        var inizio = invocazioni.stream().mapToLong(Invocazione::inizio).min().orElseThrow();
        var fine = invocazioni.stream().mapToLong(Invocazione::fine).max().orElseThrow();
        var piuGrande = invocazioni.stream()
                .max(Comparator.comparingInt(Invocazione::byteEvento))
                .orElseThrow();
        riga("Invocazioni", invocazioni.size());
        riga("Messaggi ricevuti", invocazioni.stream().mapToInt(Invocazione::messaggi).sum());
        riga("di cui ricevuti di nuovo",
                invocazioni.stream().mapToInt(Invocazione::ricevutiDiNuovo).sum());
        riga("Messaggi per invocazione, il minimo", messaggi[0]);
        riga("Messaggi per invocazione, la mediana", messaggi[messaggi.length / 2]);
        riga("Messaggi per invocazione, il massimo", messaggi[messaggi.length - 1]);
        riga("Evento più grande, KB", piuGrande.byteEvento() / BYTE_PER_KB);
        riga("Evento più grande, messaggi", piuGrande.messaggi());
        riga("Dalla prima all'ultima invocazione, ms", fine - inizio);
        riga("Tempo totale nella Lambda, ms",
                invocazioni.stream().mapToLong(Invocazione::durata).sum());
        riga("Conti letti e riscritti su DynamoDB",
                invocazioni.stream().mapToInt(Invocazione::conti).sum());
    }

    private static void stampaConti(SortedMap<Integer, Stato> conti) {
        riga("Conti", conti.size());
        riga("Bonifici registrati", conti.values().stream().mapToInt(Stato::registrati).sum());
        riga("Rifiutati per saldo insufficiente",
                conti.values().stream().mapToInt(Stato::rifiutati).sum());
        riga("Scartati come doppioni", conti.values().stream().mapToInt(Stato::scartati).sum());
        var saldo = conti.values().stream().mapToLong(Stato::saldo).sum();
        OUT.printf(Locale.ITALY, "%-40s %,.2f €%n", "Somma dei saldi",
                BigDecimal.valueOf(saldo, 2));

        var conRifiuti = conti.entrySet().stream()
                .filter(conto -> conto.getValue().rifiutati() > 0)
                .map(Map.Entry::getKey)
                .toList();
        riga("Conti con bonifici rifiutati", conRifiuti.size());
        conRifiuti.stream().limit(CONTI_DA_ELENCARE).forEach(conto -> {
            var stato = conti.get(conto);
            OUT.printf(Locale.ITALY,
                    "  conto numero %d: %d registrati, %d rifiutati, %d scartati%n",
                    conto, stato.registrati(), stato.rifiutati(), stato.scartati());
        });
    }

    private static void stampaCronologia(List<Invocazione> invocazioni) {
        var inizio = invocazioni.stream().mapToLong(Invocazione::inizio).min().orElseThrow();
        var perBlocco = invocazioni.stream().collect(groupingBy(
                invocazione -> (invocazione.inizio() - inizio) / MS_PER_BLOCCO,
                TreeMap::new, toList()));
        OUT.println();
        OUT.println("Invocazioni partite, a blocchi di 10 secondi dalla prima");
        OUT.println("  dal secondo   invocazioni   messaggi");
        perBlocco.forEach((blocco, insieme) -> OUT.printf(Locale.ITALY,
                "  %,11d   %,11d   %,8d%n", blocco * MS_PER_BLOCCO / MS_PER_SECONDO, insieme.size(),
                insieme.stream().mapToInt(Invocazione::messaggi).sum()));
    }

    private static void riga(String etichetta, long valore) {
        OUT.printf(Locale.ITALY, "%-40s %,d%n", etichetta, valore);
    }

    private Riassunto() {
    }
}
