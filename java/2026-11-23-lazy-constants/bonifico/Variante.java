package bonifico;

import java.util.function.Supplier;

enum Variante implements Opzione {

    FINAL("final", "campi final caricati nel costruttore", ConFinal::new),
    IF_NULL("if-null", "caricamento al primo uso con if (campo == null)", ConIfNull::new),
    DOUBLE_CHECKED("double-checked", "double-checked locking su un campo volatile",
            ConDoubleChecked::new),
    HOLDER("holder", "una holder class per componente", ConHolder::new),
    LAZY_CONSTANT("lazy-constant", "un campo LazyConstant per componente", ConLazyConstant::new),
    LAZY_CONSTANT_RETRY("lazy-constant-retry", "LazyConstant con 3 tentativi nel Supplier",
            ConLazyConstantConRetry::new);

    private final String nome;
    private final String descrizione;
    private final Supplier<Componenti> componenti;

    Variante(String nome, String descrizione, Supplier<Componenti> componenti) {
        this.nome = nome;
        this.descrizione = descrizione;
        this.componenti = componenti;
    }

    @Override
    public String nome() {
        return nome;
    }

    String descrizione() {
        return descrizione;
    }

    Componenti nuoviComponenti() {
        return componenti.get();
    }
}
