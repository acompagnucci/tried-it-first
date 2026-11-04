package accrediti;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Accrediti {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final int THREAD = 2;
    private static final int ACCREDITI_PER_THREAD = 1_000_000;
    private static final long UN_CENTESIMO = 1;

    private long saldoInCentesimi;

    void accredita(Long idConto, long centesimi) {
        synchronized (idConto) {
            saldoInCentesimi += centesimi;
        }
    }

    BigDecimal saldo() {
        return BigDecimal.valueOf(saldoInCentesimi, 2);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            OUT.println("Uso: java accrediti/Accrediti.java <numero del conto>");
            return;
        }
        String contoDallaRichiesta = args[0];
        var conto = new Accrediti();
        try (ExecutorService esecutore = Executors.newFixedThreadPool(THREAD)) {
            for (int t = 0; t < THREAD; t++) {
                esecutore.execute(() -> {
                    for (int i = 0; i < ACCREDITI_PER_THREAD; i++) {
                        conto.accredita(Long.valueOf(contoDallaRichiesta), UN_CENTESIMO);
                    }
                });
            }
        }
        NumberFormat euro = NumberFormat.getCurrencyInstance(Locale.ITALY);
        BigDecimal atteso = BigDecimal.valueOf(UN_CENTESIMO * THREAD * ACCREDITI_PER_THREAD, 2);
        OUT.println("conto %s: saldo %s, atteso %s"
                .formatted(contoDallaRichiesta, euro.format(conto.saldo()), euro.format(atteso)));
    }
}
