package enet.midp;

import java.io.IOException;
import javax.microedition.io.Connector;
import javax.microedition.io.Datagram;
import javax.microedition.io.DatagramConnection;
import javax.microedition.io.UDPDatagramConnection;

import enet.ENetTransport;

/**
 * MIDP 2.0 / CLDC GCF transport: Connector.open("datagram://host:port").
 * Note: datagram:// is optional in MIDP 2.0; most real devices/emulators have it.
 */
public class DatagramTransport implements ENetTransport {
    private DatagramConnection conn;
    private Datagram rx;
    private final int maxLength;

    public DatagramTransport(String host, int port) throws IOException {
        conn = (DatagramConnection) Connector.open("datagram://" + host + ":" + port);
        int max = 0;
        try {
            max = conn.getMaximumLength();
        } catch (IOException e) {
            max = 0;
        }
        maxLength = max;
        int size = 2048;
        if (max > 0 && max < size) size = max;
        rx = conn.newDatagram(size);

    }

    /** Max datagram size the platform allows (0 = unknown). Use it to pick ENetClient.setMtu(). */
    public int getMaximumLength() {
        return maxLength;
    }

    public void send(byte[] data, int off, int len) throws IOException {
        byte[] buf = data;
        if (off != 0) {
            buf = new byte[len];
            System.arraycopy(data, off, buf, 0, len);
        }
        Datagram d = conn.newDatagram(buf, len);
        conn.send(d);
    }

    public int receive(byte[] buf) throws IOException {
        byte[] data = rx.getData();
        rx.setData(data, 0, data.length);
        conn.receive(rx);
        int n = rx.getLength();
        int off = rx.getOffset();
        if (n > buf.length) n = buf.length;
        System.arraycopy(rx.getData(), off, buf, 0, n);
        return n;
    }

    public void close() {
        try {
            conn.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
