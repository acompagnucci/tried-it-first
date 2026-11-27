package bonifici;

import java.util.List;

public final class RimandaSoloIlFallito extends Gestore {

    @Override
    protected Stato applica(Stato prima, List<Ricevuto> delConto, List<String> daRiprovare) {
        var stato = prima;
        for (var ricevuto : delConto) {
            try {
                verifica(ricevuto);
                stato = stato.applica(ricevuto.bonifico());
            } catch (ServizioNonDisponibile e) {
                daRiprovare.add(ricevuto.id());
            }
        }
        return stato;
    }
}
