package giro;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.core.util.VersionInfo;

final class Registro {

    private Registro() {
    }

    static void giro(Context context, String prova, int giro) {
        context.getLogger().log("prova=%s giro=%d sdk=%s variabile=%s proprieta=%s".formatted(
                prova, giro, VersionInfo.SDK_VERSION,
                System.getenv("_X_AMZN_TRACE_ID"),
                System.getProperty("com.amazonaws.xray.traceHeader")));
    }
}
