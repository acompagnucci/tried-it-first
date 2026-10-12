package mellum;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChiediAlModello {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private static final String DOMANDA = """
            Questo programma Java dovrebbe segnalare come non saldata solo la fattura F-003, \
            ma segnala anche F-001. Trova il bug, spiega in poche righe perché succede \
            e proponi la correzione più piccola possibile.""";

    public static void main(String[] args) throws IOException, InterruptedException {
        int codiceUscita = esegui(args);
        if (codiceUscita != 0) {
            System.exit(codiceUscita);
        }
    }

    private static int esegui(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            ERR.println("Uso: java mellum/ChiediAlModello.java <file da analizzare>");
            return 1;
        }
        Path sorgente = Path.of(args[0]);
        if (!Files.isRegularFile(sorgente)) {
            ERR.println("Non trovo il file " + sorgente.toAbsolutePath());
            return 1;
        }
        String codice = Files.readString(sorgente);
        String baseUrl = System.getenv().getOrDefault("MODEL_URL", "http://localhost:8080");

        String messaggio = DOMANDA + "\n\n```java\n" + codice + "\n```";
        String body = """
                {"messages":[{"role":"user","content":"%s"}],
                 "temperature":0.6,"top_p":0.95,"top_k":20,"stream":false}"""
                .formatted(jsonEscape(messaggio));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        OUT.println("Domanda inviata a " + baseUrl + ", aspetto la risposta...\n");
        long inizio = System.nanoTime();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            ERR.println("Non riesco a collegarmi a " + baseUrl + ". Il server è avviato? (README, passo 4)");
            return 1;
        }
        double secondi = (System.nanoTime() - inizio) / 1e9;

        if (response.statusCode() != 200) {
            ERR.println("Il server ha risposto con errore " + response.statusCode() + ":\n" + response.body());
            return 1;
        }

        String json = response.body();
        String ragionamento = campoStringa(json, "reasoning_content");
        String risposta = campoStringa(json, "content");

        if (ragionamento != null && !ragionamento.isBlank()) {
            OUT.println("=== Ragionamento del modello ===\n" + ragionamento.strip() + "\n");
        }
        OUT.println("=== Risposta ===\n" + (risposta == null ? "(vuota)" : risposta.strip()) + "\n");
        OUT.printf("Tempo totale: %.1f secondi%n", secondi);
        String tokenGenerati = campoNumero(json, "completion_tokens");
        if (tokenGenerati != null) {
            OUT.println("Token generati: " + tokenGenerati);
        }
        String tokenAlSecondo = campoNumero(json, "predicted_per_second");
        if (tokenAlSecondo != null) {
            OUT.printf("Velocità di generazione: %.1f token/s%n", Double.parseDouble(tokenAlSecondo));
        }
        if ("length".equals(campoStringa(json, "finish_reason"))) {
            ERR.println("\nIl modello ha esaurito il contesto prima di finire. Rilancia, oppure aumenta --ctx-size.");
            return 2;
        }
        return 0;
    }

    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.toString();
    }

    static String campoStringa(String json, String nome) {
        String chiave = "\"" + nome + "\":\"";
        int inizio = json.indexOf(chiave);
        if (inizio < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = inizio + chiave.length();
        while (i < json.length() && json.charAt(i) != '"') {
            if (json.charAt(i) == '\\') {
                i = decodificaEscape(json, i + 1, sb);
            } else {
                sb.append(json.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static int decodificaEscape(String json, int i, StringBuilder sb) {
        char c = json.charAt(i);
        if (c == 'u') {
            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
            return i + 5;
        }
        switch (c) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            default -> sb.append(c);
        }
        return i + 1;
    }

    static String campoNumero(String json, String nome) {
        Matcher m = Pattern.compile("\"" + nome + "\":([0-9.eE+-]+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
