package enet;

/** Result of ENetClient.service(): connect, disconnect or received packet. */
public class ENetEvent {
    public static final int NONE = 0;
    public static final int CONNECT = 1;
    public static final int DISCONNECT = 2;
    public static final int RECEIVE = 3;

    public final int type;
    public final int channelID;
    public final ENetPacket packet;
    public final int data;

    public ENetEvent(int type, int channelID, ENetPacket packet, int data) {
        this.type = type;
        this.channelID = channelID;
        this.packet = packet;
        this.data = data;
    }
}
