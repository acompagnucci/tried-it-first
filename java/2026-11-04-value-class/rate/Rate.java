package rate;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.ObjectName;

public class Rate {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final int PRATICHE = 10_000;
    private static final int RATE_PER_PRATICA = 120;
    private static final long PRIMO_ID = 1_000_000L;
    private static final int PRIMO_IMPORTO = 25_000;

    private static final List<String> CLASSI = List.of(
            "rate.Rate$Rata", "java.lang.Long", "java.time.LocalDate", "java.lang.Integer");
    private static final Pattern SPAZI = Pattern.compile("\\s+");
    private static final int COLONNA_CLASSE = 3;
    private static final String RIGA_TOTALE = "Total";

    record Rata(Long idPratica,
                LocalDate scadenza,
                Integer centesimi) {
    }

    public static void main(String[] args) throws JMException {
        OUT.println("Java " + Runtime.version());
        List<Rata> rate = piano(LocalDate.of(2027, 1, 27));
        OUT.println(String.format(Locale.ITALY, "%,d rate in memoria", rate.size()));
        istogramma().lines()
                .filter(riga -> CLASSI.contains(classe(riga)) || riga.startsWith(RIGA_TOTALE))
                .forEach(OUT::println);
        OUT.println("prima rata: " + rate.getFirst());
    }

    static List<Rata> piano(LocalDate primaScadenza) {
        List<Rata> rate = new ArrayList<>();
        for (int pratica = 0; pratica < PRATICHE; pratica++) {
            for (int mese = 0; mese < RATE_PER_PRATICA; mese++) {
                rate.add(new Rata(PRIMO_ID + pratica, primaScadenza.plusMonths(mese), PRIMO_IMPORTO + pratica));
            }
        }
        return rate;
    }

    static String classe(String riga) {
        String[] colonne = SPAZI.split(riga.trim());
        return colonne.length > COLONNA_CLASSE ? colonne[COLONNA_CLASSE] : "";
    }

    static String istogramma() throws JMException {
        var comandi = new ObjectName("com.sun.management:type=DiagnosticCommand");
        return (String) ManagementFactory.getPlatformMBeanServer().invoke(
                comandi, "gcClassHistogram", new Object[] {null}, new String[] {String[].class.getName()});
    }
}
