import enet.ENetClient;
import enet.ENetEvent;
import enet.ENetPacket;
import enet.midp.DatagramTransport;

import javax.microedition.io.file.ConnectionClosedException;
import javax.microedition.lcdui.game.Sprite;
import java.io.*;
import java.util.Vector;

public class Multiplayer {
    static public final int STATE_DISCONNECTED = 0;
    static public final int STATE_CONNECTING = 1;
    static public final int STATE_LOGGING_IN = 2;
    static public final int STATE_CONNECTED = 3;

    static final int PACKET_AUTH = 0;
    static final int PACKET_ADDPLAYER = 1;
    static final int PACKET_MOVE = 2;
    static final int PACKET_REMOVEPLAYER = 3;
    static final int PACKET_LOADWORLD = 4;
    static final int PACKET_ADDBULLET = 5;
    static final int PACKET_REMOVEBULLET = 6;
    static final int PACKET_SETHP = 7;
    static final int PACKET_ADDWEAPON = 8;
    static final int PACKET_UPDATEWEAPON = 9;
    static final int PACKET_LEVEL = 10;
    static final int PACKET_UPDATECOLLECTIBLE = 11;
    static final int PACKET_SETSCORE = 12;
    static final int PACKET_UPDATEANGLE = 13;
    static final int PACKET_MESSAGE = 14;
    static final int PACKET_DAMAGE = 15;
    static final int PACKET_ROUNDEND = 16;
    static final int PACKET_THROWGRENADE = 17;
    static final int PACKET_ADDGRENADE = 18;
    static final int PACKET_REMOVEGRENADE = 19;
    static final int PACKET_REVIVE = 20;

    public String host;
    public int port = 6890;

    private final ENetClient client;
    public int state = STATE_DISCONNECTED;
    private final String playerName;
    public Vector remotePlayers = new Vector();

    public Multiplayer(String host, String playerName) throws IOException {
        { // make url and port
            int idx = host.indexOf(":");

            if (idx > 0) {
                port = Integer.parseInt(host.substring(idx + 1));
                this.host = host.substring(0, idx);
            }
        }

        this.playerName = playerName;

        // Connect to host
        client = new ENetClient(new DatagramTransport(host, port));
        client.connect(2, 0);

        state = STATE_CONNECTING;

        // wait for connection 15 sec
        ENetEvent e = client.service(15000);
        if (e.type != ENetEvent.CONNECT) {
            state = STATE_DISCONNECTED;
            throw new ConnectionClosedException("Cant connect to server"); // TODO: make error screen
        }

        state = STATE_LOGGING_IN;

        { // send login packet
            ByteArrayOutputStream loginPacket = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(loginPacket);

            out.writeShort(0);
            out.write(playerName.getBytes());
            out.writeByte(0); // null terminator string
            out.flush();

            client.send(0, loginPacket.toByteArray(), ENetPacket.FLAG_RELIABLE);
        }
    }

    public void update() throws IOException {
        // process 64 packets at once
        for (int j = 0; j < 64; j++) {
            ENetEvent e = client.service(0);
            if (e == null) break;

            if (e.type == ENetEvent.RECEIVE) handlePacket(e.packet);
            if (e.type == ENetEvent.DISCONNECT) throw new ConnectionClosedException("Disconnected");
        }
    }

    private final byte[] movePacket = new byte[9];
    public void sendMove(Game.Player player) {
        movePacket[0] = PACKET_MOVE;

        Utils.writeFloatLE(movePacket, 1, player.x);
        Utils.writeFloatLE(movePacket, 5, player.y);

        client.send(0, movePacket, 0);
    }

    private final byte[] universalPacket = new byte[5];
    public void sendAngle(float angle) {
        universalPacket[0] = PACKET_UPDATEANGLE;
        Utils.writeFloatLE(universalPacket, 1, (float) angle);

        client.send(0, universalPacket, 0);
    }

