package bonifici;

final class Controlli {

    private static final int NESSUNO = 0;

    private final int contoGuasto;
    private final int bonificoGuasto;

    private Controlli(int contoGuasto, int bonificoGuasto) {
        this.contoGuasto = contoGuasto;
        this.bonificoGuasto = bonificoGuasto;
    }

    static Controlli daAmbiente() {
        return new Controlli(numero("CONTO_GUASTO"), numero("BONIFICO_GUASTO"));
    }

    void verifica(Ricevuto ricevuto) {
        var bonifico = ricevuto.bonifico();
        if (bonifico.conto() == contoGuasto && bonifico.progressivo() == bonificoGuasto
                && !ricevuto.ricevutoDiNuovo()) {
            throw new ServizioNonDisponibile("Il controllo antiriciclaggio non ha risposto: conto "
                    + bonifico.conto() + ", bonifico " + bonifico.progressivo());
        }
    }

    private static int numero(String variabile) {
        var valore = System.getenv(variabile);
        return valore == null || valore.isBlank() ? NESSUNO : Integer.parseInt(valore);
    }
}
