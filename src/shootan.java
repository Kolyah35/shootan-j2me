import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;
import java.util.Random;

public class shootan extends MIDlet implements CommandListener {
    static shootan instance;
    public Display display = null;
    private static boolean started;

    Form mainMenu;
    Random rand = new Random();

    Command join = new Command("Connect", Command.OK, 1);
    Command offline = new Command("Play offline", Command.ITEM, 2);
    Command selectHat = new Command("Select hat", Command.ITEM, 3);

    Command select = new Command("Select", Command.OK, 1);

    TextField username = new TextField("Player name", "Player-" + Math.abs(rand.nextLong()) % 1000, 32, TextField.ANY);
    TextField hostname = new TextField("Server IP", "sffempire.ru", 256, TextField.ANY);
    List hatList = new List("Hats", List.IMPLICIT, new String[]{"None", "Wizard", "Loofy", "Sunglasses"}, null);

    Command reconnect = new Command("Reconnect", Command.OK, 2);
    Command backToMenu = new Command("Back to menu", Command.EXIT, 1);

    StringItem selectedHat = new StringItem(null, "Selected hat: None");

    protected void startApp() {
        if (started) return;
        started = true;
        instance = this;
        display = Display.getDisplay(this);

        hatList.setSelectCommand(select);
        hatList.setCommandListener(this);

        mainMenu = new Form("shootan");
        mainMenu.append(username);
        mainMenu.append(hostname);
        mainMenu.append(selectedHat);
        mainMenu.addCommand(join);
        mainMenu.addCommand(offline);
        mainMenu.addCommand(selectHat);
        mainMenu.setCommandListener(this);

        display.setCurrent(mainMenu);
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
    }

    public void commandAction(Command command, Displayable displayable) {
        boolean offlineMode = false;

        if (displayable == hatList) {
            selectedHat.setText("Selected hat: " + hatList.getString(hatList.getSelectedIndex()));
            display.setCurrent(mainMenu);
            return;
        }

        if (command == backToMenu) {
            display.setCurrent(mainMenu);
            return;
        }

        if (command == selectHat) {
            display.setCurrent(hatList);
            return;
        }

        try {
            if (command == join || command == reconnect) {
                offlineMode = false;
            }

            if (command == offline) {
                offlineMode = true;
                display.setCurrent(new Alert("Offline mode is not ready yet and made just for performance test"), mainMenu);
            }

            Game game = new Game(username.getString(), hostname.getString(), offlineMode, (byte) hatList.getSelectedIndex());
            display.setCurrent(game);

            new Thread(game).start();
        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(e.toString());
            display.setCurrent(alert, mainMenu);
        }
    }

    public void showErrorScreen(String reason) {
        Form errorScreen = new Form("Error!");
        errorScreen.append(reason);
        errorScreen.addCommand(backToMenu);
        errorScreen.addCommand(reconnect);
        errorScreen.setCommandListener(this);

        display.setCurrent(errorScreen);
    }
}
