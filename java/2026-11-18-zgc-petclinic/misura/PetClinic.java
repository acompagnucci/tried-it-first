package misura;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class PetClinic {

    private static final Path JAR = Path.of("target", "petclinic.jar");
    private static final Path CARTELLA_LOG = Path.of("target", "log");
    static final Path CARTELLA_GC = Path.of("prove", "gc");
    static final URI INDIRIZZO = URI.create("http://localhost:8080");

    private static final HttpRequest HOME = HttpRequest.newBuilder(INDIRIZZO).build();
    private static final int HTTP_OK = 200;
    private static final Duration PAUSA_TRA_TENTATIVI = Duration.ofMillis(5);
    private static final Duration ATTESA_MASSIMA = Duration.ofMinutes(1);
    private static final Pattern CAPACITA_HEAP = Pattern.compile("^\\[([\\d.]+)s].* [YO]: +Capacity:(.*)$");
    private static final Pattern MEGABYTE = Pattern.compile("(\\d+)M");
    private static final double NANO_IN_UN_SECONDO = 1e9;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final Process processo;
    private final long avviataIl;
    private final Path log;
    private final Path logGcHeap;

    private PetClinic(Process processo, long avviataIl, Path log, Path logGcHeap) {
        this.processo = processo;
        this.avviataIl = avviataIl;
        this.log = log;
        this.logGcHeap = logGcHeap;
    }

    static PetClinic avvia(Scenario scenario, String nomeCorsa) throws IOException {
        Files.createDirectories(CARTELLA_LOG);
        Files.createDirectories(CARTELLA_GC);
        var log = CARTELLA_LOG.resolve(nomeCorsa + ".log");
        var logGcHeap = CARTELLA_LOG.resolve(nomeCorsa + "-heap.log");
        var comando = new ArrayList<String>();
        comando.add(scenario.java());
        comando.addAll(scenario.opzioni());
        comando.add("-Xlog:gc:file=" + CARTELLA_GC.resolve(nomeCorsa + ".log"));
        comando.add("-Xlog:gc+heap:file=" + logGcHeap);
        comando.addAll(List.of("-jar", JAR.toString()));
        var avviataIl = System.nanoTime();
        var processo = new ProcessBuilder(comando)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        return new PetClinic(processo, avviataIl, log, logGcHeap);
    }

    static String versioneDelJava(Scenario scenario) throws IOException, InterruptedException {
        var processo = new ProcessBuilder(scenario.java(), "-version").redirectErrorStream(true).start();
        try (InputStream output = processo.getInputStream()) {
            var primaRiga = new String(output.readAllBytes(), StandardCharsets.UTF_8).lines().findFirst();
            processo.waitFor();
            return primaRiga.orElse("versione sconosciuta");
        }
    }

    Duration aspettaLaPrimaRisposta() throws IOException, InterruptedException {
        var limite = avviataIl + ATTESA_MASSIMA.toNanos();
        while (System.nanoTime() < limite) {
            if (!processo.isAlive()) {
                throw new IllegalStateException("PetClinic si è fermata da sola: guardate " + log);
            }
            if (rispondeLaHome()) {
                return dallAvvio();
            }
            Thread.sleep(PAUSA_TRA_TENTATIVI);
        }
        throw new IllegalStateException("PetClinic non risponde dopo " + ATTESA_MASSIMA.toSeconds()
                + " secondi: guardate " + log);
    }

    private boolean rispondeLaHome() throws IOException, InterruptedException {
        try {
            return client.send(HOME, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK;
        } catch (ConnectException e) {
            return false;
        }
    }

    Duration dallAvvio() {
        return Duration.ofNanos(System.nanoTime() - avviataIl);
    }

    NavigableMap<Duration, Long> heapInMbDalLog() throws IOException {
        NavigableMap<Duration, Long> heap;
        try (var righe = Files.lines(logGcHeap)) {
            heap = righe.map(CAPACITA_HEAP::matcher)
                    .filter(Matcher::matches)
                    .collect(Collectors.toMap(
                            PetClinic::istante, PetClinic::massimoDelCiclo, Math::max, TreeMap::new));
        }
        if (heap.isEmpty()) {
            throw new IllegalStateException("nessuna riga Capacity in " + logGcHeap);
        }
        return heap;
    }

    private static Duration istante(Matcher riga) {
        return Duration.ofNanos((long) (Double.parseDouble(riga.group(1)) * NANO_IN_UN_SECONDO));
    }

    private static long massimoDelCiclo(Matcher riga) {
        return MEGABYTE.matcher(riga.group(2)).results()
                .mapToLong(mb -> Long.parseLong(mb.group(1)))
                .max()
                .orElseThrow();
    }

    int ferma() throws InterruptedException {
        processo.destroy();
        return processo.waitFor();
    }

    Path log() {
        return log;
    }
}
