import javax.microedition.io.file.ConnectionClosedException;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.lcdui.game.Sprite;

import enet.ENetClient;
import enet.ENetEvent;
import enet.ENetPacket;
import enet.midp.DatagramTransport;

import java.io.*;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Random;

public class Game extends GameCanvas {
    private static Game game;

    private ENetClient c;
    private Graphics g;

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

    boolean inputClimbUp = false;
    boolean inputClimbDown = false;
    boolean inputJump = false;
    boolean inputLeft = false;
    boolean inputRight = false;
    boolean inputRotateLeft = false;
    boolean inputRotateRight = false;

    long ticks = 0;

    public static class RemotePlayer {
        public String username;

        public int x = 0;
        public int y = 0;
        public byte hp = 100;
        public byte hat;
        public byte weapon;
        public boolean isDied = false;
        public int transform;

        public RemotePlayer(String username, int x, int y, byte hp, byte hat, byte weapon, boolean isDied) {
            this.username = username;
            this.x = x;
            this.y = y;
            this.hp = hp;
            this.hat = hat;
            this.weapon = weapon;
            this.isDied = isDied;
        }
    }

    public static class Player {
        public String username;

        public float x = 0;
        public float y = 0;
        public double angle = 0;

        public int id;
        public byte hat = 0;
        public byte weapon = 0;
        public byte hp = 100;
        public boolean onGround = false;
        public boolean onLadder = false;

        public float speedX = 0;
        public float speedY = 0;

        public boolean isDied = false;

        public int coyoteTime = 0;

        public int pixelsX() { return (int)(this.x * 8.f); }
        public int pixelsY() { return (int)(this.y * 8.f); }
    };

    public Level lvl = null;
    public Player player = null;
    public Hashtable remotes = new Hashtable();

    private Game() {
        super(false);
    }

    public static Game getInstance() {
        if (game == null) {
            game = new Game();
        }

        return game;
    }

    public void start(String playerName, String host, boolean offline) throws IOException {
        g = getGraphics();

        if (!offline) {
            int port = 6890;
            { // make url and port
                int idx = host.indexOf(":");

                if (idx > 0) {
                    port = Integer.parseInt(host.substring(idx + 1));
                    host = host.substring(0, idx);
                }
            }

            drawConnectingScreen("Connecting to " + host + ":" + port + "...");

            c = new ENetClient(new DatagramTransport(host, port));
            c.connect(2, 0);
            c.setPingInterval(100);

            for (; ; ) {
                ENetEvent e = c.service(50);
                if (e == null) continue;
                if (e.type == ENetEvent.CONNECT) break;
                if (e.type == ENetEvent.DISCONNECT)
                    throw new ConnectionClosedException("Cant connect to server"); // TODO: make error screen
            }

            { // send login packet
                ByteArrayOutputStream loginPacket = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(loginPacket);

                out.writeShort(0);
                out.write(playerName.getBytes());
                out.writeByte(0); // null terminator string

                out.flush();

                ENetPacket login = new ENetPacket(loginPacket.toByteArray(), ENetPacket.FLAG_RELIABLE);
                c.send(0, login.data, ENetPacket.FLAG_RELIABLE);
            }
        }

        int offX = 0;
        int offY = 0;

        Timer timer = new Timer(60.f);

        if (offline) {
            lvl = Level.createFromRes("/world.dat");
            player = new Player();
            player.x = new Random().nextInt() % Level.LEVEL_SIZE;
        }

        setFullScreenMode(true);

        while (true) {
            if (c != null) {
                // process 64 packets at once
                for (int j = 0; j < 64; j++) {
                    ENetEvent e = c.service(0);
                    if (e == null) break;

                    if (e.type == ENetEvent.RECEIVE) processPacket(playerName, e.packet);
                    if (e.type == ENetEvent.DISCONNECT) throw new ConnectionClosedException("Disconnected");
                }
            }

            // draw bg
            g.setColor(0x040432);
            g.fillRect(0, 0, this.getWidth(), this.getHeight());

            if (player != null && lvl != null) {
                timer.advanceTime();

                for (int i = 0; i < timer.ticks; i++) {
                    this.ticks++;

                    if (!player.isDied) {
                        updatePlayer(lvl);
                    }

                    offX = player.pixelsX() - getWidth() / 2;
                    offY = player.pixelsY() - getHeight() / 2;

                    if (offX < 0) offX = 0;
                    if (offX > Level.LEVEL_SIZE * 8 - getWidth()) offX = Level.LEVEL_SIZE * 8 - getWidth();

                    if (offY < 0) offY = 0;
                    if (offY > Level.LEVEL_SIZE * 8 - getHeight()) offY = Level.LEVEL_SIZE * 8 - getHeight();
                }

                // scary code
                if (inputClimbUp || inputClimbDown && player.onLadder) {
                    boolean isLadderAbove = false;
                    if (Level.BLOCK_LADDER == lvl.getBlockFG((int)player.x, (int)player.y - 1)) {
                        isLadderAbove = true;
                        player.x = (int)player.x;
                    }

                    if (Level.BLOCK_LADDER == lvl.getBlockFG((int)player.x + 1, (int)player.y - 1)) {
                        isLadderAbove = true;
                        player.x = ((int)player.x) + 1;
                    }

                    boolean isLadderUnder = false;

                    if (Level.BLOCK_LADDER == lvl.getBlockFG((int)player.x, ((int)player.y))) {
                        isLadderUnder = true;
                        player.x = (int)player.x;
                    }

                    if (Level.BLOCK_LADDER == lvl.getBlockFG((int)player.x + 1, (int)(player.y))) {
                        isLadderUnder = true;
                        player.x = ((int)player.x) + 1;
                    }

                    if (isLadderAbove || isLadderUnder) {
                        player.onLadder = true;
                        player.speedY = inputClimbUp ? -0.2f : 0.2f;
                    }
                }

                if (inputJump) {
                    if (player.coyoteTime > 0) {
                        player.speedY = -0.3f;
                        player.coyoteTime = 0;
                    } else if (player.onLadder) {
                        player.onLadder = false;
                        player.speedY = -0.3f;
                    }
                }

                if (!player.onLadder) {
                    if (inputLeft) {
                        if (player.speedX > -0.175f) {
                            player.speedX -= 0.03f;
                        }

                        if (player.speedX < -0.175f) {
                            player.speedX = -0.175f;
                        }
                    }

                    if (inputRight) {
                        if (player.speedX < 0.175f) {
                            player.speedX += 0.03f;
                        }

                        if (player.speedX > 0.175f) {
                            player.speedX = 0.175f;
                        }
                    }
                }

                if (inputRotateRight) player.angle += 0.06f;
                if (inputRotateLeft) player.angle -= 0.06f;

                lvl.render(g, offX, offY, getWidth(), getHeight());

                Enumeration elements = remotes.elements();

                // draw remote players
                while (elements.hasMoreElements()) {
                    RemotePlayer pl = (RemotePlayer)elements.nextElement();
                    g.drawRegion(Resources.spritesheet, 32, 8, 8, 8, 0, pl.x - offX, pl.y - offY, 0);
                }

                int deg = Math.abs((int)Math.toDegrees(player.angle) % 360);
                int transform = (deg > 75 && deg < 260) ? Sprite.TRANS_MIRROR : Sprite.TRANS_NONE;

                // draw player
                g.drawRegion(Resources.spritesheet, 32, 8, 8, 8, transform, player.pixelsX() - offX, player.pixelsY() - offY, 0);

                g.setColor(0xFF0000);
                g.drawLine(
                        player.pixelsX() - offX + 4,
                        player.pixelsY() - offY + 4,
                        player.pixelsX() - offX + (int)(14 * Math.cos(player.angle)),
                        player.pixelsY() - offY + (int)(14 * Math.sin(player.angle))
                );
            }

            flushGraphics();
        }
    }

