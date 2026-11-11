package bonifico;

import java.util.concurrent.ExecutionException;

@FunctionalInterface
interface Verificatore {

    Verifiche verifica(Bonifico bonifico) throws ExecutionException, InterruptedException;
}
