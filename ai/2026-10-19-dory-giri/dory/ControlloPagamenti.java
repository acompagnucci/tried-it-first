package dory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ControlloPagamenti {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    record Fattura(String numero, BigDecimal importo) {}

    record Pagamento(String numeroFattura, BigDecimal importo) {}

    static List<String> fattureNonSaldate(List<Fattura> fatture, List<Pagamento> pagamenti) {
        Map<String, BigDecimal> totalePagato = new HashMap<>();
        for (Pagamento p : pagamenti) {
            totalePagato.merge(p.numeroFattura(), p.importo(), BigDecimal::add);
        }

        List<String> nonSaldate = new ArrayList<>();
        for (Fattura f : fatture) {
            BigDecimal pagato = totalePagato.getOrDefault(f.numero(), BigDecimal.ZERO);
            if (!pagato.equals(f.importo())) {
                nonSaldate.add(f.numero());
            }
        }
        return nonSaldate;
    }

    public static void main(String[] args) {
        Fattura f1 = new Fattura("F-001", new BigDecimal("120.00"));
        Fattura f2 = new Fattura("F-002", new BigDecimal("80.50"));
        Fattura f3 = new Fattura("F-003", new BigDecimal("45.00"));

        List<Pagamento> pagamenti = List.of(
                new Pagamento(f1.numero(), new BigDecimal("120")),
                new Pagamento(f2.numero(), new BigDecimal("40.25")),
                new Pagamento(f2.numero(), new BigDecimal("40.25")),
                new Pagamento(f3.numero(), new BigDecimal("20.00")));

        List<String> atteso = List.of(f3.numero());
        List<String> ottenuto = fattureNonSaldate(List.of(f1, f2, f3), pagamenti);

        OUT.println("Fatture non saldate attese:   " + atteso);
        OUT.println("Fatture non saldate trovate:  " + ottenuto);
        OUT.println(atteso.equals(ottenuto) ? "OK" : "ERRORE: il risultato non è quello atteso");
    }
}
