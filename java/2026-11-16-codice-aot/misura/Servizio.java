package misura;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class Servizio {

    private static final Path JAR = Path.of("servizio", "target", "estratto", "bonifici.jar");
    private static final Path CARTELLA_LOG = Path.of("servizio", "target", "log");
    private static final String PROXY_DI_SPRING_SUL_JDK_28 = "-Djdk.reflect.allowNonSerializableConstruction=true";
    private static final List<String> SENZA_WEB_E_SOLO_AVVIO =
            List.of("--spring.main.web-application-type=none", "--spring.context.exit=onRefresh");
    private static final Duration PAUSA_TRA_TENTATIVI = Duration.ofMillis(5);
    private static final Duration ATTESA_MASSIMA = Duration.ofMinutes(1);

    private final Process processo;
    private final long avviatoIl;
    private final Path log;

    private Servizio(Process processo, long avviatoIl, Path log) {
        this.processo = processo;
        this.avviatoIl = avviatoIl;
        this.log = log;
    }

    static Servizio avvia(List<String> opzioni, String nomeLog) throws IOException {
        var log = nuovoLog(nomeLog);
        var avviatoIl = System.nanoTime();
        var processo = new ProcessBuilder(comando(opzioni, List.of()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        return new Servizio(processo, avviatoIl, log);
    }

    static List<String> avviaSenzaWeb(List<String> opzioni) throws IOException, InterruptedException {
        var processo = new ProcessBuilder(comando(opzioni, SENZA_WEB_E_SOLO_AVVIO)).redirectErrorStream(true).start();
        try (InputStream output = processo.getInputStream()) {
            var righe = new String(output.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            processo.waitFor();
            return righe;
        }
    }

    static void avviaEAspettaLUscita(List<String> opzioni, String nomeLog) throws IOException, InterruptedException {
        var log = nuovoLog(nomeLog);
        var uscita = new ProcessBuilder(comando(opzioni, List.of()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
                .waitFor();
        if (uscita != 0) {
            throw new IllegalStateException("il servizio è uscito con codice " + uscita + ": guardate " + log);
        }
    }

    private static Path nuovoLog(String nome) throws IOException {
        Files.createDirectories(CARTELLA_LOG);
        return CARTELLA_LOG.resolve(nome + ".log");
    }

    private static boolean risponde(Carico carico) throws IOException, InterruptedException {
        try {
            return carico.movimentiDelPrimoConto() == Carico.HTTP_OK;
        } catch (ConnectException e) {
            return false;
        }
    }

    private static List<String> comando(List<String> opzioni, List<String> argomenti) {
        var comando = new ArrayList<String>();
        comando.add(ProcessHandle.current().info().command().orElseThrow());
        comando.add(PROXY_DI_SPRING_SUL_JDK_28);
        comando.addAll(opzioni);
        comando.add("-jar");
        comando.add(JAR.toString());
        comando.addAll(argomenti);
        return comando;
    }

    Duration aspettaLaPrimaRisposta(Carico carico) throws IOException, InterruptedException {
        var limite = avviatoIl + ATTESA_MASSIMA.toNanos();
        while (System.nanoTime() < limite) {
            if (!processo.isAlive()) {
                throw new IllegalStateException("il servizio si è fermato da solo: guardate " + log);
            }
            if (risponde(carico)) {
                return tempoDallAvvio();
            }
            Thread.sleep(PAUSA_TRA_TENTATIVI);
        }
        throw new IllegalStateException("il servizio non risponde dopo " + ATTESA_MASSIMA.toSeconds()
                + " secondi: guardate " + log);
    }

    Duration tempoDallAvvio() {
        return Duration.ofNanos(System.nanoTime() - avviatoIl);
    }

    int ferma() throws InterruptedException {
        processo.destroy();
        return processo.waitFor();
    }

    Path log() {
        return log;
    }
}
