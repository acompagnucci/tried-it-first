package bonifico;

import java.math.BigDecimal;
import java.util.Currency;

record Bonifico(String iban, BigDecimal importo, Currency valuta) {
}
