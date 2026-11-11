package bonifico;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

enum Variante {

    EXECUTOR(
            "executor",
            "ExecutorService condiviso, get() su saldo, limite e antifrode",
            false,
            (condiviso, controlli) -> new ConExecutor(condiviso, controlli)::verifica),
    EXECUTOR_ANTIFRODE_PRIMA(
            "executor-antifrode-prima",
            "ExecutorService condiviso, get() prima sull'antifrode",
            false,
            (condiviso, controlli) -> new ConExecutor(condiviso, controlli)::verificaAntifrodePrima),
    EXECUTOR_PER_RICHIESTA(
            "executor-per-richiesta",
            "un ExecutorService per richiesta in try-with-resources, get() prima sull'antifrode",
            false,
            (condiviso, controlli) -> new ConExecutorPerRichiesta(controlli)::verifica),
    SCOPE(
            "scope",
            "StructuredTaskScope",
            false,
            (condiviso, controlli) -> new ConScope(controlli)::verifica),
    SCOPE_INTERRUZIONE_IGNORATA(
            "scope-interruzione-ignorata",
            "StructuredTaskScope, il controllo del limite ignora l'interruzione",
            true,
            (condiviso, controlli) -> new ConScope(controlli)::verifica);

    private final String nome;
    private final String descrizione;
    private final boolean limiteIgnoraInterruzione;
    private final BiFunction<ExecutorService, Controlli, Verificatore> verificatore;

    Variante(String nome, String descrizione, boolean limiteIgnoraInterruzione,
            BiFunction<ExecutorService, Controlli, Verificatore> verificatore) {
        this.nome = nome;
        this.descrizione = descrizione;
        this.limiteIgnoraInterruzione = limiteIgnoraInterruzione;
        this.verificatore = verificatore;
    }

    String nome() {
        return nome;
    }

    String descrizione() {
        return descrizione;
    }

    Controlli nuoviControlli() {
        return new Controlli(limiteIgnoraInterruzione);
    }

    Verificatore verificatore(ExecutorService condiviso, Controlli controlli) {
        return verificatore.apply(condiviso, controlli);
    }

    static Optional<Variante> daNome(String nome) {
        return Arrays.stream(values()).filter(v -> v.nome.equals(nome)).findFirst();
    }

    static String nomi() {
        return Arrays.stream(values()).map(Variante::nome).collect(Collectors.joining(" | "));
    }
}
