import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;
import java.io.IOException;
import java.util.Random;

public class shootan extends MIDlet implements CommandListener {
    static shootan instance = null;
    public Display display = null;

    Random rand = new Random();

    Command join = new Command("Connect", Command.OK, 1);
    Command offline = new Command("Play offline", Command.ITEM, 2);

    TextField username = new TextField("Player name", "Player-" + Math.abs(rand.nextLong()) % 1000, 32, TextField.ANY);
    TextField hostname = new TextField("Server IP", "sffempire.ru", 256, TextField.ANY);

    protected void startApp() {
        instance = this;
        display = Display.getDisplay(this);

        Form f = new Form("shootan");
        f.append(username);
        f.append(hostname);
        f.addCommand(join);
        f.addCommand(offline);
        f.setCommandListener(this);

        display.setCurrent(f);
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
    }

    public static shootan getInstance() {
        return instance;
    }

    public void commandAction(Command command, Displayable displayable) {
        Display.getDisplay(this).setCurrent(Game.getInstance());

        try {
            if (command == join) {
                Game.getInstance().start(username.getString(), hostname.getString(), false);
            }

            if (command == offline) {
                Game.getInstance().start(username.getString(), hostname.getString(), true);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
