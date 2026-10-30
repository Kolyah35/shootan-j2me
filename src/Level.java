import gzip.*;

import javax.microedition.lcdui.Graphics;
import java.io.*;
import java.util.Vector;

public class Level {
    public static int LEVEL_SIZE = 128;
    private final byte[] foreground;
    private final byte[] background;

    public static byte COLLECTIBLE_NONE = 0;
    public static byte COLLECTIBLE_SHOTGUN = 1;
    public static byte COLLECTIBLE_SNIPER = 2;
    public static byte COLLECTIBLE_MEDKIT = 3;
    public static byte COLLECTIBLE_GRENADE = 4;

    public static byte BLOCK_AIR = 0;
    public static byte BLOCK_GRASS = 1;
    public static byte BLOCK_DIRT = 2;
    public static byte BLOCK_BRICK = 3;
    public static byte BLOCK_LADDER = 4;
    public static byte BLOCK_STONE_BRICK = 5;
    public static byte BLOCK_GLASS = 6;
    public static byte BLOCK_LEAVES = 7;
    public static byte BLOCK_WOOD = 8;
    public static byte BLOCK_BOX = 9;

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

    private final Collectible[] collectibles;
    private final Vector grenades = new Vector();

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

        long uncompressed;

        System.out.println("Parsing level from ENet packet...");

        // Foreground
        int compressedFgSize = Utils.readIntLE(in) - 2;
        System.out.println("compressed FG size: "+compressedFgSize);

        in.skip(2);
        in.mark(compressedFgSize);

        ByteArrayOutputStream uncompressedForeground = new ByteArrayOutputStream();
        uncompressed = Gzip.inflate(in, uncompressedForeground);

        in.reset();
        in.skip(compressedFgSize);

        System.out.println(uncompressed+" bytes uncompressed");

        // Background
        int compressedBgSize = Utils.readIntLE(in) - 2;
        System.out.println("compressed BG size: "+compressedBgSize);

        in.skip(2);
        in.mark(compressedBgSize);

        ByteArrayOutputStream uncompressedBackground = new ByteArrayOutputStream();
        uncompressed = Gzip.inflate(in, uncompressedBackground);

        in.reset();
        in.skip(compressedBgSize);

        System.out.println(uncompressed+" bytes uncompressed");

        // Collectibles
        int collectibleCount = Utils.readIntLE(in);
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

    public static Level createFromRes(String name) throws IOException {
        InputStream stream = Game.getInstance().getClass().getResourceAsStream(name);

        byte[] foreground = new byte[LEVEL_SIZE*LEVEL_SIZE];
        stream.read(foreground);

        byte[] background = new byte[LEVEL_SIZE*LEVEL_SIZE];
        stream.read(background);

        int collectibleCount = Utils.readIntLE(stream);
        Collectible[] collectibles = new Collectible[collectibleCount];

        for (int i = 0; i < collectibleCount; i++) {
            float x = Utils.readFloatLE(stream);
            float y = Utils.readFloatLE(stream);
            byte type = (byte) stream.read();

            Collectible coll = new Collectible(x, y, type);
            coll.respawnTime = (int)(Utils.readFloatLE(stream) * 1000);
            coll.newY = Utils.readFloatLE(stream);
            coll.isSent = stream.read() == 0x01;
        }

        return new Level(background, foreground, collectibles);
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
        int minX = (int)Math.min(player.x, player.x + player.speedX);
        int maxX = (int)(Math.max(player.x, player.x + player.speedX) + 1);

        int minY = (int)player.y;
        int maxY = (int)(player.y + 1);

        for (int yy = minY; yy <= maxY; yy++) {
            for (int xx = minX; xx <= maxX; xx++) {
                if (xx < 0 || xx >= LEVEL_SIZE || yy < 0 || yy >= LEVEL_SIZE) continue;

                int idx = yy * LEVEL_SIZE + xx;

                if (foreground[idx] == 0 || foreground[idx] == 4) continue;

                player.speedX = clipX(
                        xx, yy, 1, 1,
                        player.x, player.y, 1, 1,
                        player.speedX
                );
            }
        }

        player.x += player.speedX;

        // Y
        minX = (int)player.x;
        maxX = (int)(player.x + 1);

        minY = (int)Math.min(player.y, player.y + player.speedY);
        maxY = (int)(Math.max(player.y, player.y + player.speedY) + 1);

        for (int yy = minY; yy <= maxY; yy++) {
            for (int xx = minX; xx <= maxX; xx++) {
                if (xx < 0 || xx >= LEVEL_SIZE || yy < 0 || yy >= LEVEL_SIZE) continue;

                int idx = yy * LEVEL_SIZE + xx;

                if (foreground[idx] == 0 || foreground[idx] == 4) continue;

                player.speedY = clipY(
                        xx, yy, 1, 1,
                        player.x, player.y, 1, 1,
                        player.speedY
                );
            }
        }

        player.y += player.speedY;
    }

    public byte getBlockFG(int x, int y) {
        if (y >= LEVEL_SIZE) return 0;
        if (x >= LEVEL_SIZE) return 0;

        return this.foreground[y * LEVEL_SIZE + x];
    }

    public static float clipX(float x1, float y1, float w1, float h1, float x2, float y2, float w2, float h2, float xa) {
        if (y2 + h2 <= y1 || y2 >= y1 + h1) {
            return xa;
        }

        if (xa > 0 && x2 + w2 <= x1) {
            xa = Math.min(x1 - (x2 + w2), xa);
        }

        if (xa < 0 && x2 >= x1 + w1) {
            xa = Math.max((x1 + w1) - x2, xa);
        }

        return xa;
    }

    public static float clipY(float x1, float y1, float w1, float h1, float x2, float y2, float w2, float h2, float ya) {
        if (x2 + w2 <= x1 || x2 >= x1 + w1) {
            return ya;
        }

        if (ya > 0 && y2 + h2 <= y1) {
            ya = Math.min(y1 - (y2 + h2), ya);
        }

        if (ya < 0 && y2 >= y1 + h1) {
            ya = Math.max((y1 + h1) - y2, ya);
        }

        return ya;
    }
}
