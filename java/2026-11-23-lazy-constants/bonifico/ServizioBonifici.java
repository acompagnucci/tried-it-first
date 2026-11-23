package bonifico;

final class ServizioBonifici {

    private final Componenti componenti;

    ServizioBonifici(Componenti componenti) {
        this.componenti = componenti;
    }

    Esito esegui(Bonifico bonifico) {
        var banca = componenti.tabellaAbi().banca(bonifico.iban());
        var importoInEuro = Importi.EURO.equals(bonifico.valuta())
                ? bonifico.importo()
                : componenti.tassiDiCambio().inEuro(bonifico.importo(), bonifico.valuta());
        return new Esito(banca, importoInEuro);
    }

    String ricevuta(Bonifico bonifico, Esito esito) {
        return componenti.templateRicevuta().compila(bonifico, esito);
    }
}
