package enet;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Random;
import java.util.Vector;

/**
 * Client-side ENet 1.3.x implementation for J2ME (CLDC 1.1 / MIDP 2.0).
 * One instance = one connection to one server. API mirrors enet_host_service():
 *
 *   ENetClient c = new ENetClient(transport);
 *   c.connect(2, 0);
 *   ENetEvent e = c.service(100);   // pump; call regularly
 *   c.send(0, bytes, ENetPacket.FLAG_RELIABLE);
 *
 * Supported: reliable/unreliable/unsequenced send+receive, fragmentation,
 * ACK/retransmit/RTT, ping, timeouts, optional CRC32.
 * Not supported: range-coder compression, bandwidth throttling.
 */
public class ENetClient {
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_DISCONNECTING = 3;

    private static final int LOCAL_PEER_ID = 0;
    private static final int THROTTLE_INTERVAL = 5000;
    private static final int THROTTLE_ACCEL = 2;
    private static final int THROTTLE_DECEL = 2;
    private static final int MAX_IN_FLIGHT = 256;
    private static final int MAX_REORDER_WINDOW = 0x4000;
    private static final int FRAGMENT_HEADER = 24;

    // ---- internal records ----

    private static final class OutCmd {
        byte[] bytes;
        int channel;
        int seq;
        int cmdNum;
        int sentTime;
        int firstSent;
        int rto;
        int rtoLimit;
        boolean needsResend;
    }

    private static final class Incoming {
        int type;
        int startSeq;
        int fragCount, fragNum, total, fragOffset;
        byte[] data;
    }

    private static final class FragAsm {
        int startSeq;
        int count;
        int received;
        byte[] buf;
        boolean[] got;
    }

    private static final class Channel {
        int outReliableSeq;
        int outUnreliableSeq;
        int inReliableSeq;
        int inUnreliableSeq;
        Hashtable pending = new Hashtable();
        FragAsm reliableAsm;
        FragAsm unreliableAsm;
    }

    // ---- state ----

    private final ENetTransport transport;
    private final Vector inbound = new Vector();      // byte[] datagrams from receiver thread
    private final Vector events = new Vector();       // ENetEvent
    private final Vector acks = new Vector();         // byte[8]
    private final Vector waiting = new Vector();      // OutCmd not yet sent
    private final Vector inFlight = new Vector();     // OutCmd awaiting ACK
    private final Vector unreliableQueue = new Vector(); // byte[]
    private final Random random = new Random();
    private final long startTime = System.currentTimeMillis();

    private int state = STATE_DISCONNECTED;
    private int mtu = ENet.DEFAULT_MTU;
    private int windowSize = ENet.MAX_WINDOW_SIZE;
    private boolean checksum = false;
    private int maxPacketSize = 4 * 1024 * 1024;
    private int pingInterval = 500;
    private int timeoutLimit = 32;
    private int timeoutMin = 5000;
    private int timeoutMax = 30000;

    private int channelCount;
    private Channel[] channels;
    private byte[] connectId = new byte[4];
    private int connectData;
    private int disconnectData;
    private int outPeerID = ENet.MAX_PEER_ID;
    private int inSessionID = 0xFF;
    private int outSessionID = 0xFF;
    private int outReliableSeq;       // peer-level, for channel 0xFF commands
    private int outUnseqGroup;
    private int inUnseqGroup;
    private int[] unseqWindow = new int[32];

    private int rtt = 500;
    private int rttVar = 0;
    private int lastSendTime;
    private int lastReceiveTime;
    private int flushTime;
    private int packetsLost;
    private IOException lastError;

    private byte[] dg;
    private int dgPos;

    private Thread receiver;
    private volatile boolean running;

    public ENetClient(ENetTransport transport) {
        this.transport = transport;
    }

    // ---- configuration (call before connect) ----

    public synchronized void setMtu(int mtu) {
        if (state != STATE_DISCONNECTED) throw new ENetException("change settings before connect()");
        if (mtu < ENet.MIN_MTU) mtu = ENet.MIN_MTU;
        if (mtu > ENet.MAX_MTU) mtu = ENet.MAX_MTU;
        this.mtu = mtu;
    }

    /** Enable only if the server host has a checksum callback (enet_crc32) set. */
    public synchronized void setChecksum(boolean on) {
        if (state != STATE_DISCONNECTED) throw new ENetException("change settings before connect()");
        this.checksum = on;
    }

