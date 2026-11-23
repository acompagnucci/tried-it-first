package bonifico;

import static bonifico.Database.DB;

import java.time.Duration;

final class ConLazyConstantConRetry implements Componenti {

    private static final Retry RETRY = new Retry(3, Duration.ofMillis(100));

    private final LazyConstant<TabellaAbi> tabellaAbi =
            LazyConstant.of(() -> RETRY.esegui(DB::caricaTabellaAbi));
    private final LazyConstant<TassiDiCambio> tassiDiCambio =
            LazyConstant.of(() -> RETRY.esegui(DB::caricaTassiDiCambio));
    private final LazyConstant<TemplateRicevuta> templateRicevuta =
            LazyConstant.of(() -> RETRY.esegui(DB::caricaTemplateRicevuta));

    @Override
    public TabellaAbi tabellaAbi() {
        return tabellaAbi.get();
    }

    @Override
    public TassiDiCambio tassiDiCambio() {
        return tassiDiCambio.get();
    }

    @Override
    public TemplateRicevuta templateRicevuta() {
        return templateRicevuta.get();
    }
}