    private void processPacket(String playerName, ENetPacket packet) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet.data));

        byte header = in.readByte();

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

                    remotes.put(new Integer(id), new RemotePlayer(
                            new String(name, "UTF-8"),
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

                System.out.println("My name: " + player.username + "; Server name: " + nameStr);

                if (player.username.equals(nameStr)) {
                    System.out.println("itsme");

                    player.id = id;
                    player.x = x;
                    player.y = y;
                } else {
                    remotes.put(new Integer(id), new RemotePlayer(
                            nameStr,
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

                if (id != player.id) {
                    RemotePlayer player = (RemotePlayer) remotes.get(new Integer(id));
                    player.x = (int) (x * 8.f);
                    player.y = (int) (y * 8.f);
                } else {
                    player.x = x;
                    player.y = y;
                }

                break;
            }

            case PACKET_REMOVEPLAYER: {
                int id = Utils.readIntLE(in);
                remotes.remove(new Integer(id));
                break;
            }

            case PACKET_LEVEL: {
                lvl = Level.createFromPacket(in);
                player = new Player();
                player.username = playerName;
            }
        }
    }

    private void updatePlayer(Level lvl) throws IOException {
        if (!player.onLadder) {
            player.speedY += 0.02f;
        }

        float prevSpeedY = player.speedY;

        lvl.movePlayer(player);

        player.onGround = prevSpeedY != player.speedY && prevSpeedY > 0;

        if (player.onGround) {
            player.coyoteTime = 60;
        } else {
            player.coyoteTime -= 10;
        }

        // Send move packet
        if (c != null && (player.speedX != 0 || player.speedY != 0)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream stream = new DataOutputStream(bytes);
            stream.writeByte(PACKET_MOVE);
            Utils.writeFloatLE(stream, player.x);
            Utils.writeFloatLE(stream, player.y);
            stream.flush();

            c.send(0, bytes.toByteArray(), ENetPacket.FLAG_UNRELIABLE_FRAGMENT);
        }

        if (player.speedX > -0.01f && player.speedX < 0.01f) player.speedX = 0;

        if (!player.onLadder) {
            player.speedX *= 0.89f;
            player.speedY *= 0.98f;
        } else {
            player.speedX = 0;
            player.speedY = 0;
        }
    }

    public void drawConnectingScreen(String text) {
        g.setColor(0);
        g.drawString(text, this.getWidth() / 2, 0, Graphics.TOP|Graphics.HCENTER);
        flushGraphics();
    }

    public void updateKeys(int keyCode, boolean pressed) {
        if (keyCode == KEY_NUM2) inputClimbUp = pressed;
        else if (getGameAction(keyCode) == UP) inputJump = pressed;

        if (keyCode == KEY_NUM8) inputClimbDown = pressed;
        if (keyCode == KEY_NUM4 || getGameAction(keyCode) == LEFT) inputLeft = pressed;
        if (keyCode == KEY_NUM6 || getGameAction(keyCode) == RIGHT) inputRight = pressed;

        if (keyCode == KEY_NUM1) inputRotateLeft = pressed;
        if (keyCode == KEY_NUM3) inputRotateRight = pressed;
    }

    public void keyPressed(int keyCode) {
        System.out.println(keyCode);
        updateKeys(keyCode, true);
    }

    public void keyReleased(int keyCode) {
        updateKeys(keyCode, false);
    }
}