    public void sendShoot(float bulletAngle) {
        universalPacket[0] = PACKET_ADDBULLET;
        Utils.writeFloatLE(universalPacket, 1, (float) bulletAngle);

        client.send(0, universalPacket, ENetPacket.FLAG_RELIABLE);
    }

    public void sendThrowGrenade(float grenadeAngle) {
        universalPacket[0] = PACKET_ADDGRENADE;
        Utils.writeFloatLE(universalPacket, 1, (float) grenadeAngle);

        client.send(0, universalPacket, ENetPacket.FLAG_RELIABLE);
    }

    private final byte[] changeWeaponPacket = new byte[2];
    public void sendChangeWeapon(byte weapon) {
        changeWeaponPacket[0] = PACKET_UPDATEWEAPON;
        changeWeaponPacket[1] = weapon;

        client.send(0, changeWeaponPacket, ENetPacket.FLAG_RELIABLE);
    }

    private final byte[] revivePacket = new byte[1];
    public void sendRevive() {
        revivePacket[0] = PACKET_REVIVE;

        client.send(0, revivePacket, ENetPacket.FLAG_RELIABLE);
    }

    public void sendChatMessage(String message) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(outputStream);

        dataOutputStream.writeByte(PACKET_MESSAGE);
        dataOutputStream.write(message.getBytes());
        dataOutputStream.writeByte(0); // null terminator string
        dataOutputStream.flush();

