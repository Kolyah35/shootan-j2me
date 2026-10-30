package enet;

/** A received or outgoing packet payload plus ENet flags. */
public class ENetPacket {
    public static final int FLAG_RELIABLE = 1;
    public static final int FLAG_UNSEQUENCED = 2;
    public static final int FLAG_UNRELIABLE_FRAGMENT = 8;

    public final byte[] data;
    public final int flags;

    public ENetPacket(byte[] data, int flags) {
        this.data = data;
        this.flags = flags;
    }
}
