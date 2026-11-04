package confronti;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class Confronti {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final String PRATICA_IN_CACHE = "100";
    private static final String PRATICA_FUORI_CACHE = "500";

    public static void main(String[] args) {
        OUT.println("Java " + Runtime.version());
        for (String id : List.of(PRATICA_IN_CACHE, PRATICA_FUORI_CACHE)) {
            Long dalDatabase = Long.valueOf(id);
            Long dallaRichiesta = Long.valueOf(id);
            OUT.println("pratica %s == pratica %s: %b".formatted(id, id, dalDatabase == dallaRichiesta));
        }

        LocalDate scadenza = LocalDate.of(2027, 1, 27);
        LocalDate letta = LocalDate.parse("2027-01-27");
        OUT.println("scadenza == scadenza letta da testo: " + (scadenza == letta));

        Map<Long, String> perIdentita = new IdentityHashMap<>();
        perIdentita.put(Long.valueOf(PRATICA_FUORI_CACHE), "dal database");
        perIdentita.put(Long.valueOf(PRATICA_FUORI_CACHE), "dalla richiesta");
        OUT.println("IdentityHashMap dopo due put con Long %s, chiavi: %d"
                .formatted(PRATICA_FUORI_CACHE, perIdentita.size()));

        Map<LocalDate, String> cache = new WeakHashMap<>();
        try {
            cache.put(scadenza, "rata 1");
            OUT.println("WeakHashMap dopo un put con una LocalDate, chiavi: " + cache.size());
        } catch (RuntimeException e) {
            OUT.println("WeakHashMap dopo un put con una LocalDate: " + e);
        }
    }
}
