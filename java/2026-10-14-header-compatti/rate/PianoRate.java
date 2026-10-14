package rate;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.management.JMException;
import javax.management.ObjectName;

public class PianoRate {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final int PRATICHE = 10_000;
    private static final int RATE_PER_PRATICA = 120;
    private static final int RATE_GIA_PAGATE = 6;
    private static final LocalDate PRIMA_SCADENZA = LocalDate.of(2026, 11, 27);
    private static final BigDecimal RATA_MINIMA = new BigDecimal("150.00");
    private static final int FASCE_DI_IMPORTO = 400;
    private static final BigDecimal QUOTA_INTERESSI_PRIMA_RATA = new BigDecimal("0.25");
    private static final long MB = 1024L * 1024L;

    record Rata(
            String pratica,
            int numero,
            LocalDate scadenza,
            BigDecimal capitale,
            BigDecimal interessi,
            boolean pagata) {}

    static List<Rata> creaRate() {
        var rate = new ArrayList<Rata>(PRATICHE * RATE_PER_PRATICA);
        for (int p = 0; p < PRATICHE; p++) {
            var pratica = "CQS-%06d".formatted(p);
            var importoRata = RATA_MINIMA.add(BigDecimal.valueOf(p % FASCE_DI_IMPORTO));
            for (int n = 1; n <= RATE_PER_PRATICA; n++) {
                BigDecimal interessi = interessi(importoRata, n);
                rate.add(new Rata(pratica, n, PRIMA_SCADENZA.plusMonths(n - 1L),
                        importoRata.subtract(interessi), interessi, n <= RATE_GIA_PAGATE));
            }
        }
        return rate;
    }

    static BigDecimal interessi(BigDecimal importoRata, int numero) {
        var rateResidue = BigDecimal.valueOf(RATE_PER_PRATICA - numero + 1L);
        return importoRata.multiply(QUOTA_INTERESSI_PRIMA_RATA)
                .multiply(rateResidue)
                .divide(BigDecimal.valueOf(RATE_PER_PRATICA), 2, RoundingMode.HALF_EVEN);
    }

    public static void main(String[] args) throws JMException {
        List<Rata> rate = creaRate();

        var memoria = ManagementFactory.getMemoryMXBean();
        memoria.gc();
        long usata = memoria.getHeapMemoryUsage().getUsed();

        OUT.println("Java " + Runtime.version() + ", header compatti: " + headerCompatti());
        OUT.printf(Locale.ITALY, "Rate in memoria: %,d%n", rate.size());
        OUT.printf(Locale.ITALY, "Heap usato dopo la GC: %,d MB%n", usata / MB);
        OUT.println();
        OUT.println(istogramma());
    }

    private static String headerCompatti() {
        try {
            return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .getVMOption("UseCompactObjectHeaders").getValue();
        } catch (IllegalArgumentException e) {
            return "non esiste in questa versione";
        }
    }

    private static String istogramma() throws JMException {
        var completo = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[] {null}, new String[] {String[].class.getName()});
        return completo.lines().limit(9).collect(Collectors.joining("\n"));
    }
}
