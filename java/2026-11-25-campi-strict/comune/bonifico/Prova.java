package bonifico;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class Prova {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final long UN_MILIONE_IN_CENTESIMI = 100_000_000L;
    private static final String DOLLARI = "USD";

    private Prova() {
    }

    public static void main(String[] args) {
        var caso = Caso.daArgomenti(args);
        if (caso.isEmpty()) {
            OUT.println("Uso: java -cp <classi> bonifico.Prova <" + Caso.nomi() + ">");
            return;
        }
        esegui(caso.get());
    }

    private static void esegui(Caso caso) {
        OUT.println("Java " + Runtime.version() + ", caso " + caso.nome());
        switch (caso) {
            case IMPORTO -> importoOltreIlLimite();
            case BANCA_PRIMA -> bancaPrima();
            case CONFIGURAZIONE_PRIMA -> configurazionePrima();
        }
    }

    private static void importoOltreIlLimite() {
        OUT.println("bonifico di " + Importi.formatta(UN_MILIONE_IN_CENTESIMI, DOLLARI)
                + ", limite per bonifico " + Importi.formatta(Importo.LIMITE_IN_CENTESIMI));
        try {
            var importo = new ImportoInValuta(UN_MILIONE_IN_CENTESIMI, DOLLARI);
            OUT.println("  accettato: " + Importi.formatta(importo.centesimi(), importo.valuta()));
        } catch (ImportoOltreIlLimite e) {
            OUT.println("  rifiutato: " + e.getMessage());
        }
    }

    private static void bancaPrima() {
        OUT.println("prima Banca.ABI, poi Configurazione.INTESTAZIONE_RICEVUTA");
        var abi = Banca.ABI;
        var intestazione = Configurazione.INTESTAZIONE_RICEVUTA;
        stampa(abi, intestazione);
    }

    private static void configurazionePrima() {
        OUT.println("prima Configurazione.INTESTAZIONE_RICEVUTA, poi Banca.ABI");
        var intestazione = Configurazione.INTESTAZIONE_RICEVUTA;
        var abi = Banca.ABI;
        stampa(abi, intestazione);
    }

    private static void stampa(String abi, String intestazione) {
        OUT.println("  Banca.ABI: " + abi);
        OUT.println("  intestazione della ricevuta: " + intestazione);
    }
}
