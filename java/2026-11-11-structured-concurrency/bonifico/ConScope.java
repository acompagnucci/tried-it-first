package bonifico;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;

final class ConScope {

    private final Controlli controlli;

    ConScope(Controlli controlli) {
        this.controlli = controlli;
    }

    Verifiche verifica(Bonifico bonifico) throws ExecutionException, InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            var saldo = scope.fork(controlli::saldo);
            var limite = scope.fork(controlli::limiteResiduo);
            var antifrode = scope.fork(() -> controlli.rischio(bonifico));
            scope.join();
            return new Verifiche(saldo.get(), limite.get(), antifrode.get());
        }
    }
}
