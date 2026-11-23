package bonifico;

enum Scenario implements Opzione {

    AVVIO("avvio"),
    CONCORRENZA("concorrenza"),
    DB_GIU("db-giu"),
    DB_GIU_CONCORRENZA("db-giu-concorrenza"),
    DB_GIU_A_LUNGO("db-giu-a-lungo");

    private final String nome;

    Scenario(String nome) {
        this.nome = nome;
    }

    @Override
    public String nome() {
        return nome;
    }
}
