package bonifici;

import org.springframework.data.jpa.repository.JpaRepository;

interface Conti extends JpaRepository<Conto, String> {
}
