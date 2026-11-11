package bonifico;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

final class ConExecutor {

    private final ExecutorService esecutore;
    private final Controlli controlli;

    ConExecutor(ExecutorService esecutore, Controlli controlli) {
        this.esecutore = esecutore;
        this.controlli = controlli;
    }

    Verifiche verifica(Bonifico bonifico) throws ExecutionException, InterruptedException {
        var saldo = esecutore.submit(controlli::saldo);
        var limite = esecutore.submit(controlli::limiteResiduo);
        var antifrode = esecutore.submit(() -> controlli.rischio(bonifico));
        return new Verifiche(saldo.get(), limite.get(), antifrode.get());
    }

    Verifiche verificaAntifrodePrima(Bonifico bonifico) throws ExecutionException, InterruptedException {
        var saldo = esecutore.submit(controlli::saldo);
        var limite = esecutore.submit(controlli::limiteResiduo);
        var antifrode = esecutore.submit(() -> controlli.rischio(bonifico));
        var rischio = antifrode.get();
        return new Verifiche(saldo.get(), limite.get(), rischio);
    }
}
