package bonifici;

record Ricevuto(String id, Bonifico bonifico, int ricezioni) {

    boolean ricevutoDiNuovo() {
        return ricezioni > 1;
    }
}
