package bonifici;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.math.BigDecimal;

@Entity
public class Conto {

    @Id
    private String iban;

    private BigDecimal saldo;

    @Version
    private long versione;

    protected Conto() {
    }

    void addebita(BigDecimal importo) {
        if (saldo.compareTo(importo) < 0) {
            throw new SaldoInsufficiente(iban);
        }
        saldo = saldo.subtract(importo);
    }

    void accredita(BigDecimal importo) {
        saldo = saldo.add(importo);
    }
}
