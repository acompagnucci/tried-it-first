package bonifici;

import java.util.List;

public final class RimandaIlRestoDelConto extends Gestore {

    @Override
    protected Stato applica(Stato prima, List<Ricevuto> delConto, List<String> daRiprovare) {
        var stato = prima;
        for (var i = 0; i < delConto.size(); i++) {
            var ricevuto = delConto.get(i);
            try {
                verifica(ricevuto);
                stato = stato.applica(ricevuto.bonifico());
            } catch (ServizioNonDisponibile e) {
                delConto.subList(i, delConto.size()).forEach(r -> daRiprovare.add(r.id()));
                break;
            }
        }
        return stato;
    }
}
