package bonifici;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class SaldoInsufficiente extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SaldoInsufficiente(String iban) {
        super("saldo insufficiente sul conto " + iban);
    }
}
