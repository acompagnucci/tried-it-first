package bonifico;

import java.util.Map;

record TabellaAbi(Map<String, String> banchePerAbi) {

    private static final int INIZIO_ABI = 5;
    private static final int FINE_ABI = 10;

    String banca(String iban) {
        var abi = iban.substring(INIZIO_ABI, FINE_ABI);
        var banca = banchePerAbi.get(abi);
        if (banca == null) {
            throw new IllegalArgumentException("ABI " + abi + " non presente in tabella");
        }
        return banca;
    }
}
