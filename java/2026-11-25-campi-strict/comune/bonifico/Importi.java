package bonifico;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

final class Importi {

    private static final int DECIMALI = 2;

    private Importi() {
    }

    static String formatta(long centesimi) {
        var formato = NumberFormat.getNumberInstance(Locale.ITALY);
        formato.setMinimumFractionDigits(DECIMALI);
        return formato.format(BigDecimal.valueOf(centesimi, DECIMALI));
    }

    static String formatta(long centesimi, String valuta) {
        return formatta(centesimi) + " " + valuta;
    }
}
