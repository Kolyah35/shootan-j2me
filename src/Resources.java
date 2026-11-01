import javax.microedition.lcdui.Image;
import java.io.IOException;

public class Resources {
    public static final Image blocks;
    public static final Image spritesheet;
    public static final BMFont defaultFont;
    public static final BMFont tinyFont;

    static {
        try {
            blocks = Image.createImage("/blocks.png");
            spritesheet = Image.createImage("/spritesheet.png");
            defaultFont = BMFont.load("/grixel.fnt");
            tinyFont = BMFont.load("/tiny.fnt");
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }
}
