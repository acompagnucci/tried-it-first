package saluto;

import java.util.Map;

public class Saluto {

    public String saluta(Map<String, Object> evento) {
        return "Ciao da Java " + Runtime.version().feature() + ", evento " + evento;
    }
}
