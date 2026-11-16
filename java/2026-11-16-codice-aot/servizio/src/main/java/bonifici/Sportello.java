package bonifici;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Sportello {

    private final Conti conti;
    private final Bonifici bonifici;

    Sportello(Conti conti, Bonifici bonifici) {
        this.conti = conti;
        this.bonifici = bonifici;
    }

    @Transactional
    public Movimento esegui(RichiestaBonifico richiesta) {
        var ordinante = conto(richiesta.ordinante());
        var beneficiario = conto(richiesta.beneficiario());
        ordinante.addebita(richiesta.importo());
        beneficiario.accredita(richiesta.importo());
        return bonifici.save(new Bonifico(richiesta, Instant.now())).comeMovimento();
    }

    @Transactional(readOnly = true)
    public List<Movimento> ultimiMovimenti(String iban) {
        if (!conti.existsById(iban)) {
            throw new ContoInesistente(iban);
        }
        return bonifici.findTop20ByOrdinanteOrBeneficiarioOrderByIdDesc(iban, iban).stream()
                .map(Bonifico::comeMovimento)
                .toList();
    }

    private Conto conto(String iban) {
        return conti.findById(iban).orElseThrow(() -> new ContoInesistente(iban));
    }
}