    public synchronized void setMaxPacketSize(int bytes) { this.maxPacketSize = bytes; }

    public synchronized void setPingInterval(int ms) { this.pingInterval = ms; }

    /** Same meaning as enet_peer_timeout(): limit, minimum ms, maximum ms. */
    public synchronized void setTimeout(int limit, int minMs, int maxMs) {
        this.timeoutLimit = limit;
        this.timeoutMin = minMs;
        this.timeoutMax = maxMs;
    }

    // ---- getters ----

    public synchronized int getState() { return state; }
    public synchronized int getRoundTripTime() { return rtt; }
    public synchronized int getPacketsLost() { return packetsLost; }
    public synchronized int getMtu() { return mtu; }
    public synchronized int getChannelCount() { return channelCount; }
    public synchronized IOException getLastError() { return lastError; }

    // ---- connection management ----

    public synchronized void connect(int channels, int data) {
        if (state != STATE_DISCONNECTED) throw new ENetException("already connected");
        if (channels < 1 || channels > ENet.MAX_CHANNELS) throw new IllegalArgumentException("channels");
        resetConnection();
        channelCount = channels;
        this.channels = new Channel[channels];
        for (int i = 0; i < channels; i++) this.channels[i] = new Channel();
        connectData = data;
        random.setSeed(System.currentTimeMillis() ^ (long) System.identityHashCode(this));
        ENet.putU32(connectId, 0, random.nextInt());
        state = STATE_CONNECTING;
        lastSendTime = lastReceiveTime = now();
        startReceiver();

        byte[] c = new byte[48];
        int seq = nextPeerSeq();
        c[0] = (byte) (ENet.CMD_CONNECT | ENet.CMD_FLAG_ACK);
        c[1] = (byte) 0xFF;
        ENet.putU16(c, 2, seq);
        ENet.putU16(c, 4, LOCAL_PEER_ID);
        c[6] = (byte) 0xFF;
        c[7] = (byte) 0xFF;
        ENet.putU32(c, 8, mtu);
        ENet.putU32(c, 12, windowSize);
        ENet.putU32(c, 16, channels);
        ENet.putU32(c, 20, 0);
        ENet.putU32(c, 24, 0);
        ENet.putU32(c, 28, THROTTLE_INTERVAL);
        ENet.putU32(c, 32, THROTTLE_ACCEL);
        ENet.putU32(c, 36, THROTTLE_DECEL);
        System.arraycopy(connectId, 0, c, 40, 4);
        ENet.putU32(c, 44, data);
        queueReliable(c, 0xFF, seq);
        flushInternal();
    }

    /** Graceful disconnect: sends DISCONNECT, waits for ACK, then emits a DISCONNECT event. */
    public synchronized void disconnect(int data) {
        if (state == STATE_DISCONNECTED || state == STATE_DISCONNECTING) return;
        if (state == STATE_CONNECTING) {
            disconnectNow(data);
            events.addElement(new ENetEvent(ENetEvent.DISCONNECT, 0, null, data));
            return;
        }
        waiting.removeAllElements();
        inFlight.removeAllElements();
        unreliableQueue.removeAllElements();
        state = STATE_DISCONNECTING;
        disconnectData = data;
        byte[] c = new byte[8];
        int seq = nextPeerSeq();
        c[0] = (byte) (ENet.CMD_DISCONNECT | ENet.CMD_FLAG_ACK);
        c[1] = (byte) 0xFF;
        ENet.putU16(c, 2, seq);
        ENet.putU32(c, 4, data);
        queueReliable(c, 0xFF, seq);
        flushInternal();
    }

    /** Immediate disconnect: one unreliable DISCONNECT datagram, no event. */
    public synchronized void disconnectNow(int data) {
        if (state == STATE_DISCONNECTED) return;
        if (state != STATE_DISCONNECTING) {
            waiting.removeAllElements();
            inFlight.removeAllElements();
            unreliableQueue.removeAllElements();
            acks.removeAllElements();
            byte[] c = new byte[8];
            c[0] = (byte) (ENet.CMD_DISCONNECT | ENet.CMD_FLAG_UNSEQUENCED);
            c[1] = (byte) 0xFF;
            ENet.putU16(c, 2, nextPeerSeq());
            ENet.putU32(c, 4, data);
            flushTime = now();
            beginDatagram();
            addCmd(c);
            sendDatagram();
        }
        state = STATE_DISCONNECTED;
        resetQueues();
    }

