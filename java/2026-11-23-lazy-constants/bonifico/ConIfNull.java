package bonifico;

import static bonifico.Database.DB;

final class ConIfNull implements Componenti {

    private TabellaAbi tabellaAbi;
    private TassiDiCambio tassiDiCambio;
    private TemplateRicevuta templateRicevuta;

    @Override
    public TabellaAbi tabellaAbi() {
        if (tabellaAbi == null) {
            tabellaAbi = DB.caricaTabellaAbi();
        }
        return tabellaAbi;
    }

    @Override
    public TassiDiCambio tassiDiCambio() {
        if (tassiDiCambio == null) {
            tassiDiCambio = DB.caricaTassiDiCambio();
        }
        return tassiDiCambio;
    }

    @Override
    public TemplateRicevuta templateRicevuta() {
        if (templateRicevuta == null) {
            templateRicevuta = DB.caricaTemplateRicevuta();
        }
        return templateRicevuta;
    }
}
