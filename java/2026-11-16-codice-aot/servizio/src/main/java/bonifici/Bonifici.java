package bonifici;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface Bonifici extends JpaRepository<Bonifico, Long> {

    List<Bonifico> findTop20ByOrdinanteOrBeneficiarioOrderByIdDesc(String ordinante, String beneficiario);
}
