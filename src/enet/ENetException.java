package enet;

/** Misuse of the API (e.g. send before connect). Unchecked; CLDC has no IllegalStateException. */
public class ENetException extends RuntimeException {
    public ENetException(String message) {
        super(message);
    }
}
