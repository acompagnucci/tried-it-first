package bonifico;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

final class Importi {

    static final Currency EURO = Currency.getInstance("EUR");
    static final Currency DOLLARO = Currency.getInstance("USD");

    private Importi() {
    }

    static String formatta(BigDecimal importo, Currency valuta) {
        var formato = NumberFormat.getCurrencyInstance(Locale.ITALY);
        formato.setCurrency(valuta);
        return formato.format(importo);
    }
}
