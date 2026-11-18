package misura;

import java.util.List;

enum Scenario {

    JAVA25_HEAP_PICCOLO("java25-heap-piccolo", "JAVA25", List.of("-XX:+UseZGC", "-Xms2M", "-Xmx8G")),
    JAVA25_HEAP_GRANDE("java25-heap-grande", "JAVA25",
            List.of("-XX:+UseZGC", "-Xms8G", "-Xmx8G", "-XX:+AlwaysPreTouch")),
    JAVA28_HEAP_PICCOLO("java28-heap-piccolo", "JAVA28", List.of("-XX:+UseZGC", "-Xms2M", "-Xmx8G")),
    JAVA28_ADATTIVO("java28-adattivo", "JAVA28", List.of("-XX:+UseZGC", "-XX:+ZAdaptiveHeapSizing"));

    private final String nome;
    private final String variabileDelJava;
    private final List<String> opzioni;

    Scenario(String nome, String variabileDelJava, List<String> opzioni) {
        this.nome = nome;
        this.variabileDelJava = variabileDelJava;
        this.opzioni = opzioni;
    }

    String nome() {
        return nome;
    }

    String java() {
        var java = System.getenv(variabileDelJava);
        if (java == null || java.isBlank()) {
            throw new IllegalStateException("impostate " + variabileDelJava + " con il percorso del comando java");
        }
        return java;
    }

    List<String> opzioni() {
        return opzioni;
    }
}
