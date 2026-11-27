package bonifici;

import java.util.List;

record Risposta(List<DaRiprovare> batchItemFailures) {

    record DaRiprovare(String itemIdentifier) {
    }

    static Risposta di(List<String> id) {
        return new Risposta(id.stream().map(DaRiprovare::new).toList());
    }
}
