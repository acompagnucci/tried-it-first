package jdk28;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.incubator.json.Json;
import jdk.incubator.json.JsonNumber;
import jdk.incubator.json.JsonParseException;
import jdk.incubator.json.JsonValue;
import jdk.incubator.json.JsonValueException;

public class LeggiBonifico {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    record Bonifico(String iban, BigDecimal importo, String causale) {
    }

    private static Bonifico leggi(String testo) {
        JsonValue bonifico = Json.parse(testo);
        return new Bonifico(
                bonifico.get("iban").asString(),
                importo(bonifico.get("importo")),
                bonifico.get("causale").asString());
    }

    private static BigDecimal importo(JsonValue valore) {
        if (valore instanceof JsonNumber numero) {
            return new BigDecimal(numero.toString());
        }
        throw new JsonValueException("importo non è un numero: " + valore);
    }

    private static String esito(String testo) {
        try {
            return leggi(testo).toString();
        } catch (JsonParseException | JsonValueException e) {
            return "errore: " + e.getMessage();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            OUT.println("Uso: java --add-modules jdk.incubator.json jdk28/LeggiBonifico.java <file json>...");
            return;
        }
        for (String nomeFile : args) {
            var file = Path.of(nomeFile);
            OUT.println(file.getFileName() + ": " + esito(Files.readString(file)));
        }
    }
}
