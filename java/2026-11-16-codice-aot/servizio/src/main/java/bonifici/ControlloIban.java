package bonifici;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ControlloIban implements ConstraintValidator<IbanValido, String> {

    private static final Pattern IBAN_ITALIANO = Pattern.compile("IT\\d{2}[A-Z]\\d{10}[0-9A-Z]{12}");
    private static final BigInteger MODULO = BigInteger.valueOf(97);

    @Override
    public boolean isValid(String iban, ConstraintValidatorContext contesto) {
        return iban != null && IBAN_ITALIANO.matcher(iban).matches() && cifreDiControlloGiuste(iban);
    }

    private static boolean cifreDiControlloGiuste(String iban) {
        var riordinato = iban.substring(4) + iban.substring(0, 4);
        var numero = riordinato.chars()
                .mapToObj(carattere -> String.valueOf(Character.getNumericValue(carattere)))
                .collect(Collectors.joining());
        return new BigInteger(numero).mod(MODULO).equals(BigInteger.ONE);
    }
}
