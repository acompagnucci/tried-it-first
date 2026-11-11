package bonifico;

import java.math.BigDecimal;

record Verifiche(BigDecimal saldo, BigDecimal limiteResiduo, int rischio) {
}
