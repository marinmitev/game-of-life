package life.net;

/** Thrown when a line received over the network is not a valid {@link Message}. */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