    /** Stop the receiver thread and close the socket. */
    public void close() {
        synchronized (this) {
            running = false;
            state = STATE_DISCONNECTED;
        }
        transport.close();
        synchronized (inbound) { inbound.notifyAll(); }
    }

    // ---- sending ----

    /** Queue a packet. Data goes out on the next service()/flush(). */
    public synchronized void send(int channelID, byte[] data, int flags) {
        if (state != STATE_CONNECTED) throw new ENetException("not connected");
        if (channelID < 0 || channelID >= channelCount) throw new IllegalArgumentException("channel");
        int len = data.length;
        if (len > maxPacketSize) throw new IllegalArgumentException("packet too large");
        Channel ch = channels[channelID];
        int fragLen = mtu - headerSize() - FRAGMENT_HEADER;

        if (len > fragLen) {
            boolean unrel = (flags & (ENetPacket.FLAG_RELIABLE | ENetPacket.FLAG_UNRELIABLE_FRAGMENT))
                    == ENetPacket.FLAG_UNRELIABLE_FRAGMENT && ch.outUnreliableSeq < 0xFFFF;
            int count = (len + fragLen - 1) / fragLen;
            int startSeq;
            if (unrel) {
                startSeq = (ch.outUnreliableSeq + 1) & 0xFFFF;
                ch.outUnreliableSeq = startSeq;
            } else {
                startSeq = (ch.outReliableSeq + 1) & 0xFFFF;
            }
            int off = 0;
            for (int n = 0; n < count; n++) {
                int l = Math.min(fragLen, len - off);
                byte[] c = new byte[FRAGMENT_HEADER + l];
                c[1] = (byte) channelID;
                int seq;
                if (unrel) {
                    c[0] = (byte) ENet.CMD_SEND_UNRELIABLE_FRAGMENT;
                    seq = ch.outReliableSeq;
                } else {
                    c[0] = (byte) (ENet.CMD_SEND_FRAGMENT | ENet.CMD_FLAG_ACK);
                    ch.outReliableSeq = (ch.outReliableSeq + 1) & 0xFFFF;
                    ch.outUnreliableSeq = 0;
                    seq = ch.outReliableSeq;
                }
                ENet.putU16(c, 2, seq);
                ENet.putU16(c, 4, startSeq);
                ENet.putU16(c, 6, l);
                ENet.putU32(c, 8, count);
                ENet.putU32(c, 12, n);
                ENet.putU32(c, 16, len);
                ENet.putU32(c, 20, off);
                System.arraycopy(data, off, c, FRAGMENT_HEADER, l);
                if (unrel) unreliableQueue.addElement(c);
                else queueReliable(c, channelID, seq);
                off += l;
            }
            return;
        }

        boolean reliable = (flags & ENetPacket.FLAG_RELIABLE) != 0;
        boolean unseq = !reliable && (flags & ENetPacket.FLAG_UNSEQUENCED) != 0;
        if (!reliable && !unseq && ch.outUnreliableSeq >= 0xFFFF) reliable = true;

        if (reliable) {
            byte[] c = new byte[6 + len];
            ch.outReliableSeq = (ch.outReliableSeq + 1) & 0xFFFF;
            ch.outUnreliableSeq = 0;
            c[0] = (byte) (ENet.CMD_SEND_RELIABLE | ENet.CMD_FLAG_ACK);
            c[1] = (byte) channelID;
            ENet.putU16(c, 2, ch.outReliableSeq);
            ENet.putU16(c, 4, len);
            System.arraycopy(data, 0, c, 6, len);
            queueReliable(c, channelID, ch.outReliableSeq);
        } else if (unseq) {
            byte[] c = new byte[8 + len];
            outUnseqGroup = (outUnseqGroup + 1) & 0xFFFF;
            c[0] = (byte) (ENet.CMD_SEND_UNSEQUENCED | ENet.CMD_FLAG_UNSEQUENCED);
            c[1] = (byte) channelID;
            ENet.putU16(c, 4, outUnseqGroup);
            ENet.putU16(c, 6, len);
            System.arraycopy(data, 0, c, 8, len);
            unreliableQueue.addElement(c);
        } else {
            byte[] c = new byte[8 + len];
            ch.outUnreliableSeq++;
            c[0] = (byte) ENet.CMD_SEND_UNRELIABLE;
            c[1] = (byte) channelID;
            ENet.putU16(c, 2, ch.outReliableSeq);
            ENet.putU16(c, 4, ch.outUnreliableSeq);
            ENet.putU16(c, 6, len);
            System.arraycopy(data, 0, c, 8, len);
            unreliableQueue.addElement(c);
        }
    }

