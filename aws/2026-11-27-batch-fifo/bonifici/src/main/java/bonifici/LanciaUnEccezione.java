package bonifici;

import java.util.List;

public final class LanciaUnEccezione extends Gestore {

    @Override
    protected Stato applica(Stato prima, List<Ricevuto> delConto, List<String> daRiprovare) {
        var stato = prima;
        for (var ricevuto : delConto) {
            verifica(ricevuto);
            stato = stato.applica(ricevuto.bonifico());
        }
        return stato;
    }
}
