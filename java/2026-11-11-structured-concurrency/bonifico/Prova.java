package bonifico;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Prova {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final String IBAN_FINTO = "IT00X0000000000000000000001";
    private static final Bonifico BONIFICO_NORMALE = new Bonifico(IBAN_FINTO, new BigDecimal("250.00"));
    private static final Bonifico BONIFICO_SOSPETTO = new Bonifico(IBAN_FINTO, new BigDecimal("5000.00"));
    private static final int BONIFICI_INSIEME = 1_000;
    private static final Duration ATTESA_DOPO_LA_RISPOSTA = Controlli.DURATA_LIMITE.plusSeconds(1);

    private record Risposta(long millisecondi, String motivoDelRifiuto) {

        boolean rifiutata() {
            return motivoDelRifiuto != null;
        }
    }

    private Prova() {
    }

    public static void main(String[] args) throws InterruptedException {
        var scelta = varianteDagliArgomenti(args);
        if (scelta.isEmpty()) {
            OUT.println("Uso: java bonifico/Prova.java <" + Variante.nomi() + ">");
            return;
        }
        var variante = scelta.get();
        OUT.println("Java " + Runtime.version() + ", " + variante.nome() + ": " + variante.descrizione());
        try (var condiviso = Executors.newVirtualThreadPerTaskExecutor()) {
            unBonifico(variante, condiviso, BONIFICO_NORMALE);
            unBonifico(variante, condiviso, BONIFICO_SOSPETTO);
            tantiBonifici(variante, condiviso, BONIFICO_SOSPETTO);
        }
    }

    private static Optional<Variante> varianteDagliArgomenti(String[] args) {
        return args.length == 1 ? Variante.daNome(args[0]) : Optional.empty();
    }

    private static void unBonifico(Variante variante, ExecutorService condiviso, Bonifico bonifico)
            throws InterruptedException {
        var controlli = variante.nuoviControlli();
        var risposta = chiedi(variante.verificatore(condiviso, controlli), bonifico);
        var esito = risposta.rifiutata()
                ? "rifiutato dopo %s ms (%s)".formatted(numero(risposta.millisecondi()), risposta.motivoDelRifiuto())
                : "autorizzato dopo %s ms".formatted(numero(risposta.millisecondi()));
        OUT.println("bonifico da " + euro(bonifico.importo()) + ": " + esito);
        stampaLimiteDopoLaRisposta(controlli);
    }

    private static void tantiBonifici(Variante variante, ExecutorService condiviso, Bonifico bonifico)
            throws InterruptedException {
        var controlli = variante.nuoviControlli();
        var verificatore = variante.verificatore(condiviso, controlli);
        var richieste = new ArrayList<Future<Risposta>>();
        try (var clienti = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var i = 0; i < BONIFICI_INSIEME; i++) {
                richieste.add(clienti.submit(() -> chiedi(verificatore, bonifico)));
            }
        }
        var risposte = richieste.stream().map(Future::resultNow).toList();
        var rifiutati = risposte.stream().filter(Risposta::rifiutata).count();
        var tempi = risposte.stream().mapToLong(Risposta::millisecondi).summaryStatistics();
        OUT.println("%s bonifici da %s insieme: %s rifiutati, risposte tra %s e %s ms".formatted(
                numero(BONIFICI_INSIEME), euro(bonifico.importo()), numero(rifiutati),
                numero(tempi.getMin()), numero(tempi.getMax())));
        stampaLimiteDopoLaRisposta(controlli);
    }

    private static Risposta chiedi(Verificatore verificatore, Bonifico bonifico) throws InterruptedException {
        var inizio = System.nanoTime();
        try {
            verificatore.verifica(bonifico);
            return new Risposta(millisecondiDa(inizio), null);
        } catch (ExecutionException e) {
            return new Risposta(millisecondiDa(inizio), e.getCause().getMessage());
        }
    }

    private static long millisecondiDa(long inizioInNanosecondi) {
        return Duration.ofNanos(System.nanoTime() - inizioInNanosecondi).toMillis();
    }

    private static void stampaLimiteDopoLaRisposta(Controlli controlli) throws InterruptedException {
        OUT.println("  limite giornaliero alla risposta:  " + descrivi(controlli.statoLimite()));
        Thread.sleep(ATTESA_DOPO_LA_RISPOSTA);
        OUT.println("  limite giornaliero %d secondi dopo: %s".formatted(
                ATTESA_DOPO_LA_RISPOSTA.toSeconds(), descrivi(controlli.statoLimite())));
    }

    private static String descrivi(Controlli.StatoLimite stato) {
        var base = "finiti %s, interrotti %s, in corso %s".formatted(
                numero(stato.finiti()), numero(stato.interrotti()), numero(stato.inCorso()));
        return stato.interruzioniIgnorate() == 0
                ? base
                : base + ", interruzioni ignorate " + numero(stato.interruzioniIgnorate());
    }

    private static String numero(long valore) {
        return NumberFormat.getIntegerInstance(Locale.ITALY).format(valore);
    }

    private static String euro(BigDecimal importo) {
        return NumberFormat.getCurrencyInstance(Locale.ITALY).format(importo);
    }
}