    /** Send everything queued right now (like enet_host_flush). */
    public synchronized void flush() {
        flushInternal();
    }

    // ---- service loop ----

    /**
     * Pump the protocol: read datagrams, handle ACKs/timeouts, send queued data.
     * Returns the next event or null if nothing happened within timeoutMs.
     */
    public ENetEvent service(int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        for (;;) {
            synchronized (this) {
                ENetEvent e = nextEvent();
                if (e != null) return e;
                processInbound();
                checkTimeouts();
                flushInternal();
                e = nextEvent();
                if (e != null) return e;
            }
            long remain = end - System.currentTimeMillis();
            if (remain <= 0) return null;
            synchronized (inbound) {
                if (inbound.isEmpty()) {
                    try {
                        inbound.wait(remain > 15 ? 15 : remain);
                    } catch (InterruptedException ie) {
                        return null;
                    }
                }
            }
        }
    }

    // ---- internals: time and sequence ----

    private int now() {
        return (int) (System.currentTimeMillis() - startTime);
    }

    private int headerSize() {
        return checksum ? 8 : 4;
    }

    private int nextPeerSeq() {
        outReliableSeq = (outReliableSeq + 1) & 0xFFFF;
        return outReliableSeq;
    }

    private int initialRto() {
        int r = rtt + 4 * rttVar;
        return r < 100 ? 100 : r;
    }

    private ENetEvent nextEvent() {
        if (events.isEmpty()) return null;
        ENetEvent e = (ENetEvent) events.elementAt(0);
        events.removeElementAt(0);
        return e;
    }

    private void resetQueues() {
        waiting.removeAllElements();
        inFlight.removeAllElements();
        unreliableQueue.removeAllElements();
        acks.removeAllElements();
    }

    private void resetConnection() {
        resetQueues();
        events.removeAllElements();
        synchronized (inbound) { inbound.removeAllElements(); }
        outPeerID = ENet.MAX_PEER_ID;
        inSessionID = 0xFF;
        outSessionID = 0xFF;
        outReliableSeq = 0;
        outUnseqGroup = 0;
        inUnseqGroup = 0;
        unseqWindow = new int[32];
        rtt = 500;
        rttVar = 0;
        packetsLost = 0;
    }

    private void queueReliable(byte[] cmd, int channel, int seq) {
        OutCmd o = new OutCmd();
        o.bytes = cmd;
        o.channel = channel;
        o.seq = seq;
        o.cmdNum = cmd[0] & ENet.CMD_MASK;
        waiting.addElement(o);
    }

    // ---- receiver thread ----

    private void startReceiver() {
        if (running) return;
        running = true;
        receiver = new Thread() {
            public void run() {
                byte[] buf = new byte[ENet.MAX_MTU + 64];
                while (running) {
                    try {
                        int n = transport.receive(buf);
                        if (n > 0) {
                            byte[] copy = new byte[n];
                            System.arraycopy(buf, 0, copy, 0, n);
                            synchronized (inbound) {
                                inbound.addElement(copy);
                                inbound.notifyAll();
                            }
                        }
                    } catch (IOException ioe) {
                        if (!running) break;
                        lastError = ioe;
                        try { Thread.sleep(20); } catch (InterruptedException ie) { break; }
                    } catch (RuntimeException re) {
                        if (!running) break;
                        try { Thread.sleep(20); } catch (InterruptedException ie) { break; }
                    }
                }
            }
        };
        receiver.start();
    }

    // ---- outgoing datagram assembly ----

