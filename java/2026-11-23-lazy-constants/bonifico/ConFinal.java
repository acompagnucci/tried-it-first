package bonifico;

import static bonifico.Database.DB;

final class ConFinal implements Componenti {

    private final TabellaAbi tabellaAbi;
    private final TassiDiCambio tassiDiCambio;
    private final TemplateRicevuta templateRicevuta;

    ConFinal() {
        tabellaAbi = DB.caricaTabellaAbi();
        tassiDiCambio = DB.caricaTassiDiCambio();
        templateRicevuta = DB.caricaTemplateRicevuta();
    }

    @Override
    public TabellaAbi tabellaAbi() {
        return tabellaAbi;
    }

    @Override
    public TassiDiCambio tassiDiCambio() {
        return tassiDiCambio;
    }

    @Override
    public TemplateRicevuta templateRicevuta() {
        return templateRicevuta;
    }
}
