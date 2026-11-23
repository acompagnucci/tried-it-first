package bonifico;

import static bonifico.Database.DB;

final class ConDoubleChecked implements Componenti {

    private final Object lockTabellaAbi = new Object();
    private final Object lockTassiDiCambio = new Object();
    private final Object lockTemplateRicevuta = new Object();

    private volatile TabellaAbi tabellaAbi;
    private volatile TassiDiCambio tassiDiCambio;
    private volatile TemplateRicevuta templateRicevuta;

    @Override
    public TabellaAbi tabellaAbi() {
        var tabella = tabellaAbi;
        if (tabella == null) {
            synchronized (lockTabellaAbi) {
                tabella = tabellaAbi;
                if (tabella == null) {
                    tabella = DB.caricaTabellaAbi();
                    tabellaAbi = tabella;
                }
            }
        }
        return tabella;
    }

    @Override
    public TassiDiCambio tassiDiCambio() {
        var tassi = tassiDiCambio;
        if (tassi == null) {
            synchronized (lockTassiDiCambio) {
                tassi = tassiDiCambio;
                if (tassi == null) {
                    tassi = DB.caricaTassiDiCambio();
                    tassiDiCambio = tassi;
                }
            }
        }
        return tassi;
    }

    @Override
    public TemplateRicevuta templateRicevuta() {
        var template = templateRicevuta;
        if (template == null) {
            synchronized (lockTemplateRicevuta) {
                template = templateRicevuta;
                if (template == null) {
                    template = DB.caricaTemplateRicevuta();
                    templateRicevuta = template;
                }
            }
        }
        return template;
    }
}
