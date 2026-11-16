package bonifici;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class Bonifico {

    @Id
    @GeneratedValue
    private Long id;

    private String ordinante;
    private String beneficiario;
    private BigDecimal importo;
    private String causale;
    private Instant eseguitoIl;

    protected Bonifico() {
    }

    Bonifico(RichiestaBonifico richiesta, Instant eseguitoIl) {
        this.ordinante = richiesta.ordinante();
        this.beneficiario = richiesta.beneficiario();
        this.importo = richiesta.importo();
        this.causale = richiesta.causale();
        this.eseguitoIl = eseguitoIl;
    }

    Movimento comeMovimento() {
        return new Movimento(id, ordinante, beneficiario, importo, causale, eseguitoIl);
    }
}
