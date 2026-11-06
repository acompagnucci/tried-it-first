package carica;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class GeneraFile {

    private static final int RIGA_ROTTA = 5_000;
    private static final int INDICE_RIGA_ROTTA = RIGA_ROTTA - 1;

    private GeneraFile() {
    }

    public static void main(String[] args) throws IOException {
        var cartella = Path.of(args.length > 0 ? args[0] : "file");
        scrivi(cartella.resolve("iniziali/iniziali.json"), Righe.iniziali());
        scrivi(cartella.resolve("giorno/giorno.json"), Righe.delGiorno());
        scrivi(cartella.resolve("righe-rotte/senza-chiave.json"), senzaChiave());
        scriviRighe(cartella.resolve("righe-rotte/troncata.json"), troncata());
    }

    private static List<Riga> senzaChiave() {
        var righe = new ArrayList<>(Righe.senzaCondizione("S", "senza-chiave"));
        Riga rotta = righe.get(INDICE_RIGA_ROTTA);
        var item = new LinkedHashMap<>(rotta.item());
        item.remove("id");
        righe.set(INDICE_RIGA_ROTTA, new Riga(item, rotta.condizione()));
        return righe;
    }

    private static List<String> troncata() {
        var righe = new ArrayList<>(json(Righe.senzaCondizione("T", "troncata")));
        String rotta = righe.get(INDICE_RIGA_ROTTA);
        righe.set(INDICE_RIGA_ROTTA, rotta.substring(0, rotta.length() / 2));
        return righe;
    }

    private static List<String> json(List<Riga> righe) {
        return righe.stream().map(RigaJson::di).toList();
    }

    private static void scrivi(Path file, List<Riga> righe) throws IOException {
        scriviRighe(file, json(righe));
    }

    private static void scriviRighe(Path file, List<String> righe) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, righe);
    }
}
