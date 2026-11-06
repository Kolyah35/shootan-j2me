import javax.microedition.lcdui.Image;
import java.io.IOException;

public class Resources {
    public static final Image blocks;
    public static final Image spritesheet;

    public static final BinaryFont font;
    public static final BinaryFont tiny;
    public static final BinaryFont digits;

    public static final Image redDigits;
    public static final Image orangeDigits;
    public static final Image greenDigits;

    static {
        try {
            blocks = Image.createImage("/blocks.png");
            spritesheet = Image.createImage("/spritesheet.png");

            font = BinaryFont.createFont("/font.bin", "/font.png");
            tiny = BinaryFont.createFont("/tiny.bin", "/tiny.png");
            digits = BinaryFont.createFont("/digits.bin", "/digits.png");

            redDigits = tint(digits.fontAtlas, 0xE62937);
            orangeDigits = tint(digits.fontAtlas, 0xFFA100);
            greenDigits = tint(digits.fontAtlas, 0x00E430);
        } catch (IOException e) {
            throw new RuntimeException(e.toString());
        }
    }

    public static Image tint(Image source, int color) {
        int width = source.getWidth();
        int height = source.getHeight();

        int[] pixels = new int[width * height];

        source.getRGB(pixels, 0, width, 0, 0, width, height);

        color &= 0x00FFFFFF;

        for (int i = 0; i < pixels.length; i++) {
            int alpha = pixels[i] & 0xFF000000;
            pixels[i] = alpha | color;
        }

        return Image.createRGBImage(pixels, width, height, true);
    }
}
