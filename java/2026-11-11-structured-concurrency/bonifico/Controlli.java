package bonifico;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

final class Controlli {

    private static final Duration DURATA_SALDO = Duration.ofMillis(200);
    private static final Duration DURATA_ANTIFRODE = Duration.ofMillis(100);
    private static final Duration DURATA_PAGINA_MOVIMENTI = Duration.ofMillis(100);
    private static final int PAGINE_MOVIMENTI = 20;
    static final Duration DURATA_LIMITE = DURATA_PAGINA_MOVIMENTI.multipliedBy(PAGINE_MOVIMENTI);

    private static final BigDecimal SALDO = new BigDecimal("10000.00");
    private static final BigDecimal LIMITE_GIORNALIERO = new BigDecimal("15000.00");
    private static final BigDecimal MOVIMENTI_PER_PAGINA = new BigDecimal("100.00");
    private static final BigDecimal IMPORTO_SOSPETTO = new BigDecimal("5000.00");
    private static final int RISCHIO_BASSO = 10;

    record StatoLimite(int iniziati, int finiti, int interrotti, int interruzioniIgnorate) {

        int inCorso() {
            return iniziati - finiti - interrotti;
        }
    }

    private final boolean limiteIgnoraInterruzione;
    private final AtomicInteger limitiIniziati = new AtomicInteger();
    private final AtomicInteger limitiFiniti = new AtomicInteger();
    private final AtomicInteger limitiInterrotti = new AtomicInteger();
    private final AtomicInteger interruzioniIgnorate = new AtomicInteger();

    Controlli(boolean limiteIgnoraInterruzione) {
        this.limiteIgnoraInterruzione = limiteIgnoraInterruzione;
    }

    BigDecimal saldo() throws InterruptedException {
        Thread.sleep(DURATA_SALDO);
        return SALDO;
    }

    int rischio(Bonifico bonifico) throws InterruptedException {
        Thread.sleep(DURATA_ANTIFRODE);
        if (bonifico.importo().compareTo(IMPORTO_SOSPETTO) >= 0) {
            throw new BonificoRifiutato("antifrode: bonifico sospetto");
        }
        return RISCHIO_BASSO;
    }

    BigDecimal limiteResiduo() throws InterruptedException {
        limitiIniziati.incrementAndGet();
        var residuo = LIMITE_GIORNALIERO;
        try {
            for (var pagina = 0; pagina < PAGINE_MOVIMENTI; pagina++) {
                residuo = residuo.subtract(leggiPaginaMovimenti());
            }
        } catch (InterruptedException e) {
            limitiInterrotti.incrementAndGet();
            throw e;
        }
        limitiFiniti.incrementAndGet();
        return residuo;
    }

    private BigDecimal leggiPaginaMovimenti() throws InterruptedException {
        try {
            Thread.sleep(DURATA_PAGINA_MOVIMENTI);
        } catch (InterruptedException e) {
            if (!limiteIgnoraInterruzione) {
                throw e;
            }
            interruzioniIgnorate.incrementAndGet();
        }
        return MOVIMENTI_PER_PAGINA;
    }

    StatoLimite statoLimite() {
        return new StatoLimite(
                limitiIniziati.get(), limitiFiniti.get(), limitiInterrotti.get(), interruzioniIgnorate.get());
    }
}
