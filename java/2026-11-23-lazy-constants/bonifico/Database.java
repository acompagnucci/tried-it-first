package bonifico;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class Database {

    static final Database DB = new Database();

    private static final Map<String, String> BANCHE_PER_ABI = Map.of("00001", "Banca di Prova");
    private static final Map<Currency, BigDecimal> EURO_PER_UNITA =
            Map.of(Importi.DOLLARO, new BigDecimal("0.87"));
    private static final String TESTO_RICEVUTA = "Bonifico di %s verso %s, pari a %s";

    private final Map<Componente, AtomicInteger> caricamenti = new EnumMap<>(Componente.class);
    private final AtomicInteger caricamentiTabellaDaFallire = new AtomicInteger();

    private Database() {
        for (var componente : Componente.values()) {
            caricamenti.put(componente, new AtomicInteger());
        }
    }

    void tabellaAbiNonDisponibilePer(int caricamentiFalliti) {
        caricamentiTabellaDaFallire.set(caricamentiFalliti);
    }

    TabellaAbi caricaTabellaAbi() {
        carica(Componente.TABELLA_ABI);
        if (caricamentiTabellaDaFallire.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
            throw new DatabaseNonDisponibile("timeout sulla lettura della tabella ABI");
        }
        return new TabellaAbi(BANCHE_PER_ABI);
    }

    TassiDiCambio caricaTassiDiCambio() {
        carica(Componente.TASSI_DI_CAMBIO);
        return new TassiDiCambio(EURO_PER_UNITA);
    }

    TemplateRicevuta caricaTemplateRicevuta() {
        carica(Componente.TEMPLATE_RICEVUTA);
        return new TemplateRicevuta(TESTO_RICEVUTA);
    }

    int caricamenti(Componente componente) {
        return caricamenti.get(componente).get();
    }

    private void carica(Componente componente) {
        caricamenti.get(componente).incrementAndGet();
        try {
            Thread.sleep(componente.durataCaricamento());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseNonDisponibile("caricamento interrotto: " + componente.nome(), e);
        }
    }
}
