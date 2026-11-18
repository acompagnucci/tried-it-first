package misura;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;

public final class Misura {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final int CORSE_SE_NON_INDICATE = 5;
    private static final Duration SOGLIA_P99 = Duration.ofMillis(20);
    private static final Path CARTELLA_SECONDI = Path.of("prove", "secondi");
    private static final int USCITA_DOPO_SIGTERM = 143;
    private static final double NANO_IN_UN_MILLISECONDO = 1e6;

    private record Corsa(
            Duration primaRisposta, List<Carico.Secondo> secondi, List<Long> heapInMb, int errori) {

        long secondiSopraLaSoglia() {
            return secondi.stream().filter(secondo -> secondo.p99().compareTo(SOGLIA_P99) > 0).count();
        }

        long sempreSottoLaSogliaDalSecondo() {
            var ultimoSopra = IntStream.range(0, secondi.size())
                    .filter(i -> secondi.get(i).p99().compareTo(SOGLIA_P99) > 0)
                    .max()
                    .orElse(-1);
            return ultimoSopra + 1L;
        }

        long heapMassimoInMb() {
            return heapInMb.stream().mapToLong(Long::longValue).max().orElseThrow();
        }

        long heapAllaFineInMb() {
            return heapInMb.getLast();
        }
    }

    private Misura() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var corse = args.length == 0 ? CORSE_SE_NON_INDICATE : Integer.parseInt(args[0]);
        OUT.println("PetClinic, %d %s per ogni scenario, alternate".formatted(corse, corse == 1 ? "corsa" : "corse"));
        OUT.println(("carico: da %d a %d richieste al secondo in %d secondi, "
                + "poi %d al secondo per %d secondi").formatted(
                Carico.RICHIESTE_AL_SECONDO_ALL_INIZIO, Carico.RICHIESTE_AL_SECONDO_A_REGIME,
                Carico.SECONDI_DI_RAMPA, Carico.RICHIESTE_AL_SECONDO_A_REGIME, Carico.SECONDI_A_REGIME));
        OUT.println("pagine, a turno: " + String.join(" ", Carico.PAGINE));
        for (var scenario : Scenario.values()) {
            OUT.println("%s: %s, %s".formatted(
                    scenario.nome(), PetClinic.versioneDelJava(scenario), String.join(" ", scenario.opzioni())));
        }
        OUT.println();
        Files.createDirectories(CARTELLA_SECONDI);
        var risultati = new EnumMap<Scenario, List<Corsa>>(Scenario.class);
        for (var i = 1; i <= corse; i++) {
            for (var scenario : Scenario.values()) {
                var nomeCorsa = scenario.nome() + "-" + i;
                var corsa = esegui(scenario, nomeCorsa);
                salva(nomeCorsa, corsa);
                risultati.computeIfAbsent(scenario, s -> new ArrayList<>()).add(corsa);
                OUT.println(nomeCorsa + ": " + descrivi(corsa) + ", errori " + corsa.errori());
            }
        }
        if (corse > 1) {
            OUT.println();
            risultati.forEach(Misura::stampaLaMediana);
        }
    }

    private static Corsa esegui(Scenario scenario, String nomeCorsa) throws IOException, InterruptedException {
        var petclinic = PetClinic.avvia(scenario, nomeCorsa);
        Duration primaRisposta;
        Duration inizioDelCarico;
        Carico.Esito esito;
        try {
            primaRisposta = petclinic.aspettaLaPrimaRisposta();
            inizioDelCarico = petclinic.dallAvvio();
            esito = new Carico().esegui();
        } finally {
            ferma(petclinic);
        }
        var heap = petclinic.heapInMbDalLog();
        var heapPerSecondo = IntStream.range(0, Carico.SECONDI)
                .mapToObj(secondo -> heapAllIstante(heap, inizioDelCarico.plusSeconds(secondo + 1L)))
                .toList();
        return new Corsa(primaRisposta, esito.secondi(), heapPerSecondo, esito.errori());
    }

    private static void ferma(PetClinic petclinic) throws InterruptedException {
        var uscita = petclinic.ferma();
        if (uscita != 0 && uscita != USCITA_DOPO_SIGTERM) {
            OUT.println("PetClinic è uscita con codice " + uscita + ": guardate " + petclinic.log());
        }
    }

    private static long heapAllIstante(NavigableMap<Duration, Long> heap, Duration istante) {
        var ultimoCiclo = heap.floorEntry(istante);
        return (ultimoCiclo != null ? ultimoCiclo : heap.firstEntry()).getValue();
    }

    private static void salva(String nomeCorsa, Corsa corsa) throws IOException {
        var righe = new ArrayList<String>();
        righe.add("secondo\trichieste\tp50_ms\tp99_ms\theap_mb");
        for (var i = 0; i < corsa.secondi().size(); i++) {
            var secondo = corsa.secondi().get(i);
            righe.add(String.format(Locale.ROOT, "%d\t%d\t%.1f\t%.1f\t%d", i, secondo.richieste(),
                    millisecondi(secondo.p50()), millisecondi(secondo.p99()), corsa.heapInMb().get(i)));
        }
        Files.write(CARTELLA_SECONDI.resolve(nomeCorsa + ".tsv"), righe);
    }

    private static String descrivi(Corsa corsa) {
        return riassunto(corsa.primaRisposta().toMillis(), corsa.secondiSopraLaSoglia(),
                corsa.sempreSottoLaSogliaDalSecondo(), corsa.heapMassimoInMb(), corsa.heapAllaFineInMb());
    }

    private static void stampaLaMediana(Scenario scenario, List<Corsa> corse) {
        OUT.println("%s, mediana di %d corse: %s".formatted(scenario.nome(), corse.size(), riassunto(
                mediana(corse, corsa -> corsa.primaRisposta().toMillis()),
                mediana(corse, Corsa::secondiSopraLaSoglia),
                mediana(corse, Corsa::sempreSottoLaSogliaDalSecondo),
                mediana(corse, Corsa::heapMassimoInMb),
                mediana(corse, Corsa::heapAllaFineInMb))));
    }

    private static String riassunto(long primaRisposta, long secondiSopra, long sempreSottoDal,
            long heapMassimo, long heapAllaFine) {
        return ("prima risposta dopo %s ms, P99 sopra %d ms in %d secondi su %d, "
                + "sempre sotto dal secondo %d, heap fino a %s MB, alla fine %s MB").formatted(
                numero(primaRisposta), SOGLIA_P99.toMillis(), secondiSopra, Carico.SECONDI, sempreSottoDal,
                numero(heapMassimo), numero(heapAllaFine));
    }

    private static long mediana(List<Corsa> corse, ToLongFunction<Corsa> valore) {
        var ordinati = corse.stream().mapToLong(valore).sorted().toArray();
        return ordinati[ordinati.length / 2];
    }

    private static double millisecondi(Duration durata) {
        return durata.toNanos() / NANO_IN_UN_MILLISECONDO;
    }

    private static String numero(long valore) {
        return NumberFormat.getIntegerInstance(Locale.ITALY).format(valore);
    }
}
