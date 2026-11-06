package carica;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

final class Righe {

    static final int RIGHE_PER_FILE = 10_000;
    private static final int NUOVI = 6_000;
    private static final int GIA_PRESENTI = 2_000;
    private static final int AGGIORNATI = 1_000;
    private static final int RIMANDATI = 1_000;
    private static final int PRIMA_VERSIONE = 1;
    private static final int SECONDA_VERSIONE = 2;
    private static final String PREFISSO_MOVIMENTI = "M";
    private static final String FILE_DEL_GIORNO = "giorno";
    private static final int CONTI = 500;
    private static final BigDecimal IMPORTO_MINIMO = new BigDecimal("10.00");

    private Righe() {
    }

    static List<Riga> iniziali() {
        return senzaCondizione(PREFISSO_MOVIMENTI, "iniziali");
    }

    static List<Riga> delGiorno() {
        var righe = new ArrayList<Riga>();
        aggiungi(righe, RIGHE_PER_FILE + 1, NUOVI, PRIMA_VERSIONE, Condizione.SOLO_SE_NUOVO);
        aggiungi(righe, 1, GIA_PRESENTI, PRIMA_VERSIONE, Condizione.SOLO_SE_NUOVO);
        aggiungi(righe, GIA_PRESENTI + 1, AGGIORNATI, SECONDA_VERSIONE, Condizione.SOLO_SE_PIU_NUOVO);
        aggiungi(righe, GIA_PRESENTI + AGGIORNATI + 1, RIMANDATI, PRIMA_VERSIONE, Condizione.SOLO_SE_PIU_NUOVO);
        return righe;
    }

    static List<Riga> senzaCondizione(String prefisso, String file) {
        return IntStream.rangeClosed(1, RIGHE_PER_FILE)
                .mapToObj(numero -> new Riga(movimento(prefisso, numero, PRIMA_VERSIONE, file), Condizione.NESSUNA))
                .toList();
    }

    private static void aggiungi(List<Riga> righe, int primo, int quanti, int versione, Condizione condizione) {
        for (int numero = primo; numero < primo + quanti; numero++) {
            righe.add(new Riga(movimento(PREFISSO_MOVIMENTI, numero, versione, FILE_DEL_GIORNO), condizione));
        }
    }

    private static Map<String, AttributeValue> movimento(String prefisso, int numero, int versione, String file) {
        var item = new LinkedHashMap<String, AttributeValue>();
        item.put("id", AttributeValue.fromS("%s%05d".formatted(prefisso, numero)));
        item.put("conto", AttributeValue.fromS("C%03d".formatted(numero % CONTI)));
        item.put("importo", AttributeValue.fromN(BigDecimal.valueOf(numero, 2).add(IMPORTO_MINIMO).toPlainString()));
        item.put("versione", AttributeValue.fromN(Integer.toString(versione)));
        item.put("file", AttributeValue.fromS(file));
        return item;
    }
}