        client.send(0, outputStream.toByteArray(), ENetPacket.FLAG_RELIABLE);
    }

    private void handlePacket(ENetPacket packet) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet.data));

        Game game = Game.getInstance();

        try {
            byte header = in.readByte();

            if (state == STATE_LOGGING_IN && header == PACKET_LEVEL) {

            }

            switch (header) {
                case PACKET_AUTH: {
                    System.out.println("Collecting players...");
                    byte count = in.readByte();

                    for (byte i = 0; i < count; i++) {
                        int id = Utils.readIntLE(in);
                        float x = Utils.readFloatLE(in);
                        float y = Utils.readFloatLE(in);

                        int nameLen = Utils.readShortLE(in);
                        byte[] name = new byte[nameLen - 1];
                        in.read(name);

                        byte hp = in.readByte();
                        byte weapon = in.readByte();
                        byte hat = in.readByte();
                        boolean isDied = in.readBoolean();

                        addRemotePlayer(new RemotePlayer(
                                id, new String(name, "UTF-8"),
                                (int) (x * 8.f),
                                (int) (y * 8.f),
                                hp, hat, weapon, isDied
                        ));
                    }

                    break;
                }

                case PACKET_ADDPLAYER: {
                    float x = Utils.readFloatLE(in);
                    float y = Utils.readFloatLE(in);
                    int id = Utils.readIntLE(in);
                    byte hat = in.readByte();

                    System.out.println("x=" + x + "; y=" + y + "; id=" + id + "; hat=" + hat);

                    byte[] name = new byte[in.available() - 1];
                    in.read(name);

                    String nameStr = new String(name, "UTF-8");

                    if (playerName.equals(nameStr)) {
                        game.player.id = id;
                        game.player.x = x;
                        game.player.y = y;
                    } else {
                        addRemotePlayer(new RemotePlayer(
                                id, nameStr,
                                (int) (x * 8.f),
                                (int) (y * 8.f),
                                (byte) 100, hat, (byte) 0, false
                        ));
                    }
                    break;
                }

                case PACKET_MOVE: {
                    int id = Utils.readIntLE(in);
                    float x = Utils.readFloatLE(in);
                    float y = Utils.readFloatLE(in);

                    if (id != game.player.id) {
                        RemotePlayer remotePlayer = getRemotePlayer(id);

                        if (remotePlayer != null) {
                            remotePlayer.x = (int) (x * 8.f);
                            remotePlayer.y = (int) (y * 8.f);
                        }
                    } else {
                        game.player.x = x;
                        game.player.y = y;
                    }

                    break;
                }

                case PACKET_REMOVEPLAYER: {
                    removeRemotePlayer(Utils.readIntLE(in));
                    break;
                }

                case PACKET_LEVEL: {
                    // packet level is considered to be accepted and connected and ready to play
                    state = STATE_CONNECTED;
                    game.connectedServer(Level.createFromPacket(in));
                    break;
                }

                case PACKET_SETHP: {
                    int id = Utils.readIntLE(in);
                    int hp = Utils.readIntLE(in);

                    if (id != game.player.id) {
                        RemotePlayer remotePlayer = getRemotePlayer(id);

                        if (remotePlayer != null) {
                            remotePlayer.hp = hp;
                        }
                    } else {
                        game.player.hp = hp;
                    }

                    break;
                }

                case PACKET_UPDATEANGLE: {
                    int id = Utils.readIntLE(in);

                    if (id != game.player.id) {
                        float angle = Utils.readFloatLE(in);
                        RemotePlayer remotePlayer = getRemotePlayer(id);

                        if (remotePlayer != null) {
                            remotePlayer.transform = Math.cos(angle) < 0 ? Sprite.TRANS_MIRROR : Sprite.TRANS_NONE;
                        }

                        break;
                    }
                }

                case PACKET_ADDBULLET: {
                    Game.Bullet bullet = new Game.Bullet();
                    bullet.id = Utils.readIntLE(in);
                    bullet.x = Utils.readFloatLE(in);
                    bullet.y = Utils.readFloatLE(in);
                    bullet.speedX = Utils.readFloatLE(in);
                    bullet.speedY = Utils.readFloatLE(in);
                    bullet.angle = (float) Math.toDegrees(Utils.readFloatLE(in));

                    game.addBullet(bullet);
                    break;
                }

                case PACKET_REMOVEBULLET: {
                    int id = Utils.readIntLE(in);
                    game.removeBullet(id);
                    break;
                }

                case PACKET_UPDATEWEAPON: {
                    byte weapon = in.readByte();
                    int id = Utils.readIntLE(in);

                    if (id == game.player.id) {
                        game.player.weapon = weapon;
                    } else {
                        RemotePlayer remote = getRemotePlayer(id);

                        if (remote != null) remote.weapon = weapon;
                    }

                    break;
                }

                case PACKET_ADDWEAPON: {
                    byte weapon = in.readByte();
                    game.player.weapons[weapon] = true;
                    break;
                }

                case PACKET_UPDATECOLLECTIBLE:
                    break; // i refuse implementing it

                case PACKET_THROWGRENADE: {
                    Game.Grenade grenade = new Game.Grenade();

                    grenade.id = Utils.readIntLE(in);
                    grenade.x = Utils.readFloatLE(in);
                    grenade.y = Utils.readFloatLE(in);
                    grenade.speedX = Utils.readFloatLE(in);
                    grenade.speedY = Utils.readFloatLE(in);

                    game.addGrenade(grenade);
                    break;
                }

                case PACKET_ADDGRENADE: {
                    game.player.grenade = true;
                    break;
                }

                case PACKET_REMOVEGRENADE: {
                    game.player.grenade = false;
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println("Failed to handle packet: " + e.getMessage());
        }
    }

    private void addRemotePlayer(RemotePlayer player) {
        if (remotePlayers.contains(player)) {
            return;
        }

        remotePlayers.addElement(player);
    }

    private void removeRemotePlayer(int id) {
        for (int i = 0; i < remotePlayers.size(); i++) {
            RemotePlayer remotePlayer = (RemotePlayer)remotePlayers.elementAt(i);
            if (remotePlayer.id == id) remotePlayers.removeElementAt(i);

            return;
        }
    }

    private RemotePlayer getRemotePlayer(int id) {
        for (int i = 0; i < remotePlayers.size(); i++) {
            RemotePlayer remotePlayer = (RemotePlayer)remotePlayers.elementAt(i);
            if (remotePlayer.id == id) return remotePlayer;
        }

        return null;
    }
}