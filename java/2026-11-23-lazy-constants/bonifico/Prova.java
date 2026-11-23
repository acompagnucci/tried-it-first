package bonifico;

import static bonifico.Database.DB;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.LongSummaryStatistics;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public final class Prova {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final String IBAN_FINTO = "IT00X0000100001000000000001";
    private static final Bonifico IN_EURO =
            new Bonifico(IBAN_FINTO, new BigDecimal("250.00"), Importi.EURO);
    private static final Bonifico IN_DOLLARI =
            new Bonifico(IBAN_FINTO, new BigDecimal("300.00"), Importi.DOLLARO);
    private static final int BONIFICI_INSIEME = 100;
    private static final int BONIFICI_CON_DB_GIU = 3;
    private static final int CARICAMENTI_FALLITI_A_LUNGO = 3;

    private record Risposta(long millisecondi, String errore) {

        boolean eseguita() {
            return errore == null;
        }
    }

    private Prova() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            stampaUso();
            return;
        }
        var variante = Opzione.daNome(Variante.class, args[0]);
        var scenario = Opzione.daNome(Scenario.class, args[1]);
        if (variante.isEmpty() || scenario.isEmpty()) {
            stampaUso();
            return;
        }
        esegui(variante.get(), scenario.get());
    }

    private static void esegui(Variante variante, Scenario scenario) {
        OUT.println("Java " + Runtime.version() + ", scenario " + scenario.nome());
        OUT.println(variante.nome() + ": " + variante.descrizione());
        switch (scenario) {
            case AVVIO -> avvio(variante);
            case CONCORRENZA -> bonificiInsieme(avvia(variante));
            case DB_GIU -> avviaConDbGiu(variante, 1).ifPresent(Prova::bonificiUnoAllaVolta);
            case DB_GIU_CONCORRENZA -> avviaConDbGiu(variante, 1).ifPresent(Prova::bonificiInsieme);
            case DB_GIU_A_LUNGO -> avviaConDbGiu(variante, CARICAMENTI_FALLITI_A_LUNGO)
                    .ifPresent(Prova::bonificiUnoAllaVolta);
        }
        stampaCaricamenti();
    }

    private static void stampaUso() {
        OUT.println("Uso: java --enable-preview -cp target/classes bonifico.Prova <%s> <%s>"
                .formatted(Opzione.nomi(Variante.class), Opzione.nomi(Scenario.class)));
    }

    private static ServizioBonifici avvia(Variante variante) {
        var inizio = System.nanoTime();
        var servizio = new ServizioBonifici(variante.nuoviComponenti());
        OUT.println("servizio pronto dopo " + numero(millisecondiDa(inizio)) + " ms");
        return servizio;
    }

    private static Optional<ServizioBonifici> avviaConDbGiu(
            Variante variante, int caricamentiFalliti) {
        DB.tabellaAbiNonDisponibilePer(caricamentiFalliti);
        var quali = caricamentiFalliti == 1
                ? "al primo caricamento"
                : "ai primi " + caricamentiFalliti + " caricamenti";
        OUT.println("il DB va in timeout " + quali + " della tabella ABI, poi torna su");
        try {
            return Optional.of(avvia(variante));
        } catch (DatabaseNonDisponibile e) {
            OUT.println("il servizio non parte: " + descrivi(e));
            return Optional.empty();
        }
    }

    private static void avvio(Variante variante) {
        var servizio = avvia(variante);

        var inizio = System.nanoTime();
        var esito = servizio.esegui(IN_EURO);
        OUT.println("bonifico di %s verso %s: risposta dopo %s ms".formatted(
                importo(IN_EURO), esito.bancaBeneficiario(), numero(millisecondiDa(inizio))));
        stampaCaricamenti();

        inizio = System.nanoTime();
        var ricevuta = servizio.ricevuta(IN_DOLLARI, servizio.esegui(IN_DOLLARI));
        OUT.println("bonifico di %s con ricevuta: risposta dopo %s ms".formatted(
                importo(IN_DOLLARI), numero(millisecondiDa(inizio))));
        OUT.println("  ricevuta: " + ricevuta);
    }

    private static void bonificiUnoAllaVolta(ServizioBonifici servizio) {
        for (var i = 1; i <= BONIFICI_CON_DB_GIU; i++) {
            var risposta = manda(servizio);
            var millisecondi = numero(risposta.millisecondi());
            var esito = risposta.eseguita()
                    ? "eseguito dopo %s ms".formatted(millisecondi)
                    : "errore dopo %s ms, %s".formatted(millisecondi, risposta.errore());
            OUT.println("bonifico " + i + ": " + esito);
        }
    }

    private static void bonificiInsieme(ServizioBonifici servizio) {
        var richieste = new ArrayList<Future<Risposta>>();
        try (var clienti = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var i = 0; i < BONIFICI_INSIEME; i++) {
                richieste.add(clienti.submit(() -> manda(servizio)));
            }
        }
        var risposte = richieste.stream().map(Future::resultNow).toList();
        OUT.println(numero(BONIFICI_INSIEME) + " bonifici di " + importo(IN_EURO) + " insieme");
        stampaEseguiti(risposte);
        stampaErrori(risposte);
    }

    private static void stampaEseguiti(List<Risposta> risposte) {
        var tempi = risposte.stream()
                .filter(Risposta::eseguita)
                .mapToLong(Risposta::millisecondi)
                .summaryStatistics();
        if (tempi.getCount() > 0) {
            OUT.println("  eseguiti: " + numero(tempi.getCount()) + ", " + descrivi(tempi));
        }
    }

    private static void stampaErrori(List<Risposta> risposte) {
        var tempiPerErrore = risposte.stream()
                .filter(risposta -> !risposta.eseguita())
                .collect(Collectors.groupingBy(Risposta::errore, TreeMap::new,
                        Collectors.summarizingLong(Risposta::millisecondi)));
        tempiPerErrore.forEach((errore, tempi) -> OUT.println("  in errore: %s, %s, %s".formatted(
                numero(tempi.getCount()), descrivi(tempi), errore)));
    }

    private static Risposta manda(ServizioBonifici servizio) {
        var inizio = System.nanoTime();
        try {
            servizio.esegui(IN_EURO);
            return new Risposta(millisecondiDa(inizio), null);
        } catch (RuntimeException | ExceptionInInitializerError | NoClassDefFoundError e) {
            return new Risposta(millisecondiDa(inizio), descrivi(e));
        }
    }

    private static void stampaCaricamenti() {
        OUT.println("  caricamenti dal DB: " + Arrays.stream(Componente.values())
                .map(c -> c.nome() + " " + numero(DB.caricamenti(c)))
                .collect(Collectors.joining(", ")));
    }

    private static String descrivi(LongSummaryStatistics tempi) {
        var minimo = numero(tempi.getMin());
        return tempi.getCount() == 1
                ? "risposta dopo %s ms".formatted(minimo)
                : "risposte tra %s e %s ms".formatted(minimo, numero(tempi.getMax()));
    }

    private static String descrivi(Throwable errore) {
        var descrizione = errore.getClass().getSimpleName();
        if (errore.getMessage() != null) {
            descrizione += " (" + errore.getMessage() + ")";
        }
        var causa = errore.getCause();
        return causa == null
                ? descrizione
                : descrizione + ", causa: " + causa.getClass().getSimpleName();
    }

    private static long millisecondiDa(long inizioInNanosecondi) {
        return Duration.ofNanos(System.nanoTime() - inizioInNanosecondi).toMillis();
    }

    private static String numero(long valore) {
        return NumberFormat.getIntegerInstance(Locale.ITALY).format(valore);
    }

    private static String importo(Bonifico bonifico) {
        return Importi.formatta(bonifico.importo(), bonifico.valuta());
    }
}
