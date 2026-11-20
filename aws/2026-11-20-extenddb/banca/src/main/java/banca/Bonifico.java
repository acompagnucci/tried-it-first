package banca;

import java.math.BigDecimal;

record Bonifico(String id, String da, String a, BigDecimal importo) {
}