    private void flushInternal() {
        if (state == STATE_DISCONNECTED) return;
        int t = now();
        flushTime = t;
        if (state == STATE_CONNECTED && waiting.isEmpty() && inFlight.isEmpty()
                && t - lastSendTime >= pingInterval) {
            byte[] p = new byte[4];
            int seq = nextPeerSeq();
            p[0] = (byte) (ENet.CMD_PING | ENet.CMD_FLAG_ACK);
            p[1] = (byte) 0xFF;
            ENet.putU16(p, 2, seq);
            queueReliable(p, 0xFF, seq);
        }
        beginDatagram();
        for (int i = 0; i < acks.size(); i++) addCmd((byte[]) acks.elementAt(i));
        acks.removeAllElements();
        for (int i = 0; i < inFlight.size(); i++) {
            OutCmd o = (OutCmd) inFlight.elementAt(i);
            if (o.needsResend) {
                o.needsResend = false;
                o.sentTime = t;
                addCmd(o.bytes);
            }
        }
        while (!waiting.isEmpty() && inFlight.size() < MAX_IN_FLIGHT) {
            OutCmd o = (OutCmd) waiting.elementAt(0);
            waiting.removeElementAt(0);
            o.sentTime = t;
            o.firstSent = t;
            o.rto = initialRto();
            o.rtoLimit = timeoutLimit * o.rto;
            inFlight.addElement(o);
            addCmd(o.bytes);
        }
        for (int i = 0; i < unreliableQueue.size(); i++) addCmd((byte[]) unreliableQueue.elementAt(i));
        unreliableQueue.removeAllElements();
        sendDatagram();
    }

    private void beginDatagram() {
        dg = new byte[mtu];
        dgPos = headerSize();
    }

    private void addCmd(byte[] c) {
        if (dgPos + c.length > mtu) {
            sendDatagram();
            beginDatagram();
            if (dgPos + c.length > mtu) throw new ENetException("command exceeds MTU");
        }
        System.arraycopy(c, 0, dg, dgPos, c.length);
        dgPos += c.length;
    }

    private void sendDatagram() {
        int hdr = headerSize();
        if (dgPos <= hdr) return;
        int word = ENet.HEADER_FLAG_SENT_TIME;
        if (outPeerID < ENet.MAX_PEER_ID) {
            word |= outPeerID | ((outSessionID & 3) << ENet.HEADER_SESSION_SHIFT);
        } else {
            word |= ENet.MAX_PEER_ID;
        }
        ENet.putU16(dg, 0, word);
        ENet.putU16(dg, 2, flushTime & 0xFFFF);
        if (checksum) {
            if (outPeerID < ENet.MAX_PEER_ID) System.arraycopy(connectId, 0, dg, 4, 4);
            else for (int i = 4; i < 8; i++) dg[i] = 0;
            ENet.putU32(dg, 4, ENet.crc32(dg, 0, dgPos));
        }
        try {
            transport.send(dg, 0, dgPos);
        } catch (IOException e) {
            lastError = e;
        }
        lastSendTime = now();
        dgPos = hdr;
    }

    // ---- timeouts ----

    private void checkTimeouts() {
        if (state == STATE_DISCONNECTED) return;
        int t = now();
        for (int i = 0; i < inFlight.size(); i++) {
            OutCmd o = (OutCmd) inFlight.elementAt(i);
            if (t - o.sentTime < o.rto) continue;
            int elapsed = t - o.firstSent;
            if (elapsed >= timeoutMax || (o.rto >= o.rtoLimit && elapsed >= timeoutMin)) {
                state = STATE_DISCONNECTED;
                resetQueues();
                events.addElement(new ENetEvent(ENetEvent.DISCONNECT, 0, null, 0));
                return;
            }
            if (!o.needsResend) {
                o.needsResend = true;
                o.rto *= 2;
                packetsLost++;
            }
        }
    }

    // ---- incoming ----

    private void processInbound() {
        for (;;) {
            byte[] d;
            synchronized (inbound) {
                if (inbound.isEmpty()) break;
                d = (byte[]) inbound.elementAt(0);
                inbound.removeElementAt(0);
            }
            if (state == STATE_DISCONNECTED) continue;
            handleDatagram(d);
        }
    }

