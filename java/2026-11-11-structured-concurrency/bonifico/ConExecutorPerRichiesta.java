package bonifico;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

final class ConExecutorPerRichiesta {

    private final Controlli controlli;

    ConExecutorPerRichiesta(Controlli controlli) {
        this.controlli = controlli;
    }

    Verifiche verifica(Bonifico bonifico) throws ExecutionException, InterruptedException {
        try (var esecutore = Executors.newVirtualThreadPerTaskExecutor()) {
            var saldo = esecutore.submit(controlli::saldo);
            var limite = esecutore.submit(controlli::limiteResiduo);
            var antifrode = esecutore.submit(() -> controlli.rischio(bonifico));
            var rischio = antifrode.get();
            return new Verifiche(saldo.get(), limite.get(), rischio);
        }
    }
}
