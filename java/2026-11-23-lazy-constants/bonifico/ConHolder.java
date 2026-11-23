package bonifico;

import static bonifico.Database.DB;

final class ConHolder implements Componenti {

    private static final class TabellaAbiHolder {
        static final TabellaAbi TABELLA_ABI = DB.caricaTabellaAbi();
    }

    private static final class TassiDiCambioHolder {
        static final TassiDiCambio TASSI_DI_CAMBIO = DB.caricaTassiDiCambio();
    }

    private static final class TemplateRicevutaHolder {
        static final TemplateRicevuta TEMPLATE_RICEVUTA = DB.caricaTemplateRicevuta();
    }

    @Override
    public TabellaAbi tabellaAbi() {
        return TabellaAbiHolder.TABELLA_ABI;
    }

    @Override
    public TassiDiCambio tassiDiCambio() {
        return TassiDiCambioHolder.TASSI_DI_CAMBIO;
    }

    @Override
    public TemplateRicevuta templateRicevuta() {
        return TemplateRicevutaHolder.TEMPLATE_RICEVUTA;
    }
}
