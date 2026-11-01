import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;

/**
 * BMFont binary v3 renderer for J2ME (MIDP 2.0 / CLDC 1.1).
 * <p>
 * Coordinates passed to draw() are the TOP-LEFT of a font line, not a baseline.
 * Offsets, advances, negative offsets, kerning and multiple PNG pages are used.
 * All glyph lookup/sorting and allocations happen while loading, not drawing.
 * <p>
 * Export: Binary .fnt, PNG pages, Unicode, "packed" disabled.
 * Page images must already contain correctly rendered RGBA/alpha glyphs.
 */
public final class BMFont {
    public static final int LEFT = 0;
    public static final int CENTER = 1;
    public static final int RIGHT = 2;

    private static final int MAX_PAGES = 256;
    private static final int MAX_GLYPHS = 16384;
    private static final int MAX_KERNINGS = 65536;
    private static final int MAX_BLOCK_BYTES = 4 * 1024 * 1024;

    private static final class Glyph {
        int id, srcX, srcY, width, height;
        int xOffset, yOffset, xAdvance, page, channel;
    }

    private static final class Kern {
        int first, second, amount;
    }

    private Image[] images;
    private String[] pageNames;
    private Glyph[] glyphs;
    private Kern[] kernings;
    private int lineHeight, base, scaleW, scaleH, pageCount;
    private int packed;
    private int fallbackAdvance;

    private BMFont() {
    }

    /**
     * Load a .fnt resource and its pages from the same JAR directory.
     */
    public static BMFont load(String fntPath) throws IOException {
        if (fntPath == null || !fntPath.startsWith("/")) {
            throw new IllegalArgumentException("Use absolute JAR path: /fonts/font.fnt");
        }
        InputStream in = BMFont.class.getResourceAsStream(fntPath);
        if (in == null) throw new IOException("Font not found: " + fntPath);
        BMFont font;
        try {
            font = parse(in);
        } finally {
            in.close();
        }
        String directory = fntPath.substring(0, fntPath.lastIndexOf('/') + 1);
        Image[] pages = new Image[font.pageCount];
        for (int i = 0; i < pages.length; i++) {
            String name = font.pageNames[i].replace('\\', '/');
            // BMFont normally stores bare file names, not absolute paths.
            while (name.startsWith("/")) name = name.substring(1);
            String pagePath = directory + name;
            try {
                pages[i] = Image.createImage(pagePath);
            } catch (IOException e) {
                throw new IOException("Cannot load font page: " + pagePath + " (" + e + ")");
            }
        }
        font.setPages(pages);
        return font;
    }

    /**
     * Load a descriptor with page images supplied by the caller.
     * Useful for preloaded atlases. Does not close input.
     */
    public static BMFont load(InputStream input, Image[] pages) throws IOException {
        if (input == null || pages == null) throw new NullPointerException();
        BMFont font = parse(input);
        font.setPages(pages);
        return font;
    }

