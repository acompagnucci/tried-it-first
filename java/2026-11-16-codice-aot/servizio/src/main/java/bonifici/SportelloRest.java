package bonifici;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SportelloRest {

    private final Sportello sportello;

    SportelloRest(Sportello sportello) {
        this.sportello = sportello;
    }

    @PostMapping("/bonifici")
    @ResponseStatus(HttpStatus.CREATED)
    public Movimento esegui(@Valid @RequestBody RichiestaBonifico richiesta) {
        return sportello.esegui(richiesta);
    }

    @GetMapping("/conti/{iban}/movimenti")
    public List<Movimento> movimenti(@PathVariable String iban) {
        return sportello.ultimiMovimenti(iban);
    }
}
