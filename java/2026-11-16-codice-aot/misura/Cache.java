package misura;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

enum Cache {

    SENZA_CODICE("senza-codice.aot", true, List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTCodeCaching")),
    CON_CODICE("con-codice.aot", true, List.of()),
    SOLO_AVVIO("solo-avvio.aot", false, List.of());

    private static final Path CARTELLA = Path.of("servizio", "target", "aot");
    private static final String ESCI_DOPO_L_AVVIO = "-Dspring.context.exit=onRefresh";

    private final String file;
    private final boolean addestrataConLeRichieste;
    private final List<String> opzioniCodice;

    Cache(String file, boolean addestrataConLeRichieste, List<String> opzioniCodice) {
        this.file = file;
        this.addestrataConLeRichieste = addestrataConLeRichieste;
        this.opzioniCodice = opzioniCodice;
    }

    Path percorso() {
        return CARTELLA.resolve(file);
    }

    boolean addestrataConLeRichieste() {
        return addestrataConLeRichieste;
    }

    List<String> perAddestrare() {
        var uscita = addestrataConLeRichieste ? Stream.<String>empty() : Stream.of(ESCI_DOPO_L_AVVIO);
        return Stream.of(opzioniCodice.stream(), uscita, Stream.of("-XX:AOTCacheOutput=" + percorso()))
                .flatMap(opzioni -> opzioni)
                .toList();
    }

    List<String> perUsare() {
        return Stream.concat(opzioniCodice.stream(), Stream.of("-XX:AOTCache=" + percorso())).toList();
    }
}
