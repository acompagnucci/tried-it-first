package bonifico;

import java.time.Duration;

enum Componente {

    TABELLA_ABI("tabella ABI", Duration.ofMillis(300)),
    TASSI_DI_CAMBIO("tassi di cambio", Duration.ofMillis(200)),
    TEMPLATE_RICEVUTA("template della ricevuta", Duration.ofMillis(400));

    private final String nome;
    private final Duration durataCaricamento;

    Componente(String nome, Duration durataCaricamento) {
        this.nome = nome;
        this.durataCaricamento = durataCaricamento;
    }

    String nome() {
        return nome;
    }

    Duration durataCaricamento() {
        return durataCaricamento;
    }
}
