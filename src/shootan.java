import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;
import java.util.Random;

public class shootan extends MIDlet implements CommandListener {
    static shootan instance;
    public Display display = null;
    private static boolean started;
    GameSettings settings;

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

        settings = GameSettings.load();
        if (settings.username != null) username.setString(settings.username);
        hostname.setString(settings.hostname);
        hatList.setSelectedIndex(settings.hat, true);
        selectedHat.setText("Selected hat: " + hatList.getString(settings.hat));

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
        if (settings != null) {
            settings.username = username.getString();
            settings.hostname = hostname.getString();
            settings.hat = (byte) hatList.getSelectedIndex();
            try { settings.save(); }
            catch (Exception e) { System.out.println("RMS error: " + e); }
        }
    }

    public void saveSettings() {
        try {
            settings.save();
        } catch (Exception e) {
            System.out.println("RMS error: " + e);
            Displayable old = display.getCurrent();
            Alert alert = new Alert("Save error", e.toString(), null, AlertType.WARNING);
            alert.setTimeout(3500);
            display.setCurrent(alert, old == null ? mainMenu : old);
        }
    }

    private void saveProfile() {
        settings.username = username.getString();
        settings.hostname = hostname.getString();
        settings.hat = (byte) hatList.getSelectedIndex();
        saveSettings();
    }

    public void commandAction(Command command, Displayable displayable) {
        boolean offlineMode = false;

        if (displayable == hatList) {
            selectedHat.setText("Selected hat: " + hatList.getString(hatList.getSelectedIndex()));
            saveProfile();
            display.setCurrent(mainMenu);
            return;
        }

        if (command == backToMenu) {
            saveProfile();
            display.setCurrent(mainMenu);
            return;
        }

        if (command == selectHat) {
            display.setCurrent(hatList);
            return;
        }

        if (command != join && command != offline && command != reconnect) return;

        try {
            saveProfile();
            if (command == join || command == reconnect) {
                offlineMode = false;
            }

            if (command == offline) {
                offlineMode = true;
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
