import gzip.*;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import java.io.*;
import java.util.Vector;

public class Level {
    public static int LEVEL_SIZE = 128;
    private byte[] foreground;
    private byte[] background;

    public static byte COLLECTIBLE_NONE = 0;
    public static byte COLLECTIBLE_SHOTGUN = 1;
    public static byte COLLECTIBLE_SNIPER = 2;
    public static byte COLLECTIBLE_MEDKIT = 3;
    public static byte COLLECTIBLE_GRENADE = 4;

    public static class Collectible {
        public byte type;
        public int x; // pixels
        public int y; // pixels
        public int respawnTime; // millis
        public float newY;
        public boolean isSent;

        public Collectible(float x, float y, byte type) {
            int xi = (int)(x * 8);
            int yi = (int)(y * 8);

            this.x = (int)((x - (float)xi) * 8);
            this.y = (int)((y - (float)yi) * 8);

            this.type = type;
            this.isSent = false;
        }
    }

    private Collectible[] collectibles;
    private final Vector grenades = new Vector();

    static private int readUI32LE(DataInputStream in) throws IOException {
        byte b0 = in.readByte();
        byte b1 = in.readByte();
        byte b2 = in.readByte();
        byte b3 = in.readByte();

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private Level(byte[] background, byte[] foreground, Collectible[] collectibles) {
        this.background = background;
        this.foreground = foreground;
        this.collectibles = collectibles;

        for (int i = 0; i < LEVEL_SIZE * LEVEL_SIZE; i++) {
            // remove illegal blocks
            if (background[i] < 0 || background[i] > 8) background[i] = 0;
            if (foreground[i] < 0 || foreground[i] > 8) foreground[i] = 0;
        }
    }

    static public Level createFromPacket(DataInputStream in) throws RuntimeException, IOException {
        in.reset();

        byte header = in.readByte();

        if (header != Game.PACKET_LEVEL) {
            throw new RuntimeException("The ENet packet is not a level!");
        }

        long uncompressed = 0;

        System.out.println("Parsing level from ENet packet...");

        // Foreground
        int compressedFgSize = Level.readUI32LE(in) - 2;
        System.out.println("compressed FG size: "+compressedFgSize);

        in.skip(2);
        in.mark(compressedFgSize);

        ByteArrayOutputStream uncompressedForeground = new ByteArrayOutputStream();
        uncompressed = Gzip.inflate(in, uncompressedForeground);

        in.reset();
        in.skip(compressedFgSize);

        System.out.println(uncompressed+" bytes uncompressed");

        // Background
        int compressedBgSize = Level.readUI32LE(in) - 2;
        System.out.println("compressed BG size: "+compressedBgSize);

        in.skip(2);
        in.mark(compressedBgSize);

        ByteArrayOutputStream uncompressedBackground = new ByteArrayOutputStream();
        uncompressed = Gzip.inflate(in, uncompressedBackground);

        in.reset();
        in.skip(compressedBgSize);

        System.out.println(uncompressed+" bytes uncompressed");

        // Collectibles
        int collectibleCount = Level.readUI32LE(in);
        System.out.println("Collectible count: "+collectibleCount);

        Collectible[] collectibles = new Collectible[collectibleCount];

        for (int i = 0; i < collectibleCount; i++) {
            float x = in.readFloat();
            float y = in.readFloat();
            byte type = in.readByte();

            collectibles[i] = new Collectible(x, y, type);
        }

        return new Level(
                uncompressedBackground.toByteArray(),
                uncompressedForeground.toByteArray(),
                collectibles
        );
    }

    public void render(Graphics g, int offX, int offY, int width, int height) {
        int minX = Math.max(0, offX / 8);
        int maxX = Math.min(LEVEL_SIZE, (offX + width) / 8 + 1);

        int minY = Math.max(offY / 8, 0);
        int maxY = Math.min((offY + height) / 8 + 1, LEVEL_SIZE);

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                int idx = y * LEVEL_SIZE + x;

                if (foreground[idx] == 0 && background[idx] != 0) {
                    g.setColor(0x7F7F7F);
                    g.drawRegion(Resources.blocks, (background[idx] - 1) * 8, 8, 8, 8, 0, x * 8 - offX, y * 8 - offY, 0);
                }

                if (foreground[idx] == 0) {
                    continue;
                }

                g.setColor(0xFFFFFF);
                g.drawRegion(Resources.blocks, (foreground[idx] - 1) * 8, 0, 8, 8, 0, x * 8 - offX, y * 8 - offY, 0);
            }
        }
    }

    public void movePlayer(Game.Player player) {
        // X
        int minX = Math.min(player.x, player.x + player.speedX) >> 3;
        int maxX = (Math.max(player.x, player.x + player.speedX) + 7) >> 3;

        int minY = player.y >> 3;
        int maxY = (player.y + 7) >> 3;

        for (int yy = minY; yy <= maxY; yy++) {
            for (int xx = minX; xx <= maxX; xx++) {
                if (xx < 0 || xx >= LEVEL_SIZE || yy < 0 || yy >= LEVEL_SIZE) continue;

                int idx = yy * LEVEL_SIZE + xx;

                if (foreground[idx] == 0 || foreground[idx] == 4) continue;

                player.speedX = clipX(
                        xx * 8, yy * 8, 8, 8,
                        player.x, player.y, 8, 8,
                        player.speedX
                );
            }
        }

        player.x += player.speedX;

        // Y
        minX = player.x >> 3;
        maxX = (player.x + 7) >> 3;

        minY = Math.min(player.y, player.y + player.speedY) >> 3;
        maxY = (Math.max(player.y, player.y + player.speedY) + 7) >> 3;

        for (int yy = minY; yy <= maxY; yy++) {
            for (int xx = minX; xx <= maxX; xx++) {
                if (xx < 0 || xx >= LEVEL_SIZE || yy < 0 || yy >= LEVEL_SIZE) continue;

                int idx = yy * LEVEL_SIZE + xx;

                if (foreground[idx] == 0 || foreground[idx] == 4) continue;

                player.speedY = clipY(
                        xx * 8, yy * 8, 8, 8,
                        player.x, player.y, 8, 8,
                        player.speedY
                );
            }
        }

        player.y += player.speedY;
    }

    public static int clipX(int x1, int y1, int w1, int h1, int x2, int y2, int w2, int h2, int xa) {
        if (y2 + h2 <= y1 || y2 >= y1 + h1) {
            return xa;
        }

        if (xa > 0 && x2 + w2 <= x1) {
            int dx = x1 - (x2 + w2);
            if (dx < xa) xa = dx;
        }

        if (xa < 0 && x2 >= x1 + w1) {
            int dx = (x1 + w1) - x2;
            if (dx > xa) xa = dx;
        }

        return xa;
    }

    public static int clipY(int x1, int y1, int w1, int h1, int x2, int y2, int w2, int h2, int ya) {
        if (x2 + w2 <= x1 || x2 >= x1 + w1) {
            return ya;
        }

        if (ya > 0 && y2 + h2 <= y1) {
            int dy = y1 - (y2 + h2);
            if (dy < ya) ya = dy;
        }

        if (ya < 0 && y2 >= y1 + h1) {
            int dy = (y1 + h1) - y2;
            if (dy > ya) ya = dy;
        }

        return ya;
    }
}
