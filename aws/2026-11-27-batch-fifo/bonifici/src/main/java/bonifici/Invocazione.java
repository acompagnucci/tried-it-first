package bonifici;

record Invocazione(String prova, String id, int messaggi, int conti, int byteEvento,
                   int ricevutiDiNuovo, long inizio, long fine) {

    long durata() {
        return fine - inizio;
    }
}