    private void handleDatagram(byte[] d) {
        int len = d.length;
        if (len < 2) return;
        int word = ENet.getU16(d, 0);
        int pid = word & ENet.MAX_PEER_ID;
        int session = (word & ENet.HEADER_SESSION_MASK) >> ENet.HEADER_SESSION_SHIFT;
        boolean hasTime = (word & ENet.HEADER_FLAG_SENT_TIME) != 0;
        if ((word & ENet.HEADER_FLAG_COMPRESSED) != 0) return;   // range coder not supported
        if (pid != LOCAL_PEER_ID) return;
        if (outPeerID < ENet.MAX_PEER_ID && session != inSessionID) return;
        int hdr = hasTime ? 4 : 2;
        if (len < hdr) return;
        int sentTime = hasTime ? ENet.getU16(d, 2) : 0;
        if (checksum) {
            if (len < hdr + 4) return;
            int desired = ENet.getU32(d, hdr);
            byte[] copy = new byte[len];
            System.arraycopy(d, 0, copy, 0, len);
            System.arraycopy(connectId, 0, copy, hdr, 4);
            if (ENet.crc32(copy, 0, len) != desired) return;
            hdr += 4;
        }
        lastReceiveTime = now();

        int pos = hdr;
        while (pos + 4 <= len && state != STATE_DISCONNECTED) {
            int cmdByte = d[pos] & 0xFF;
            int num = cmdByte & ENet.CMD_MASK;
            if (num == ENet.CMD_NONE || num > ENet.CMD_SEND_UNRELIABLE_FRAGMENT) break;
            int size = ENet.COMMAND_SIZE[num];
            if (pos + size > len) break;
            int chan = d[pos + 1] & 0xFF;
            int seq = ENet.getU16(d, pos + 2);

            if ((cmdByte & ENet.CMD_FLAG_ACK) != 0) {
                if (!hasTime) break;
                if (state == STATE_CONNECTING || state == STATE_CONNECTED) queueAck(chan, seq, sentTime);
            }

            int extra = 0;
            boolean ok = true;
            switch (num) {
                case ENet.CMD_ACK:
                    handleAck(chan, ENet.getU16(d, pos + 4), ENet.getU16(d, pos + 6));
                    break;
                case ENet.CMD_VERIFY_CONNECT:
                    ok = handleVerify(d, pos);
                    break;
                case ENet.CMD_DISCONNECT:
                    handleDisconnect(ENet.getU32(d, pos + 4));
                    break;
                case ENet.CMD_PING:
                case ENet.CMD_BANDWIDTH_LIMIT:
                case ENet.CMD_THROTTLE_CONFIGURE:
                case ENet.CMD_CONNECT:
                    break;
                case ENet.CMD_SEND_RELIABLE: {
                    int dl = ENet.getU16(d, pos + 4);
                    extra = dl;
                    if (state != STATE_CONNECTED || chan >= channelCount || pos + size + dl > len) { ok = false; break; }
                    Incoming in = new Incoming();
                    in.type = num;
                    in.data = copyOf(d, pos + size, dl);
                    queueIncomingReliable(chan, seq, in);
                    break;
                }
                case ENet.CMD_SEND_UNRELIABLE: {
                    int dl = ENet.getU16(d, pos + 6);
                    extra = dl;
                    if (state != STATE_CONNECTED || chan >= channelCount || pos + size + dl > len) { ok = false; break; }
                    handleUnreliable(chan, seq, ENet.getU16(d, pos + 4), copyOf(d, pos + size, dl));
                    break;
                }
                case ENet.CMD_SEND_UNSEQUENCED: {
                    int dl = ENet.getU16(d, pos + 6);
                    extra = dl;
                    if (state != STATE_CONNECTED || chan >= channelCount || pos + size + dl > len) { ok = false; break; }
                    handleUnsequenced(chan, ENet.getU16(d, pos + 4), copyOf(d, pos + size, dl));
                    break;
                }
                case ENet.CMD_SEND_FRAGMENT:
                case ENet.CMD_SEND_UNRELIABLE_FRAGMENT: {
                    int dl = ENet.getU16(d, pos + 6);
                    extra = dl;
                    if (state != STATE_CONNECTED || chan >= channelCount || pos + size + dl > len) { ok = false; break; }
                    Incoming in = new Incoming();
                    in.type = num;
                    in.startSeq = ENet.getU16(d, pos + 4);
                    in.fragCount = ENet.getU32(d, pos + 8);
                    in.fragNum = ENet.getU32(d, pos + 12);
                    in.total = ENet.getU32(d, pos + 16);
                    in.fragOffset = ENet.getU32(d, pos + 20);
                    if (in.fragCount <= 0 || in.fragCount > 1024 * 1024 || in.fragNum < 0 || in.fragNum >= in.fragCount
                            || in.total <= 0 || in.total > maxPacketSize || in.fragOffset < 0
                            || in.fragOffset >= in.total || dl > in.total - in.fragOffset) { ok = false; break; }
                    in.data = copyOf(d, pos + size, dl);
                    if (num == ENet.CMD_SEND_FRAGMENT) queueIncomingReliable(chan, seq, in);
                    else handleUnreliableFragment(chan, seq, in);
                    break;
                }
                default:
                    break;
            }
            if (!ok) break;
            pos += size + extra;
        }
    }

