package bonifico;

import static bonifico.Database.DB;

final class ConLazyConstant implements Componenti {

    private final LazyConstant<TabellaAbi> tabellaAbi =
            LazyConstant.of(DB::caricaTabellaAbi);
    private final LazyConstant<TassiDiCambio> tassiDiCambio =
            LazyConstant.of(DB::caricaTassiDiCambio);
    private final LazyConstant<TemplateRicevuta> templateRicevuta =
            LazyConstant.of(DB::caricaTemplateRicevuta);

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
