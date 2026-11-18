package misura;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class CicliDelGc {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final Pattern FINE_DEL_CICLO =
            Pattern.compile("Collection \\(.*\\) \\d+M\\(\\d+%\\)->\\d+M\\(\\d+%\\) ([\\d.]+)s$");
    private static final Pattern NUMERO_DELLA_CORSA = Pattern.compile("-(\\d+)\\.log$");

    private record Cicli(int quanti, double secondi) {
    }

    private CicliDelGc() {
    }

    public static void main(String[] args) throws IOException {
        OUT.println("cicli di ZGC in ogni corsa, avvio compreso, e loro durata sommata (da prove/gc)");
        for (var scenario : Scenario.values()) {
            var corse = new ArrayList<Cicli>();
            for (var log : logDelleCorse(scenario)) {
                corse.add(contaICicli(log));
            }
            OUT.println("%s: cicli %s, secondi %s".formatted(scenario.nome(),
                    corse.stream().map(c -> Integer.toString(c.quanti())).collect(Collectors.joining(" ")),
                    corse.stream().map(c -> secondi(c.secondi())).collect(Collectors.joining(" "))));
        }
    }

    private static List<Path> logDelleCorse(Scenario scenario) throws IOException {
        try (var logDelGc = Files.list(PetClinic.CARTELLA_GC)) {
            return logDelGc.filter(log -> log.getFileName().toString().startsWith(scenario.nome() + "-"))
                    .sorted(Comparator.comparingInt(CicliDelGc::numeroDellaCorsa))
                    .toList();
        }
    }

    private static int numeroDellaCorsa(Path log) {
        var numero = NUMERO_DELLA_CORSA.matcher(log.getFileName().toString());
        if (!numero.find()) {
            throw new IllegalStateException("nome del log senza numero della corsa: " + log);
        }
        return Integer.parseInt(numero.group(1));
    }

    private static Cicli contaICicli(Path log) throws IOException {
        try (var righe = Files.lines(log)) {
            var durate = righe.map(FINE_DEL_CICLO::matcher)
                    .filter(Matcher::find)
                    .mapToDouble(fine -> Double.parseDouble(fine.group(1)))
                    .summaryStatistics();
            return new Cicli((int) durate.getCount(), durate.getSum());
        }
    }

    private static String secondi(double valore) {
        return String.format(Locale.ITALY, "%.2f", valore);
    }
}
