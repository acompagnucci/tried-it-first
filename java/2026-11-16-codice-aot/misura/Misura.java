package misura;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class Misura {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final int CORSE = 7;
    private static final long BYTE_IN_UN_MEGABYTE = 1_000_000L;
    private static final int USCITA_DOPO_SIGTERM = 143;
    private static final List<String> GC = List.of("G1GC", "ParallelGC", "ZGC");
    private static final String LOG_CODICE_AOT = "-Xlog:aot+codecache+init=info";
    private static final Pattern RIGHE_SULLA_CACHE =
            Pattern.compile("code entries|disabled|Unable|CompressedOops|Error occurred");

    private record Corsa(
            Duration primaRisposta, Duration ultimaRisposta, List<Duration> lotti, Map<Integer, Integer> risposte) {
    }

    private Misura() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        switch (args.length == 1 ? args[0] : "") {
            case "addestra" -> addestra();
            case "confronta" -> confronta();
            case "altri-gc" -> altriGc();
            default -> OUT.println("Uso: java misura/Misura.java <addestra | confronta | altri-gc>");
        }
    }

    private static void addestra() throws IOException, InterruptedException {
        OUT.println("Java " + Runtime.version() + ", addestramento");
        for (var cache : Cache.values()) {
            Files.createDirectories(cache.percorso().getParent());
            Files.deleteIfExists(cache.percorso());
            var nomeLog = "addestra-" + cache.percorso().getFileName();
            var inizio = System.nanoTime();
            var richieste = "nessuna richiesta";
            if (cache.addestrataConLeRichieste()) {
                richieste = "risposte " + descrivi(esegui(cache.perAddestrare(), nomeLog).risposte());
            } else {
                Servizio.avviaEAspettaLUscita(cache.perAddestrare(), nomeLog);
            }
            var durata = Duration.ofNanos(System.nanoTime() - inizio);
            if (!Files.exists(cache.percorso())) {
                throw new IllegalStateException("la cache " + cache.percorso() + " non è stata creata");
            }
            OUT.println("%s: %s MB, pronta dopo %s ms, %s".formatted(
                    cache.percorso().getFileName(), numero(Files.size(cache.percorso()) / BYTE_IN_UN_MEGABYTE),
                    numero(durata.toMillis()), richieste));
        }
    }

    private static void confronta() throws IOException, InterruptedException {
        OUT.println("Java " + Runtime.version() + ", " + CORSE + " corse per ogni modo, alternate");
        var corse = new EnumMap<Modo, List<Corsa>>(Modo.class);
        for (var modo : Modo.values()) {
            corse.put(modo, new ArrayList<>());
        }
        for (var i = 1; i <= CORSE; i++) {
            for (var modo : Modo.values()) {
                corse.get(modo).add(esegui(modo.opzioni(), modo.nome() + "-" + i));
            }
        }
        corse.forEach(Misura::stampa);
    }

    private static void stampa(Modo modo, List<Corsa> corse) {
        OUT.println();
        OUT.println(modo.nome() + ": " + modo.descrizione());
        for (var i = 0; i < corse.size(); i++) {
            var corsa = corse.get(i);
            OUT.println("corsa %d: prima risposta dopo %s ms, ultima dopo %s ms, lotti in ms %s".formatted(
                    i + 1, numero(corsa.primaRisposta().toMillis()), numero(corsa.ultimaRisposta().toMillis()),
                    elenco(corsa.lotti())));
        }
        var lotti = IntStream.range(0, Carico.LOTTI)
                .mapToObj(n -> Duration.ofMillis(mediana(corse, c -> c.lotti().get(n).toMillis())))
                .toList();
        OUT.println("mediana: prima risposta dopo %s ms, ultima dopo %s ms, lotti in ms %s".formatted(
                numero(mediana(corse, c -> c.primaRisposta().toMillis())),
                numero(mediana(corse, c -> c.ultimaRisposta().toMillis())), elenco(lotti)));
        var risposte = corse.stream().map(Corsa::risposte).distinct().toList();
        OUT.println(risposte.size() == 1
                ? "risposte, uguali in tutte le corse: " + descrivi(risposte.getFirst())
                : "ATTENZIONE, risposte diverse tra le corse: " + risposte);
    }

    private static void altriGc() throws IOException, InterruptedException {
        OUT.println("Java " + Runtime.version() + ", " + Cache.CON_CODICE.percorso().getFileName()
                + " addestrata con G1, usata con -XX:AOTMode=required");
        for (var gc : GC) {
            OUT.println("-XX:+Use" + gc);
            var opzioni = new ArrayList<>(List.of("-XX:+Use" + gc, "-XX:AOTMode=required", LOG_CODICE_AOT));
            opzioni.addAll(Cache.CON_CODICE.perUsare());
            Servizio.avviaSenzaWeb(opzioni).stream()
                    .filter(riga -> RIGHE_SULLA_CACHE.matcher(riga).find())
                    .forEach(OUT::println);
        }
    }

    private static Corsa esegui(List<String> opzioni, String nomeLog) throws IOException, InterruptedException {
        var carico = new Carico();
        var servizio = Servizio.avvia(opzioni, nomeLog);
        try {
            var primaRisposta = servizio.aspettaLaPrimaRisposta(carico);
            var lotti = new ArrayList<Duration>();
            for (var i = 0; i < Carico.LOTTI; i++) {
                lotti.add(carico.lotto());
            }
            return new Corsa(primaRisposta, servizio.tempoDallAvvio(), List.copyOf(lotti), carico.risposte());
        } finally {
            var uscita = servizio.ferma();
            if (uscita != 0 && uscita != USCITA_DOPO_SIGTERM) {
                OUT.println("il servizio è uscito con codice " + uscita + ": guardate " + servizio.log());
            }
        }
    }

    private static long mediana(List<Corsa> corse, ToLongFunction<Corsa> valore) {
        var ordinati = corse.stream().mapToLong(valore).sorted().toArray();
        return ordinati[ordinati.length / 2];
    }

    private static String elenco(List<Duration> durate) {
        return durate.stream().map(d -> numero(d.toMillis())).collect(Collectors.joining(" "));
    }

    private static String descrivi(Map<Integer, Integer> risposte) {
        return risposte.entrySet().stream()
                .map(e -> numero(e.getValue()) + " con codice " + e.getKey())
                .collect(Collectors.joining(", "));
    }

    private static String numero(long valore) {
        return NumberFormat.getIntegerInstance(Locale.ITALY).format(valore);
    }
}