    private static BMFont parse(InputStream in) throws IOException {
        if (rawByte(in) != 'B' || rawByte(in) != 'M' || rawByte(in) != 'F') {
            throw new IOException("Not a BMFont binary descriptor");
        }
        if (rawByte(in) != 3) throw new IOException("Only BMFont binary v3 is supported");

        BMFont f = new BMFont();
        boolean haveCommon = false, havePages = false, haveChars = false, haveKerns = false;
        byte[] pageData = null;
        while (true) {
            int type = in.read();
            if (type < 0) break;
            int size = rawInt32(in);
            if (size < 0 || size > MAX_BLOCK_BYTES) throw new IOException("Invalid BMFont block size");
            Block b = new Block(in, size);
            switch (type) {
                case 2: // common, 15 bytes
                    if (haveCommon || size < 15) throw new IOException("Invalid common block");
                    f.lineHeight = b.u16();
                    f.base = b.u16();
                    f.scaleW = b.u16();
                    f.scaleH = b.u16();
                    f.pageCount = b.u16();
                    f.packed = b.u8() & 0x80;
                    // Channel roles (alpha, red, green, blue) are metadata here.
                    // Alpha-packed channel decoding is intentionally unsupported.
                    b.skip(4);
                    haveCommon = true;
                    break;
                case 3: // equal-width null-terminated names (possibly padded)
                    if (havePages) throw new IOException("Duplicate page block");
                    pageData = new byte[size];
                    for (int i = 0; i < pageData.length; i++) pageData[i] = (byte) b.u8();
                    havePages = true;
                    break;
                case 4: // 20-byte glyph records
                    if (haveChars || size % 20 != 0 || size / 20 > MAX_GLYPHS) {
                        throw new IOException("Invalid chars block");
                    }
                    f.glyphs = new Glyph[size / 20];
                    for (int i = 0; i < f.glyphs.length; i++) {
                        Glyph g = new Glyph();
                        g.id = b.i32();
                        g.srcX = b.u16();
                        g.srcY = b.u16();
                        g.width = b.u16();
                        g.height = b.u16();
                        g.xOffset = b.i16();
                        g.yOffset = b.i16();
                        g.xAdvance = b.i16();
                        g.page = b.u8();
                        g.channel = b.u8();
                        if (g.id < 0 || g.id > 0x10FFFF) throw new IOException("Invalid glyph id");
                        f.glyphs[i] = g;
                    }
                    haveChars = true;
                    break;
                case 5: // 10-byte kerning records
                    if (haveKerns || size % 10 != 0 || size / 10 > MAX_KERNINGS) {
                        throw new IOException("Invalid kernings block");
                    }
                    f.kernings = new Kern[size / 10];
                    for (int i = 0; i < f.kernings.length; i++) {
                        Kern k = new Kern();
                        k.first = b.i32();
                        k.second = b.i32();
                        k.amount = b.i16();
                        f.kernings[i] = k;
                    }
                    haveKerns = true;
                    break;
                default: // info (type 1) and future optional blocks
                    break;
            }
            b.skip(b.remaining);
        }
        if (!haveCommon || !havePages || !haveChars) {
            throw new IOException("BMFont missing common/pages/chars block");
        }
        if (f.pageCount < 1 || f.pageCount > MAX_PAGES || f.lineHeight < 1) {
            throw new IOException("Inconsistent BMFont page count or lineHeight");
        }
        f.pageNames = decodePageNames(pageData, f.pageCount);
        if (f.packed != 0) {
            throw new IOException("Packed BMFont channels unsupported: export unpacked RGBA PNG");
        }
        if (f.kernings == null) f.kernings = new Kern[0];
        if (f.glyphs.length > 1) sortGlyphs(f.glyphs, 0, f.glyphs.length - 1);
        if (f.kernings.length > 1) sortKerns(f.kernings, 0, f.kernings.length - 1);
        for (int i = 0; i < f.glyphs.length; i++) {
            if (f.glyphs[i].page >= f.pageCount) throw new IOException("Invalid glyph page");
            if (i > 0 && f.glyphs[i - 1].id == f.glyphs[i].id) {
                throw new IOException("Duplicate glyph id");
            }
        }
        for (int i = 1; i < f.kernings.length; i++) {
            if (compare(f.kernings[i - 1], f.kernings[i]) == 0) {
                throw new IOException("Duplicate kerning pair");
            }
        }
        f.fallbackAdvance = f.lineHeight / 2;
        Glyph space = f.find(32);
        if (space != null) f.fallbackAdvance = space.xAdvance;
        return f;
    }

