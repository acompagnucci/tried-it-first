package bonifico;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

enum Caso {
    IMPORTO("importo"),
    BANCA_PRIMA("banca-prima"),
    CONFIGURAZIONE_PRIMA("configurazione-prima");

    private final String nome;

    Caso(String nome) {
        this.nome = nome;
    }

    String nome() {
        return nome;
    }

    static Optional<Caso> daArgomenti(String[] args) {
        if (args.length != 1) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(caso -> caso.nome.equals(args[0])).findFirst();
    }

    static String nomi() {
        return Arrays.stream(values()).map(Caso::nome).collect(Collectors.joining("|"));
    }
}
