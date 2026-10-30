import javax.microedition.lcdui.Image;
import java.io.IOException;

public class Resources {
    public static final Image blocks;
    public static final Image spritesheet;

    static {
        try {
            blocks = Image.createImage("/blocks.png");
            spritesheet = Image.createImage("/spritesheet.png");
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }
}
