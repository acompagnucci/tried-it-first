package bonifico;

record TemplateRicevuta(String testo) {

    String compila(Bonifico bonifico, Esito esito) {
        return testo.formatted(Importi.formatta(bonifico.importo(), bonifico.valuta()),
                esito.bancaBeneficiario(), Importi.formatta(esito.importoInEuro(), Importi.EURO));
    }
}