    private static byte[] copyOf(byte[] src, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(src, off, r, 0, len);
        return r;
    }

    private void queueAck(int channel, int seq, int sentTime) {
        byte[] a = new byte[8];
        a[0] = (byte) ENet.CMD_ACK;
        a[1] = (byte) channel;
        ENet.putU16(a, 2, seq);
        ENet.putU16(a, 4, seq);
        ENet.putU16(a, 6, sentTime);
        acks.addElement(a);
    }

    private void handleAck(int channel, int seq, int receivedSentTime) {
        OutCmd found = null;
        for (int i = 0; i < inFlight.size(); i++) {
            OutCmd o = (OutCmd) inFlight.elementAt(i);
            if (o.seq == seq && o.channel == channel) {
                found = o;
                inFlight.removeElementAt(i);
                break;
            }
        }
        if (found == null) return;
        int sample = (now() - receivedSentTime) & 0xFFFF;
        if (sample < 0 || sample > 60000) sample = rtt;
        if (sample >= rtt) {
            int diff = sample - rtt;
            rttVar += diff / 4;
            rtt += diff / 8;
        } else {
            int diff = rtt - sample;
            rttVar -= diff / 4;
            rtt -= diff / 8;
        }
        if (rttVar < 0) rttVar = 0;
        if (state == STATE_DISCONNECTING && found.cmdNum == ENet.CMD_DISCONNECT) {
            state = STATE_DISCONNECTED;
            resetQueues();
            events.addElement(new ENetEvent(ENetEvent.DISCONNECT, 0, null, disconnectData));
        }
    }

    private boolean handleVerify(byte[] d, int pos) {
        if (state != STATE_CONNECTING) return true;
        int cc = ENet.getU32(d, pos + 16);
        boolean idOk = true;
        for (int i = 0; i < 4; i++) if (d[pos + 40 + i] != connectId[i]) idOk = false;
        if (cc < 1 || cc > ENet.MAX_CHANNELS
                || ENet.getU32(d, pos + 28) != THROTTLE_INTERVAL
                || ENet.getU32(d, pos + 32) != THROTTLE_ACCEL
                || ENet.getU32(d, pos + 36) != THROTTLE_DECEL || !idOk) {
            state = STATE_DISCONNECTED;
            resetQueues();
            events.addElement(new ENetEvent(ENetEvent.DISCONNECT, 0, null, 0));
            return false;
        }
        for (int i = 0; i < inFlight.size(); i++) {
            if (((OutCmd) inFlight.elementAt(i)).cmdNum == ENet.CMD_CONNECT) {
                inFlight.removeElementAt(i);
                break;
            }
        }
        if (cc < channelCount) channelCount = cc;
        outPeerID = ENet.getU16(d, pos + 4) & ENet.MAX_PEER_ID;
        inSessionID = d[pos + 6] & 0xFF;
        outSessionID = d[pos + 7] & 0xFF;
        int m = ENet.getU32(d, pos + 8);
        if (m < ENet.MIN_MTU) m = ENet.MIN_MTU;
        if (m > ENet.MAX_MTU) m = ENet.MAX_MTU;
        if (m < mtu) mtu = m;
        int w = ENet.getU32(d, pos + 12);
        if (w < ENet.MIN_WINDOW_SIZE) w = ENet.MIN_WINDOW_SIZE;
        if (w > ENet.MAX_WINDOW_SIZE) w = ENet.MAX_WINDOW_SIZE;
        if (w < windowSize) windowSize = w;
        state = STATE_CONNECTED;
        lastSendTime = now();
        events.addElement(new ENetEvent(ENetEvent.CONNECT, 0, null, connectData));
        return true;
    }

    private void handleDisconnect(int data) {
        if (state == STATE_DISCONNECTED) return;
        flushInternal();     // push the queued ACK out before tearing down
        state = STATE_DISCONNECTED;
        resetQueues();
        events.addElement(new ENetEvent(ENetEvent.DISCONNECT, 0, null, data));
    }

