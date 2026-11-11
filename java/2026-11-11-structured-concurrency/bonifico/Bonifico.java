package bonifico;

import java.math.BigDecimal;

record Bonifico(String ibanBeneficiario, BigDecimal importo) {
}
