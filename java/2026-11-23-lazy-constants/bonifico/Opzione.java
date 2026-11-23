package bonifico;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

interface Opzione {

    String nome();

    static <E extends Enum<E> & Opzione> Optional<E> daNome(Class<E> tipo, String nome) {
        return Arrays.stream(tipo.getEnumConstants())
                .filter(opzione -> opzione.nome().equals(nome))
                .findFirst();
    }

    static <E extends Enum<E> & Opzione> String nomi(Class<E> tipo) {
        return Arrays.stream(tipo.getEnumConstants())
                .map(Opzione::nome)
                .collect(Collectors.joining(" | "));
    }
}
