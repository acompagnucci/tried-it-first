package servizio;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public class Servizio {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final List<String> PROPRIETA = List.of("db.url", "db.password", "db.pass");
    private static final List<String> VARIABILI = List.of("DATABASE_URL", "DB_PASSWORD", "DB_PASS");

    public static void main(String[] args) throws InterruptedException {
        OUT.println("Java " + Runtime.version());
        for (String nome : PROPRIETA) {
            OUT.println(riga("-D" + nome, System.getProperty(nome)));
        }
        for (String nome : VARIABILI) {
            OUT.println(riga(nome, System.getenv(nome)));
        }
        if (args.length > 0) {
            Thread.sleep(Duration.ofSeconds(Long.parseLong(args[0])));
        }
    }

    static String riga(String nome, String valore) {
        return "%-14s %s".formatted(nome, Objects.requireNonNullElse(valore, "non impostata"));
    }
}
