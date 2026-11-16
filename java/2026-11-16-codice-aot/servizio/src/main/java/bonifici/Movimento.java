package bonifici;

import java.math.BigDecimal;
import java.time.Instant;

public record Movimento(
        Long id, String ordinante, String beneficiario, BigDecimal importo, String causale, Instant eseguitoIl) {
}
