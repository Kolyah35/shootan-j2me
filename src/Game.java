import javax.microedition.io.file.ConnectionClosedException;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.game.GameCanvas;
import enet.ENetClient;
import enet.ENetEvent;
import enet.ENetPacket;
import enet.midp.DatagramTransport;

import java.io.*;

public class Game extends GameCanvas {
    private static Game game;

    private ENetClient c;
    private Graphics g;

    static final int PACKET_AUTH = 0;
    static final int PACKET_MOVE = 2;
    static final int PACKET_LEVEL = 10;

    long ticks = 0;

    public static class Player {
        public String username;

        public int x;
        public int y;

        public byte hp;
        public boolean onGround;
        public boolean prevOnGround;

        public int speedX;
        public int speedY;

        public boolean isDied;

        public long lastFall = 0;
    };

    public Player player = null;

    private Game() {
        super(false);
    }

    public static Game getInstance() {
        if (game == null) {
            game = new Game();
        }

        return game;
    }

    public void start(String playerName, String host) throws IOException {
        int port = 6890;
        {
            int idx = host.indexOf(":");

            if (idx > 0){
                port = Integer.parseInt(host.substring(idx + 1));
                host = host.substring(0, idx);
            }
        }

        g = getGraphics();

        drawConnectingScreen("Connecting to "+host+":"+port+"...");

        c = new ENetClient(new DatagramTransport(host, port));
        c.connect(2, 0);

        for (;;) {
            ENetEvent e = c.service(50);
            if (e == null) continue;
            if (e.type == ENetEvent.CONNECT) break;
            if (e.type == ENetEvent.DISCONNECT) throw new ConnectionClosedException("Cant connect to server"); // TODO: make error screen
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

        int offX = 0;
        int offY = 0;

        Level lvl = null;
        Timer timer = new Timer(60.f);

        while (true) {
            ENetEvent e = c.service(20);
            if (e != null) {
                if (e.type == ENetEvent.RECEIVE) {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(e.packet.data));

                    byte header = in.readByte();

                    if (header == PACKET_LEVEL) {
                        lvl = Level.createFromPacket(in);
                        player = new Player();
                        player.x = 400;
                    }
                }
                if (e.type == ENetEvent.DISCONNECT)
                    throw new ConnectionClosedException("Disconnected");
            }

            if (player != null) {
                timer.advanceTime();

                for (int i = 0; i < timer.ticks; i++) {
                    this.ticks++;

                    int prevX = player.x;
                    int prevY = player.y;

                    if (this.ticks % 30 == 0) {
                        player.speedY += 1;
                    }

                    int prevSpeedY = player.speedY;

                    if (lvl != null) {
                        lvl.movePlayer(player);
                    }

                    player.prevOnGround = player.onGround;
                    player.onGround = prevSpeedY != player.speedY && prevSpeedY > 0;

                    if (player.prevOnGround && !player.onGround) {
                        player.lastFall = this.ticks;
                    }

                    if (this.ticks % 20 == 0) {
                        player.speedX /= 2;
                    }

                    if (!player.isDied && (player.x != prevX || player.y != prevY)) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        DataOutputStream stream = new DataOutputStream(bytes);
                        stream.writeByte(PACKET_MOVE);

                        float x = (float)player.x;
                        float y = (float)player.y;
                        stream.writeFloat(((float)player.x) / 8.f);
                        stream.writeFloat(((float)player.y) / 8.f);
                        stream.flush();

                        c.send(0, bytes.toByteArray(), ENetPacket.FLAG_RELIABLE);
                    }
                }
                int keyState = getKeyStates();

                if ((keyState & LEFT_PRESSED) != 0) {
                    player.speedX = -1;
                }

                if ((keyState & RIGHT_PRESSED) != 0) {
                    player.speedX = 1;
                }

                if ((keyState & UP_PRESSED) != 0 && player.onGround) {
                    player.speedY = -1;
                }

                offX = player.x - getWidth() / 2;
                offY = player.y - getHeight() / 2;

                if (offX < 0) offX = 0;
                if (offX > Level.LEVEL_SIZE * 8 - getWidth()) offX = Level.LEVEL_SIZE * 8 - getWidth();

                if (offY < 0) offY = 0;
                if (offY > Level.LEVEL_SIZE * 8 - getHeight()) offY = Level.LEVEL_SIZE * 8 - getHeight();
            }

            g.setColor(0x040432);
            g.fillRect(0, 0, this.getWidth(), this.getHeight());

            if (lvl != null) {
                lvl.render(g, offX, offY, getWidth(), getHeight());
            }

            if (player != null) {
                g.setColor(0xFF0000);
                g.drawRegion(Resources.spritesheet, 32, 8, 8, 8, 0, player.x - offX, player.y - offY, 0);
            }
            flushGraphics();
        }
    }

    public void drawConnectingScreen(String text) {
        g.setColor(0);
        g.drawString(text, this.getWidth() / 2, 0, Graphics.TOP|Graphics.HCENTER);
        flushGraphics();
    }
}