    // ---- incoming data paths ----

    private void emit(int channel, byte[] data, int flags) {
        events.addElement(new ENetEvent(ENetEvent.RECEIVE, channel, new ENetPacket(data, flags), 0));
    }

    private void queueIncomingReliable(int chan, int seq, Incoming in) {
        Channel ch = channels[chan];
        int diff = (seq - ch.inReliableSeq) & 0xFFFF;
        if (diff == 0 || diff > MAX_REORDER_WINDOW) return;     // duplicate or out of window
        Integer key = new Integer(seq);
        if (!ch.pending.containsKey(key)) ch.pending.put(key, in);
        for (;;) {
            int next = (ch.inReliableSeq + 1) & 0xFFFF;
            Integer nk = new Integer(next);
            Incoming c = (Incoming) ch.pending.get(nk);
            if (c == null) break;
            ch.pending.remove(nk);
            ch.inReliableSeq = next;
            ch.inUnreliableSeq = 0;
            if (c.type == ENet.CMD_SEND_RELIABLE) emit(chan, c.data, ENetPacket.FLAG_RELIABLE);
            else deliverReliableFragment(chan, ch, c);
        }
    }

    private void deliverReliableFragment(int chan, Channel ch, Incoming c) {
        FragAsm a = ch.reliableAsm;
        if (a == null || a.startSeq != c.startSeq || a.buf.length != c.total) {
            a = new FragAsm();
            a.startSeq = c.startSeq;
            a.count = c.fragCount;
            a.buf = new byte[c.total];
            a.got = new boolean[c.fragCount];
            ch.reliableAsm = a;
        }
        if (c.fragNum >= a.count || c.fragOffset + c.data.length > a.buf.length) return;
        if (!a.got[c.fragNum]) {
            a.got[c.fragNum] = true;
            a.received++;
            System.arraycopy(c.data, 0, a.buf, c.fragOffset, c.data.length);
        }
        if (a.received == a.count) {
            ch.reliableAsm = null;
            emit(chan, a.buf, ENetPacket.FLAG_RELIABLE);
        }
    }

    private static boolean newer(int a, int b) {
        int diff = (a - b) & 0xFFFF;
        return diff != 0 && diff < 0x8000;
    }

    private void handleUnreliable(int chan, int relSeq, int unrelSeq, byte[] data) {
        Channel ch = channels[chan];
        if (relSeq != ch.inReliableSeq) return;
        if (!newer(unrelSeq, ch.inUnreliableSeq)) return;
        ch.inUnreliableSeq = unrelSeq;
        emit(chan, data, 0);
    }

    private void handleUnreliableFragment(int chan, int relSeq, Incoming in) {
        Channel ch = channels[chan];
        if (relSeq != ch.inReliableSeq) return;
        FragAsm a = ch.unreliableAsm;
        if (a == null || a.startSeq != in.startSeq) {
            if (!newer(in.startSeq, ch.inUnreliableSeq)) return;
            a = new FragAsm();
            a.startSeq = in.startSeq;
            a.count = in.fragCount;
            a.buf = new byte[in.total];
            a.got = new boolean[in.fragCount];
            ch.unreliableAsm = a;
        }
        if (in.fragNum >= a.count || in.fragOffset + in.data.length > a.buf.length) return;
        if (!a.got[in.fragNum]) {
            a.got[in.fragNum] = true;
            a.received++;
            System.arraycopy(in.data, 0, a.buf, in.fragOffset, in.data.length);
        }
        if (a.received == a.count) {
            ch.unreliableAsm = null;
            ch.inUnreliableSeq = a.startSeq;
            emit(chan, a.buf, ENetPacket.FLAG_UNRELIABLE_FRAGMENT);
        }
    }

    private void handleUnsequenced(int chan, int group, byte[] data) {
        int index = group % 1024;
        int g = group;
        if (g < inUnseqGroup) g += 0x10000;
        if (g >= inUnseqGroup + 32 * 1024) return;
        g &= 0xFFFF;
        if (g - index != inUnseqGroup) {
            inUnseqGroup = g - index;
            unseqWindow = new int[32];
        } else if ((unseqWindow[index / 32] & (1 << (index % 32))) != 0) {
            return;
        }
        unseqWindow[index / 32] |= 1 << (index % 32);
        emit(chan, data, ENetPacket.FLAG_UNSEQUENCED);
    }
}
