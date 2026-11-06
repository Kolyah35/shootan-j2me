import java.io.DataInputStream;
import java.io.IOException;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public class BinaryFont {
    public static final int LEFT = 0;
    public static final int CENTER = 1;
    public static final int RIGHT = 2;

    public static class Glyph {
        public short id;
        public byte x;
        public byte y;
        public byte width;
        public byte height;
        public byte xOffset;
        public byte yOffset;
        public byte xAdvance;

        public Glyph(DataInputStream dis) throws IOException {
            id = ((short) dis.readUnsignedShort());
            x = ((byte) dis.readUnsignedByte());
            y = ((byte) dis.readUnsignedByte());
            width = ((byte) dis.readUnsignedByte());
            height = ((byte) dis.readUnsignedByte());
            xOffset = ((byte) dis.readByte());
            yOffset = ((byte) dis.readByte());
            xAdvance = ((byte) dis.readByte());

            System.out.println("char id=" + id + " x=" + x + " y=" + y + " width=" + width + " height=" + height + " xoffset=" + xOffset + " yoffset=" + yOffset + " xadvance=" + xAdvance);
        }
    }

    public int lineHeight;
    public Image fontAtlas;
    public Glyph[] glyphs;
    private int unknownIdx = -1;

    static public BinaryFont createFont(String fnt, String atlas) throws IOException {
        DataInputStream dis = new DataInputStream(BinaryFont.class.getResourceAsStream(fnt));

        BinaryFont ret = new BinaryFont();

        ret.fontAtlas = Image.createImage(atlas);

        int count = dis.readUnsignedShort();
        ret.glyphs = new Glyph[count];
        ret.lineHeight = dis.readUnsignedByte();

        System.out.println("lineHeight: " + ret.lineHeight);

        for (int i = 0; i < count; i++) {
            ret.glyphs[i] = new Glyph(dis);

            if (ret.glyphs[i].id == (int)'?') ret.unknownIdx = i;
        }

        return ret;
    }

    public void draw(Graphics g, String str, int x, int y) {
        drawAligned(g, str, x, y, LEFT);
    }

    public void drawAligned(Graphics g, String str, int x, int y, int align) {
        drawAlignedAtlas(g, str, x, y, align, fontAtlas);
    }

    public void drawAlignedAtlas(Graphics g, String str, int x, int y, int align, Image atlas) {
        int xBegin = x;
        int entire = stringWidth(str);

        switch (align) {
            case LEFT: break;
            case CENTER: {
                int n = str.indexOf('\n');
                x = n == -1 ? x - (entire >> 1) : x - (stringWidth(str.substring(0, n)) >> 1);
                break;
            }
            case RIGHT: {
                int n = str.indexOf('\n');
                x = n == -1 ? x - entire : x - stringWidth(str.substring(0, n));
                break;
            }
        }

        for(int i = 0; i < str.length(); ++i) {
            char c = str.charAt(i);

            if (c == '\n') {
                y += lineHeight;

                switch (align) {
                    case LEFT:
                        x = xBegin;
                        break;
                    case CENTER: {
                        int n = str.indexOf('\n', i+1);
                        x = n == -1 ? xBegin - (stringWidth(str.substring(i + 1)) >> 1) : xBegin - (stringWidth(str.substring(i + 1, n)) >> 1);
                        break;
                    }
                    case RIGHT: {
                        int n = str.indexOf('\n', i+1);
                        x = n == -1 ? xBegin - stringWidth(str.substring(i + 1)) : xBegin - stringWidth(str.substring(i + 1, n));
                        break;
                    }
                }

                continue;
            }

            Glyph glyph = findGlygh((short)c);

            if (glyph == null) continue;

            g.drawRegion(atlas, glyph.x, glyph.y, glyph.width, glyph.height, 0, x + glyph.xOffset, y + glyph.yOffset, Graphics.TOP | Graphics.LEFT);

            x += glyph.xAdvance;
        }
    }

    public int stringWidth(String str) {
        int ret = 0;
        int lineW = 0;

        for(int i = 0; i < str.length(); ++i) {
            if (str.charAt(i) == '\n') {
                ret = Math.max(lineW, ret);
                lineW = 0;
                continue;
            }

            Glyph glyph = findGlygh((short) str.charAt(i));

            if (glyph == null) continue;

            lineW += glyph.xOffset + glyph.xAdvance;
        }

        return Math.max(lineW, ret);
    }

    public int stringHeight(String str) {
        int ret = lineHeight;
        int i = 0;

        while (i < str.length()-1) {
            int next = str.indexOf('\n', i+1);

            if (next == -1) break;

            ret += lineHeight;
            i = next;
        }

        return ret;
    }

    private Glyph findGlygh(short id) {
        for (int i = 0; i < glyphs.length; i++) {
            if (glyphs[i].id == id) return glyphs[i];
        }

        if (unknownIdx != -1) {
            return glyphs[unknownIdx];
        }

        return null;
    }
}