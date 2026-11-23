package bonifico;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;

record TassiDiCambio(Map<Currency, BigDecimal> euroPerUnita) {

    private static final int DECIMALI_EURO = 2;

    BigDecimal inEuro(BigDecimal importo, Currency valuta) {
        var tasso = euroPerUnita.get(valuta);
        if (tasso == null) {
            throw new IllegalArgumentException("nessun tasso di cambio per " + valuta);
        }
        return importo.multiply(tasso).setScale(DECIMALI_EURO, RoundingMode.HALF_EVEN);
    }
}