    /**
     * Official layout: N fixed-size zero-padded names. Also accepts a plain
     * sequence of zero-terminated strings from other exporters.
     */
    private static String[] decodePageNames(byte[] bytes, int count) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("Empty font pages block");
        String[] names = new String[count];
        if (bytes.length % count == 0) {
            int stride = bytes.length / count;
            boolean valid = true;
            for (int i = 0; i < count; i++) {
                int start = i * stride;
                int end = start;
                while (end < start + stride && bytes[end] != 0) end++;
                if (end == start || end == start + stride) {
                    valid = false;
                    break;
                }
                for (int j = end + 1; j < start + stride; j++) {
                    if (bytes[j] != 0) {
                        valid = false;
                        break;
                    }
                }
                if (!valid) break;
                names[i] = utf8Range(bytes, start, end - start);
            }
            if (valid) return names;
        }
        int start = 0;
        for (int i = 0; i < count; i++) {
            int end = start;
            while (end < bytes.length && bytes[end] != 0) end++;
            if (end == start || end == bytes.length) {
                throw new IOException("Invalid BMFont page filenames");
            }
            names[i] = utf8Range(bytes, start, end - start);
            start = end + 1;
        }
        if (start != bytes.length) throw new IOException("Unexpected trailing page data");
        return names;
    }

    private static String utf8Range(byte[] data, int start, int length) throws IOException {
        byte[] bytes = new byte[length];
        System.arraycopy(data, start, bytes, 0, length);
        return new String(bytes, "UTF-8");
    }

    private void setPages(Image[] supplied) throws IOException {
        if (supplied.length != pageCount) throw new IOException("Wrong number of font images");
        for (int i = 0; i < supplied.length; i++) {
            if (supplied[i] == null) throw new IOException("Null font page " + i);
        }
        for (int i = 0; i < glyphs.length; i++) {
            Glyph g = glyphs[i];
            Image im = supplied[g.page];
            if (g.srcX + g.width > im.getWidth() || g.srcY + g.height > im.getHeight()) {
                throw new IOException("Glyph rectangle outside atlas: id=" + g.id);
            }
        }
        images = supplied;
    }

    public int getLineHeight() {
        return lineHeight;
    }

    public int getBase() {
        return base;
    }

    public int getPageCount() {
        return pageCount;
    }

    /**
     * Draws lines with (x,y) at the top-left of the first text line.
     */
    public void draw(Graphics g, String text, int x, int y) {
        drawAligned(g, text, x, y, LEFT);
    }

    /**
     * Each line is independently positioned relative to x.
     */
    public void drawAligned(Graphics g, String text, int x, int y, int align) {
        if (g == null || text == null) throw new NullPointerException();
        if (align != LEFT && align != CENTER && align != RIGHT) {
            throw new IllegalArgumentException("Unknown text alignment");
        }
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                int dx = x;
                if (align != LEFT) {
                    int width = measureLine(text, start, i);
                    dx -= align == CENTER ? width / 2 : width;
                }
                drawLine(g, text, start, i, dx, y);
                y += lineHeight;
                start = i + 1;
            }
        }
    }

    /**
     * Baseline-based API: baselineY is the baseline of the first line.
     */
    public void drawBaseline(Graphics g, String text, int x, int baselineY) {
        draw(g, text, x, baselineY - base);
    }

    /**
     * The widest logical line, including xAdvance and kerning.
     */
    public int stringWidth(String text) {
        if (text == null) throw new NullPointerException();
        int start = 0;
        int max = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                int w = measureLine(text, start, i);
                if (w > max) max = w;
                start = i + 1;
            }
        }
        return max;
    }

    /**
     * Line-box height (not exact visible pixel bounds). Empty string = 0.
     */
    public int stringHeight(String text) {
        if (text == null) throw new NullPointerException();
        if (text.length() == 0) return 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines * lineHeight;
    }

    private int measureLine(String text, int start, int end) {
        int advance = 0;
        int prev = -1;
        for (int i = start; i < end; i++) {
            char ch = text.charAt(i);
            if (ch == '\r') continue;
            if (ch == '\t') {
                advance += 4 * spaceAdvance();
                prev = -1;
                continue;
            }
            int cp = ch;
            if (ch >= 0xD800 && ch <= 0xDBFF && i + 1 < end) {
                char low = text.charAt(i + 1);
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    cp = 0x10000 + ((ch - 0xD800) << 10) + (low - 0xDC00);
                    i++;
                }
            }
            Glyph glyph = findOrFallback(cp);
            if (prev >= 0) advance += kern(prev, cp);
            advance += glyph == null ? fallbackAdvance : glyph.xAdvance;
            prev = cp;
        }
        return advance;
    }

    private void drawLine(Graphics graphics, String text, int start, int end, int cursorX, int y) {
        int prev = -1;
        for (int i = start; i < end; i++) {
            char ch = text.charAt(i);
            if (ch == '\r') continue;
            if (ch == '\t') {
                cursorX += 4 * spaceAdvance();
                prev = -1;
                continue;
            }
            int cp = ch;
            if (ch >= 0xD800 && ch <= 0xDBFF && i + 1 < end) {
                char low = text.charAt(i + 1);
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    cp = 0x10000 + ((ch - 0xD800) << 10) + (low - 0xDC00);
                    i++;
                }
            }
            Glyph glyph = findOrFallback(cp);
            if (prev >= 0) cursorX += kern(prev, cp);
            if (glyph != null) {
                if (glyph.width > 0 && glyph.height > 0) {
                    graphics.drawRegion(images[glyph.page], glyph.srcX, glyph.srcY, glyph.width, glyph.height, Sprite.TRANS_NONE, cursorX + glyph.xOffset, y + glyph.yOffset, Graphics.TOP | Graphics.LEFT);
                }
                cursorX += glyph.xAdvance;
            } else {
                cursorX += fallbackAdvance;
            }
            prev = cp;
        }
    }

    private int spaceAdvance() {
        Glyph sp = find(32);
        return sp == null ? fallbackAdvance : sp.xAdvance;
    }

    private Glyph findOrFallback(int cp) {
        Glyph g = find(cp);
        return g == null ? find(63) : g; // '?' if missing; or fixed advance
    }

    private Glyph find(int id) {
        int lo = 0, hi = glyphs.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int other = glyphs[mid].id;
            if (id < other) hi = mid - 1;
            else if (id > other) lo = mid + 1;
            else return glyphs[mid];
        }
        return null;
    }

    private int kern(int first, int second) {
        int lo = 0, hi = kernings.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Kern k = kernings[mid];
            if (first < k.first || (first == k.first && second < k.second)) hi = mid - 1;
            else if (first > k.first || (first == k.first && second > k.second)) lo = mid + 1;
            else return k.amount;
        }
        return 0;
    }

    private static void sortGlyphs(Glyph[] data, int lo, int hi) {
        int i = lo, j = hi, pivot = data[(lo + hi) >>> 1].id;
        while (i <= j) {
            while (data[i].id < pivot) i++;
            while (data[j].id > pivot) j--;
            if (i <= j) {
                Glyph temp = data[i];
                data[i] = data[j];
                data[j] = temp;
                i++;
                j--;
            }
        }
        if (lo < j) sortGlyphs(data, lo, j);
        if (i < hi) sortGlyphs(data, i, hi);
    }

    private static int compare(Kern a, Kern b) {
        if (a.first < b.first) return -1;
        if (a.first > b.first) return 1;
        if (a.second < b.second) return -1;
        if (a.second > b.second) return 1;
        return 0;
    }

    private static void sortKerns(Kern[] data, int lo, int hi) {
        int i = lo, j = hi;
        Kern pivot = data[(lo + hi) >>> 1];
        while (i <= j) {
            while (compare(data[i], pivot) < 0) i++;
            while (compare(data[j], pivot) > 0) j--;
            if (i <= j) {
                Kern tmp = data[i];
                data[i] = data[j];
                data[j] = tmp;
                i++;
                j--;
            }
        }
        if (lo < j) sortKerns(data, lo, j);
        if (i < hi) sortKerns(data, i, hi);
    }

    private static int rawByte(InputStream in) throws IOException {
        int v = in.read();
        if (v == -1) throw new EOFException("Truncated BMFont header");
        return v;
    }

    private static int rawInt32(InputStream in) throws IOException {
        int b0 = rawByte(in), b1 = rawByte(in);
        int b2 = rawByte(in), b3 = rawByte(in);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static final class Block {
        private final InputStream input;
        int remaining;

        Block(InputStream input, int size) {
            this.input = input;
            remaining = size;
        }

        int u8() throws IOException {
            if (remaining <= 0) throw new EOFException("BMFont block too short");
            int v = input.read();
            if (v < 0) throw new EOFException("Truncated BMFont block");
            remaining--;
            return v;
        }

        int u16() throws IOException {
            return u8() | (u8() << 8);
        }

        int i16() throws IOException {
            return (short) u16();
        }

        int i32() throws IOException {
            int a = u8(), b = u8(), c = u8(), d = u8();
            return a | (b << 8) | (c << 16) | (d << 24);
        }

        void skip(int count) throws IOException {
            if (count < 0 || count > remaining) throw new IOException("Bad BMFont skip");
            while (count > 0) {
                long skipped = input.skip(count);
                if (skipped > 0) {
                    count -= (int) skipped;
                    remaining -= (int) skipped;
                } else {
                    u8();
                    count--;
                }
            }
        }
    }
}
