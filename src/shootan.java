import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;
import java.io.IOException;
import java.util.Random;

public class shootan extends MIDlet implements CommandListener {
    static shootan instance;
    public Display display = null;
    private static boolean started;

    Form f;
    Random rand = new Random();

    Command join = new Command("Connect", Command.OK, 1);
    Command offline = new Command("Play offline", Command.ITEM, 2);

    TextField username = new TextField("Player name", "Player-" + Math.abs(rand.nextLong()) % 1000, 32, TextField.ANY);
    TextField hostname = new TextField("Server IP", "sffempire.ru", 256, TextField.ANY);

    protected void startApp() {
        if (started) return;
        started = true;
        instance = this;
        display = Display.getDisplay(this);

        f = new Form("shootan");
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

    public void commandAction(Command command, Displayable displayable) {
        boolean offlineMode = false;

        try {
            if (command == join) {
                offlineMode = false;
            }

            if (command == offline) {
                offlineMode = true;
            }

            Game game = new Game(username.getString(), hostname.getString(), offlineMode);
            display.setCurrent(game);

            new Thread(game).start();
        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(e.toString());
            display.setCurrent(alert, f);
        }
    }
}
