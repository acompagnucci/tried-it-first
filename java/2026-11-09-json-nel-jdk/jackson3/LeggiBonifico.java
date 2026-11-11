package jackson3;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class LeggiBonifico {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    record Bonifico(String iban, BigDecimal importo, String causale) {
    }

    private static JsonMapper mapper(String modo) {
        return switch (modo) {
            case "normale" -> JsonMapper.builder().build();
            case "severo" -> JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .build();
            default -> throw new IllegalArgumentException("Modo sconosciuto: " + modo);
        };
    }

    private static String esito(JsonMapper mapper, String testo) {
        try {
            var bonifico = mapper.readValue(testo, Bonifico.class);
            return bonifico.toString();
        } catch (JacksonException e) {
            return "errore: " + e.getMessage();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            OUT.println("Uso: java --class-path 'lib/jackson3/*' jackson3/LeggiBonifico.java normale|severo <file json>...");
            return;
        }
        var mapper = mapper(args[0]);
        for (var nomeFile : Arrays.copyOfRange(args, 1, args.length)) {
            var file = Path.of(nomeFile);
            OUT.println(file.getFileName() + ": " + esito(mapper, Files.readString(file)));
        }
    }
}
