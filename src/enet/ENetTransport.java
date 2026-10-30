package enet;

import java.io.IOException;

/**
 * Minimal UDP abstraction, already bound to the remote server address.
 * MIDP implementation: enet.midp.DatagramTransport. Java SE (tests): UdpTransport.
 */
public interface ENetTransport {
    /** Send one datagram to the server. */
    void send(byte[] data, int off, int len) throws IOException;

    /** Block until a datagram arrives; copy it to buf and return its length. */
    int receive(byte[] buf) throws IOException;

    /** Close the socket; must make a blocked receive() throw IOException. */
    void close();
}
