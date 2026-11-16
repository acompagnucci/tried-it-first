package bonifici;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RichiestaBonifico(
        @IbanValido String ordinante,
        @IbanValido String beneficiario,
        @NotNull @DecimalMin("0.01") @Digits(integer = 9, fraction = 2) BigDecimal importo,
        @NotNull @Size(max = 140) String causale) {
}
