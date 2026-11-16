package bonifici;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ContoInesistente extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ContoInesistente(String iban) {
        super("conto " + iban + " inesistente");
    }
}
