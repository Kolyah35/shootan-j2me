import javax.microedition.lcdui.*;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.lcdui.game.Sprite;

import java.io.*;
import java.util.Random;
import java.util.Vector;

public class Game extends GameCanvas implements Runnable, CommandListener {
//    public static final int[] MAX_BULLETS = {20, 12, 4};
    public static final float[] BULLET_SPEED = {0.6f, 0.7f, 0.8f};
    public static final int[] RELOAD_TIME = {15, 80, 105};

    public static final int GRENADE_BURST_RADIUS = 3;
    public static final int GRENADE_DAMAGE = 50;
    public static final float GRENADE_SPEED = 0.7f;
    public static final int GRENADE_LIFETIME = 125;

    public static final int SCREEN_NONE = 0;
    public static final int SCREEN_PAUSE = 1;
    public static final int SCREEN_CHAT = 2;
    public static final int SCREEN_SETTINGS = 3;

    public Multiplayer multiplayer = null;
    public Level lvl = null;
    public Player player = new Player();
    public Timer timer = null;

    private static final int[] FPS_CHOICES = new int[]{20, 30, 60};
    private int targetFpsChoice = 1;
    private boolean debug = false;
    private boolean syncAimDirection = true;
    private int settingChoice = 0;
    private static final String[] SETTINGS_CHOICES = new String[]{"Target FPS: 30 FPS", "Debug stats: NO", "Sync aim with direction: YES"};

    private boolean inputClimbUpJump = false;
    private boolean inputClimbDown = false;
    private boolean inputLeft = false;
    private boolean inputRight = false;
    private boolean inputRotateLeft = false;
    private boolean inputRotateRight = false;
    private boolean inputShoot = false;
    private boolean inputGrenade = false;

    public boolean shouldClose = false;
    public int screen = SCREEN_NONE;

    public long lastMsgTime = -1000;
    public boolean checkedChat = true;

    public static class Player extends Entity implements Scorable {
        public String username;
        public double angle = 0;
        public double lastAngle = Double.NaN;
        public double aimCos;
        public double aimSin;

        public int id;
        public byte hat = 0;
        public byte weapon = 0;
        public int hp = 100;
        public boolean onGround = false;
        public boolean onLadder = false;

        // weapons
        public boolean[] weapons = new boolean[]{true, false, false};
        public int[] reloadTimes = new int[]{0, 0, 0};

        public boolean grenade = false;

        public boolean isDied = false;

        public int coyoteTime = 0;
        public int score = 0;

        public int getScore() { return this.score; }

        public String getUsername() { return this.username; }

        private Random rand;

        public void updateAim() {
            if (angle != lastAngle) {
                lastAngle = angle;
                aimCos = Math.cos(lastAngle);
                aimSin = Math.sin(lastAngle);
            }
        }

        public void respawn(Level lvl) {
            if (rand == null) rand = new Random();

            if (lvl.spawnpoints != null) {
                int idx = rand.nextInt(lvl.spawnpoints.length);
                x = lvl.spawnpoints[idx][0];
                y = lvl.spawnpoints[idx][1];
            }
        }
    };

    public static class Bullet extends Entity {
        public int id;
        public int ownerId;
        public int weaponId;
    };

    private final Vector bullets = new Vector();

    public static class Grenade extends Entity {
        public int id;
        public int lifeTime;
    }

    private Graphics g;

    private final Vector grenades = new Vector();

    // if not zero then round end and leaderboard screen pops out
    private int winnerId = 0;

    private Scorable[] leaderboard;

    private static final String[] PAUSED_CHOICES = new String[]{"Resume", "Open chat", "Settings", "Quit to menu"};
    private int pauseChoice = 0;
    private boolean fromDeathScreen = false;

    private final TextBox chatBox = new TextBox("Write a message", null, 255, TextField.ANY);
    private final Command chatSend = new Command("Send", Command.OK, 1);

    private static final int CHAT_PADDING = 6;
    private static final int CHAT_MAX_MESSAGE = 512;

    private final StringBuffer chatText = new StringBuffer(2048);
    private String chatRenderText = "";
    private int chatLines = 0;

    public Game(String playerName, String host, boolean offline, byte hatId) throws IOException {
        super(false);

        GameSettings saved = shootan.instance.settings;
        targetFpsChoice = saved.targetFpsChoice;
        debug = saved.debug;
        syncAimDirection = saved.syncAimDirection;
        SETTINGS_CHOICES[0] = "Target FPS: " + FPS_CHOICES[targetFpsChoice] + " FPS";
        SETTINGS_CHOICES[1] = "Debug stats: " + (debug ? "YES" : "NO");
        SETTINGS_CHOICES[2] = "Sync aim with direction: " + (syncAimDirection ? "YES" : "NO");

        if (!offline) {
            multiplayer = new Multiplayer(this, host);
        }

        player.username = playerName;
        player.hat = (byte) hatId;

        chatBox.setInitialInputMode("MIDP_LOWERCASE_LATIN");
        chatBox.addCommand(chatSend);
        chatBox.addCommand(new Command("Back", Command.CANCEL, 2));
        chatBox.setCommandListener(this);
    }

    public void run() {
        g = getGraphics();

        int offX = 0;
        int offY = 0;

        setFullScreenMode(true);

        // create death overlay
        int[] pixels = new int[getWidth() * getHeight()];

        int color = 0x80000000;

        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = color;
        }

        Image darkOverlay = Image.createRGBImage(pixels, getWidth(), getHeight(), true);

        // offline mode
        if (multiplayer == null) {
            try {
                connectedServer(Level.createFromRes("/world.dat"));
            } catch (IOException e) {
                throw new RuntimeException("Failed to load level from res. Place your world.dat into res folder of JAR file.");
            }

            player.respawn(lvl);
        } else {
            multiplayer.connect();
        }

        long lastElapsed = 0;

        player.updateAim();

        String debugText = "";
        long lastDebugUpdate = -1000;
        int maxDrawMs = 0;
        int maxFlushMs = 0;
        int maxNetMs = 0;

        while (!shouldClose) {
            long frameStart = System.currentTimeMillis();

            if (multiplayer != null) multiplayer.update();
            int netMs = (int)(System.currentTimeMillis() - frameStart);
            
            // draw bg
            g.setColor(0x040432);
            g.fillRect(0, 0, this.getWidth(), this.getHeight());

            long drawStart = 0;

            if (lvl != null) {
                if (multiplayer != null || screen == SCREEN_NONE) {
                    timer.advanceTime();

                    for (int tick = 0; tick < timer.ticks; tick++) {
                        if (!player.isDied) {
                            tickPlayer();
                        }

                        // update bullets
                        for (int i = bullets.size() - 1; i >= 0; i--) {
                            Bullet b = (Bullet) bullets.elementAt(i);

                            float prevSpeedX = b.speedX;
                            float prevSpeedY = b.speedY;

                            lvl.moveEntity(b, 0.3f, 0.2f);

                            boolean shouldDelete =
                                    b.x < 0 || b.x > Level.LEVEL_SIZE || b.y < 0 || b.y > Level.LEVEL_SIZE || prevSpeedX != b.speedX || prevSpeedY != b.speedY;

                            if (shouldDelete) bullets.removeElementAt(i);
                        }

                        // update grenades
                        for (int i = grenades.size() - 1; i >= 0; i--) {
                            Grenade grenade = (Grenade) grenades.elementAt(i);

                            grenade.speedY += 0.02f;

                            float prevSpeedX = grenade.speedX;
                            // float prevSpeedY = grenade.speedY;

                            lvl.moveEntity(grenade, 0.5f, 0.5f);

                            if (prevSpeedX != grenade.speedX) grenade.speedX *= -1;
                            if (grenade.speedX > -0.05f && grenade.speedX < 0.05f) grenade.speedX = 0;

                            grenade.speedX *= 0.95f;
                            grenade.speedY *= 0.99f;

                            boolean shouldDelete = grenade.x < 0 || grenade.x > Level.LEVEL_SIZE || grenade.y < 0 || grenade.y > Level.LEVEL_SIZE;

                            if (multiplayer == null) {
                                grenade.lifeTime--;

                                float dx = player.x - grenade.x;
                                float dy = player.y - grenade.y;

                                if (grenade.lifeTime <= 0) {
                                    if ((dx < GRENADE_BURST_RADIUS && dx > -GRENADE_BURST_RADIUS) || (dy < GRENADE_BURST_RADIUS && dy > -GRENADE_BURST_RADIUS)) {
                                        // player in offline mode the only one
                                        player.hp -= GRENADE_DAMAGE;

                                        if (player.hp <= 0) {
                                            player.isDied = true;
                                        }
                                    }

                                    shouldDelete = true;
                                }
                            }

                            if (shouldDelete) grenades.removeElementAt(i);
                        }

                        for (int i = 0; i < player.reloadTimes.length; i++) {
                            if (player.reloadTimes[i] > 0) {
                                player.reloadTimes[i]--;
                            }
                        }

                        if (multiplayer == null && lvl.collectibles != null) {
                            for (int i = 0; i < lvl.collectibles.length; i++) lvl.collectibles[i].respawnTime--;
                        }

                        // offset shit
                        offX = player.pixelsX() - getWidth() / 2;
                        offY = player.pixelsY() - getHeight() / 2;

                        if (offX < 0) offX = 0;
                        if (offX > Level.LEVEL_SIZE * 8 - getWidth()) offX = Level.LEVEL_SIZE * 8 - getWidth();

                        if (offY < 0) offY = 0;
                        if (offY > Level.LEVEL_SIZE * 8 - getHeight()) offY = Level.LEVEL_SIZE * 8 - getHeight();
                    }
                } else {
                    timer.reset();
                }

                drawStart = System.currentTimeMillis();
                lvl.render(g, offX, offY, getWidth(), getHeight());

                if (multiplayer != null) {
                    // draw remote players
                    for (int i = 0; i < multiplayer.remotePlayers.size(); i++) {
                        RemotePlayer pl = (RemotePlayer) multiplayer.remotePlayers.elementAt(i);

                        if (pl.isDied) continue;

                        int x = pl.x - offX;
                        int y = pl.y - offY;

                        if (x >= 0 && x < getWidth() && y > 0 && y < getHeight()) {
                            g.drawRegion(Resources.spritesheet, 32, 8, 8, 8, pl.transform, x, y, Graphics.TOP | Graphics.LEFT);
                            drawWeapon(pl.weapon, pl.transform, x + 4, y, false);

                            if (pl.hat != 0) {
                                drawHat(x, y, pl.transform, pl.hat);
                            }

                            int height = Resources.defaultFont.getLineHeight();

                            Resources.tinyFont.draw(g, pl.username, x + 4 - pl.usernameWidth / 2, y - height);
                        }
                    }
                }

                for (int i = 0; i < bullets.size(); i++) {
                    Bullet bullet = (Bullet) bullets.elementAt(i);

                    int x = bullet.pixelsX() - offX;
                    int y = bullet.pixelsY() - offY;

                    if (x >= -8 && x < getWidth() && y > -6 && y < getHeight()) {
                        g.drawRegion(Resources.spritesheet, 0, 20, 3, 1, 0, x, y, Graphics.TOP | Graphics.LEFT);
                    }
                }

                for (int i = 0; i < grenades.size(); i++) {
                    Grenade grenade = (Grenade) grenades.elementAt(i);

                    int x = grenade.pixelsX() - offX;
                    int y = grenade.pixelsY() - offY;

                    if (x >= -6 && x < getWidth() && y > -6 && y < getHeight()) {
                        g.drawRegion(Resources.spritesheet,  11, 32, 5, 5, 0, x, y, Graphics.TOP | Graphics.LEFT);
                    }
                }

                int cx = player.pixelsX() - offX + 4;
                int cy = player.pixelsY() - offY + 4;

                int transform = player.aimCos < 0 ? Sprite.TRANS_MIRROR : Sprite.TRANS_NONE;

                // draw player and other things
                if (!player.isDied) {
                    g.drawRegion(Resources.spritesheet, 32, 8, 8, 8, transform, cx - 4, cy - 4, Graphics.TOP | Graphics.LEFT);

                    if (player.hat != 0) {
                        drawHat(cx - 4, cy - 4, transform, player.hat);
                    }

                    drawWeapon(player.weapon, transform, cx, cy - 4, false);

                    g.drawRegion(Resources.spritesheet, 54, 32, 5, 5, 0, cx + (int)(20 * player.aimCos), cy + (int)(20 * player.aimSin), Graphics.HCENTER | Graphics.VCENTER);

                    if (player.reloadTimes[player.weapon] > 0) {
                        Resources.defaultFont.draw(g, "RELOADING... ", 0, 12);
                    }

                    if (!checkedChat && System.currentTimeMillis() - lastMsgTime < 2000) {
                        Resources.defaultFont.draw(g, "NEW MESSAGE", 0, 23);
                    }

                    int inner = player.hp > 60 ? 0x00E430 : player.hp > 25 ? 0xFFA100 : 0xE62937;
                    int stroke = player.hp > 60 ? 0x00752C : player.hp > 25 ? 0x804E04 : 0x6B0404;

                    g.setColor(stroke);
                    g.fillRect(0, 0, getWidth(), 11);

                    g.setColor(0);
                    g.fillRect(2, 2, getWidth() - 4, 7);

                    g.setColor(inner);
                    g.fillRect(2, 2, (int)(getWidth() * (player.hp / 100.f)) - 4, 7);

                    String hp = "HP: " + player.hp;
                    Resources.defaultFont.draw(g, hp, (getWidth() - Resources.defaultFont.stringWidth(hp)) / 2, -3);

                    drawWeapon((byte) 0, 0, getWidth() - 14, 13, !player.weapons[0]);
                    drawWeapon((byte) 1, 0, getWidth() - 24, 22, !player.weapons[1]);
                    drawWeapon((byte) 2, 0, getWidth() - 32, 31, !player.weapons[2]);

                    if (player.grenade) {
                        g.drawRegion(Resources.spritesheet, 11, 32, 5, 5, 0, getWidth() - 5, 41, Graphics.TOP | Graphics.LEFT);
                    } else {
                        g.drawRegion(Resources.spritesheet, 31, 32, 5, 5, 0, getWidth() - 5, 41, Graphics.TOP | Graphics.LEFT);
                    }
                } else if (winnerId == 0) {
                    g.drawImage(darkOverlay, 0, 0, Graphics.TOP | Graphics.LEFT);

                    Resources.defaultFont.drawAligned(g, "You died!\nScore: " + player.score + "\nPress 0 to revive\nPress 5 to open chat",
                            getWidth() / 2,
                            getHeight() / 2 - 30, BMFont.CENTER
                    );
                }

                if (winnerId != 0) {
                    g.drawImage(darkOverlay, 0, 0, Graphics.TOP | Graphics.LEFT);

                    Resources.defaultFont.drawAligned(g, "ROUND END", getWidth() / 2, 10, BMFont.CENTER);
                    Resources.defaultFont.drawAligned(g, "WAITING FOR ROUND BEGIN", getWidth() / 2, getHeight() - 20, BMFont.CENTER);

                    drawLeaderboard(30, true);
                }
            }

            int drawMs = (int)(System.currentTimeMillis() - drawStart);

            if (timer != null && debug) {
                Resources.tinyFont.draw(g, debugText, 0, 35);
            }

            int lineHeight = Resources.tinyFont.getLineHeight();

            if (screen != SCREEN_NONE) {
                g.drawImage(darkOverlay, 0, 0, Graphics.TOP | Graphics.LEFT);

                Resources.tinyFont.drawAligned(g, "Back", getWidth(), getHeight() - lineHeight, BMFont.RIGHT);

                switch (screen) {
                    case SCREEN_PAUSE:
                        drawScreen(PAUSED_CHOICES, pauseChoice);
                        Resources.tinyFont.drawAligned(g, "Select", getWidth() / 2, getHeight() - lineHeight, BMFont.CENTER);
                        drawLeaderboard(90, false);
                        break;
                    case SCREEN_CHAT:
                        checkedChat = true;

                        int y = getHeight() - CHAT_PADDING - chatLines * lineHeight;

                        if (y < CHAT_PADDING) y = CHAT_PADDING;

                        Resources.tinyFont.draw(g, chatRenderText, CHAT_PADDING, y);
                        Resources.tinyFont.drawAligned(g, "Send", getWidth() / 2, getHeight() - lineHeight, BMFont.CENTER);
                        break;
                    case SCREEN_SETTINGS:
                        drawScreen(SETTINGS_CHOICES, settingChoice);
                        Resources.tinyFont.drawAligned(g, "Change", getWidth() / 2, getHeight() - lineHeight, BMFont.CENTER);
                        break;
                }
            } else {
                Resources.tinyFont.drawAligned(g, "Pause", getWidth(), getHeight() - lineHeight, BMFont.RIGHT);
            }

            long flushStart = System.currentTimeMillis();
            flushGraphics();
            int flushMs = (int)(System.currentTimeMillis() - flushStart);

            long elapsed = System.currentTimeMillis() - frameStart;
            lastElapsed = elapsed;
            long sleep = 1000 / FPS_CHOICES[targetFpsChoice] - elapsed;

            // debug
            if (System.currentTimeMillis() - lastDebugUpdate >= 1000 && timer != null) {
                maxNetMs = Math.max(maxNetMs, netMs);
                maxDrawMs = Math.max(maxDrawMs, drawMs);
                maxFlushMs = Math.max(maxFlushMs, flushMs);

                debugText =
                            "fps: " + timer.fps + "\n" +
                            "frame: " + lastElapsed + " ms\n" +
                            "net: " + netMs + "ms max: " + maxNetMs + " ms\n" +
                            "draw: " + drawMs + "ms max: " + maxDrawMs + " ms\n" +
                            "flush: " + flushMs + "ms max: " + maxFlushMs + " ms\n" +
                            "free heap: " + Runtime.getRuntime().freeMemory() / 1024 + " KB";
                lastDebugUpdate = System.currentTimeMillis();
                maxFlushMs = 0;
                maxNetMs = 0;
                maxDrawMs = 0;
            }

            if (sleep > 0) {
                try { Thread.sleep(sleep); }
                catch (InterruptedException e) { break; }
            }
        }
    }

    public void addBullet(Bullet bullet) {
        if (bullets.contains(bullet)) {
            return;
        }

        bullets.addElement(bullet);
    }

    public void removeBullet(int id) {
        for (int i = 0; i < bullets.size(); i++) {
            Bullet bullet = (Bullet)bullets.elementAt(i);
            if (bullet.id == id) {
                bullets.removeElementAt(i);
                return;
            }
        }
    }

    public void addGrenade(Grenade grenade) {
        if (grenades.contains(grenade)) {
            return;
        }

        grenades.addElement(grenade);
    }

    public void removeGrenade(int id) {
        for (int i = 0; i < grenades.size(); i++) {
            Grenade grenade = (Grenade)grenades.elementAt(i);
            if (grenade.id == id) {
                grenades.removeElementAt(i);
                return;
            }
        }
    }

    public void connectedServer(Level lvl) {
        this.lvl = lvl;
        timer = new Timer(60.f);
    }

    public void disconnectedServer(String reason) {
        this.shouldClose = true;
        shootan.instance.showErrorScreen(reason);
    }

    public void roundEnd(int winnerId) {
        this.winnerId = winnerId;
        updateLeaderboard();
    }

    public void roundBegin() {
        this.winnerId = 0;

        if (multiplayer != null && player.isDied) {
            multiplayer.sendRevive();
        }
    }

    public void addChatMessage(String message) {
        if (message == null || message.length() == 0) return;

        BMFont font = Resources.tinyFont;
        int maxWidth = getWidth() - CHAT_PADDING * 2 - 4;

        if (maxWidth <= 0) return;

        StringBuffer cleaned = new StringBuffer();

        for (int i = 0; i < message.length() && cleaned.length() < CHAT_MAX_MESSAGE; i++) {
            char c = message.charAt(i);

            if (c == '\n' || c == '\r' || c == '\t') c = ' ';

            if (c >= 32 && c != 127) {
                cleaned.append(c);
            }
        }

        String text = cleaned.toString();
        StringBuffer line = new StringBuffer(64);

        int pos = 0;

        while (pos < text.length()) {
            while (pos < text.length() && text.charAt(pos) == ' ') pos++;

            if (pos >= text.length()) break;

            int start = pos;

            while (pos < text.length() && text.charAt(pos) != ' ') pos++;

            String word = text.substring(start, pos);

            if (line.length() > 0) {
                String candidate = line.toString() + " " + word;

                if (font.stringWidth(candidate) <= maxWidth) {
                    line.append(' ').append(word);
                    continue;
                }

                appendChatLine(line.toString());
                line.setLength(0);
            }

            if (font.stringWidth(word) <= maxWidth) {
                line.append(word);
                continue;
            }

            for (int i = 0; i < word.length(); ) {
                int next = i + 1;

                char c = word.charAt(i);
                if (c >= 0xD800 && c <= 0xDBFF && next < word.length()) {
                    char low = word.charAt(next);
                    if (low >= 0xDC00 && low <= 0xDFFF) next++;
                }

                String part = word.substring(i, next);
                String candidate = line.toString() + part;

                if (line.length() > 0 && font.stringWidth(candidate) > maxWidth) {
                    appendChatLine(line.toString());
                    line.setLength(0);
                }

                line.append(part);
                i = next;
            }
        }

        if (line.length() > 0) appendChatLine(line.toString());

        chatRenderText = chatText.toString();
        checkedChat = false;
        lastMsgTime = System.currentTimeMillis();
    }

    private void appendChatLine(String line) {
        if (chatLines > 0) chatText.append('\n');

        chatText.append(line);
        chatLines++;

        int maxLines = (getHeight() - CHAT_PADDING * 2) / Resources.tinyFont.getLineHeight();

        if (maxLines < 1) maxLines = 1;

        while (chatLines > maxLines) {
            int end = 0;

            while (end < chatText.length() && chatText.charAt(end) != '\n') {
                end++;
            }

            if (end < chatText.length()) {
                chatText.delete(0, end + 1);
                chatLines--;
            } else {
                chatText.setLength(0);
                chatLines = 0;
            }
        }
    }

    private void updateLeaderboard() {
        if (multiplayer == null) {
            leaderboard = new Scorable[]{player};
            return;
        }

        int playerCount = multiplayer.remotePlayers.size() + 1;

        if (leaderboard == null || leaderboard.length != playerCount) {
            leaderboard = new Scorable[playerCount];
        }

        multiplayer.remotePlayers.copyInto(leaderboard);
        leaderboard[leaderboard.length - 1] = player;

        for (int i = 1; i < leaderboard.length; i++) {
            Scorable player = leaderboard[i];
            int j = i - 1;

            while (j >= 0 && leaderboard[j].getScore() < player.getScore()) {
                leaderboard[j + 1] = leaderboard[j];
                j--;
            }

            leaderboard[j + 1] = player;
        }
    }

    private void drawWeapon(byte weapon, int transform, int x, int y, boolean tinted) {
        int wx;

        switch (weapon) {
            case 0:
                wx = transform == Sprite.TRANS_NONE ? x : x - 14;
                if (tinted) {
                    g.drawRegion(Resources.spritesheet, 7, 16, 14, 8, transform, wx, y, Graphics.TOP | Graphics.LEFT);
                } else {
                    g.drawRegion(Resources.spritesheet, 0, 0, 14, 8, transform, wx, y, Graphics.TOP | Graphics.LEFT);
                }
                break;
            case 1:
                wx = transform == Sprite.TRANS_NONE ? x : x - 24;

                if (tinted) {
                    g.drawRegion(Resources.spritesheet, 21, 16, 24, 8, transform, wx, y, Graphics.TOP | Graphics.LEFT);
                } else {
                    g.drawRegion(Resources.spritesheet, 14, 0, 24, 8, transform, wx, y, Graphics.TOP | Graphics.LEFT);
                }
                break;
            case 2:
                wx = transform == Sprite.TRANS_NONE ? x : x - 32;

                if (tinted) {
                    g.drawRegion(Resources.spritesheet, 45, 16, 32, 8, transform, wx, y, Graphics.TOP | Graphics.LEFT);
                } else {
                    g.drawRegion(Resources.spritesheet, 0, 8, 32, 8, transform, wx, y, Graphics.TOP | Graphics.LEFT);
                }
                break;
        }
    }

    private void drawHat(int playerX, int playerY, int transform, byte hat) {
        switch (hat) {
            case 0: return; // no hat
            case 1:
                g.drawRegion(Resources.spritesheet, 40, 8, 8, 8, transform, playerX, playerY - 8, Graphics.TOP | Graphics.LEFT);
                return;
            case 2:
                g.drawRegion(Resources.spritesheet, 38, 0, 8, 6, transform, playerX, playerY - 6, Graphics.TOP | Graphics.LEFT);
                return;
            case 3:
                g.drawRegion(Resources.spritesheet, 46, 0, 7, 3, transform, playerX, playerY + 1, Graphics.TOP | Graphics.LEFT);
        }
    }

    private void drawScreen(String[] choices, int choice) {
        for (int i = 0; i < choices.length; i++) {
            int y = 20 + 15 * i;
            Resources.defaultFont.draw(g, choices[i], 10, y);

            if (i == choice) {
                g.setColor(0xFFFFFF);
                g.drawLine(10, y + 14, 10 + Resources.defaultFont.stringWidth(choices[i]), y + 14);
            }
        }
    }

    private void drawLeaderboard(int y, boolean leader) {
        if (leaderboard == null) return;

        int x = leader ? 15 : 10;

        if (leader) {
            g.drawRegion(Resources.spritesheet, 0, 21, 7, 6, 0, 4, y + 7, Graphics.TOP | Graphics.LEFT);
        }

        for (int i = 0; i < leaderboard.length; i++) {
            Resources.defaultFont.draw(g, leaderboard[i].getUsername(), x, y + 12 * i);
            Resources.defaultFont.drawAligned(g, String.valueOf(leaderboard[i].getScore()), getWidth() - 5, y + 12 * i, BMFont.RIGHT);
        }
    }

    private void tickPlayer() {
        // scary code
        if (inputClimbUpJump || inputClimbDown) {
            boolean isLadderAbove = false;

            if (Level.BLOCK_LADDER == lvl.getBlockFG((int)player.x, (int)player.y - 1)) {
                isLadderAbove = true;
                player.x = (int)player.x;
            }

            if (Level.BLOCK_LADDER == lvl.getBlockFG((int)player.x + 1, (int)player.y - 1)) {
                isLadderAbove = true;
                player.x = ((int)player.x) + 1;
            }

            boolean isLadderUnder = false;

            if (Level.BLOCK_LADDER == lvl.getBlockFG((int)player.x, ((int)player.y))) {
                isLadderUnder = true;
                player.x = (int)player.x;
            }

            if (Level.BLOCK_LADDER == lvl.getBlockFG((int)player.x + 1, (int)(player.y))) {
                isLadderUnder = true;
                player.x = ((int)player.x) + 1;
            }

            if (isLadderAbove || isLadderUnder) {
                player.onLadder = true;
                player.speedY = inputClimbUpJump ? -0.2f : 0.2f;
            } else if (player.coyoteTime > 0 && inputClimbUpJump) {
                player.speedY = -0.3f;
                player.coyoteTime = 0;
            } else if (player.onLadder) {
                player.onLadder = false;
                player.speedY = -0.3f;
            }
        }

        if (!player.onLadder) {
            if (inputLeft) {
                if (player.speedX > -0.175f) {
                    player.speedX -= 0.03f;
                }

                if (player.speedX < -0.175f) {
                    player.speedX = -0.175f;
                }
            }

            if (inputRight) {
                if (player.speedX < 0.175f) {
                    player.speedX += 0.03f;
                }

                if (player.speedX > 0.175f) {
                    player.speedX = 0.175f;
                }
            }
        } else {
            if (inputRight || inputLeft) player.onLadder = false;
        }

        if (syncAimDirection && ((inputLeft && player.aimCos > 0) || (inputRight && player.aimCos < 0))) {
            player.angle = Math.PI - player.angle;
            player.updateAim();

            if (multiplayer != null) {
                multiplayer.sendAngle((float) Math.toDegrees(player.angle));
            }
        }

        if (inputRotateRight || inputRotateLeft) {
            if (inputRotateRight) player.angle += 0.06f;
            if (inputRotateLeft) player.angle -= 0.06f;

            if (multiplayer != null) {
                multiplayer.sendAngle((float) Math.toDegrees(player.angle));
            }

            player.updateAim();
        }

        if (inputShoot && !player.isDied && player.reloadTimes[player.weapon] == 0) {
            if (multiplayer != null) {
                multiplayer.sendShoot((float) player.angle);
            } else {
                Bullet bullet = new Bullet();
                bullet.x = player.x;
                bullet.y = player.y;
                bullet.speedY = (float) Math.sin(player.angle) * BULLET_SPEED[player.weapon];
                bullet.speedX = (float) Math.cos(player.angle) * BULLET_SPEED[player.weapon];
                bullet.weaponId = player.weapon;
                bullet.id = bullets.size();
                bullet.ownerId = player.id;

                addBullet(bullet);
            }

            player.reloadTimes[player.weapon] = RELOAD_TIME[player.weapon];
        }

        if (inputGrenade && !player.isDied && player.grenade) {
            if (multiplayer != null) {
                multiplayer.sendThrowGrenade((float) player.angle);
            } else {
                Grenade grenade = new Grenade();
                grenade.x = player.x + 0.5f;
                grenade.y = player.y + 0.5f;
                grenade.speedY = (float) Math.sin(player.angle) * GRENADE_SPEED;
                grenade.speedX = (float) Math.cos(player.angle) * GRENADE_SPEED;
                grenade.id = grenades.size();
                grenade.lifeTime = GRENADE_LIFETIME;

                addGrenade(grenade);
            }

            player.grenade = false;
        }

        if (!player.onLadder) {
            player.speedY += 0.02f;
        }

        float prevSpeedY = player.speedY;

        lvl.moveEntity(player, 1.f, 1.f);

        if (multiplayer == null && lvl.collectibles != null) {
            for (int i = 0; i < lvl.collectibles.length; i++) {
                Level.Collectible coll = lvl.collectibles[i];

                if (coll.respawnTime > 0) continue;

                int dx = player.pixelsX() - coll.x;
                int dy = player.pixelsY() - coll.y;

                if (dx * dx + dy * dy < 64) {
                    switch (coll.type) {
                        case Level.COLLECTIBLE_SHOTGUN:
                            if (!player.weapons[1]) {
                                player.weapons[1] = true;
                                coll.respawnTime = 600;
                            }
                            break;

                        case Level.COLLECTIBLE_SNIPER:
                            if (!player.weapons[2]) {
                                player.weapons[2] = true;
                                coll.respawnTime = 600;
                            }
                            break;

                        case Level.COLLECTIBLE_MEDKIT:
                            if (player.hp < 100) {
                                player.hp += 15;
                                if (player.hp > 100) player.hp = 100;
                                coll.respawnTime = 600;
                            }
                            break;
                        case Level.COLLECTIBLE_GRENADE:
                            if (!player.grenade) {
                                player.grenade = true;
                                coll.respawnTime = 600;
                            }
                            break;
                    }
                }
            }
        }

        player.onGround = prevSpeedY != player.speedY && prevSpeedY > 0;

        if (player.onGround) {
            player.coyoteTime = 60;
        } else {
            player.coyoteTime -= 10;
        }

        // Send move packet
        if (multiplayer != null && (player.speedX != 0 || player.speedY != 0)) {
            multiplayer.sendMove(player);
        }

        if (player.speedX > -0.01f && player.speedX < 0.01f) player.speedX = 0;

        if (!player.onLadder) {
            player.speedX *= 0.89f;
            player.speedY *= 0.98f;
        } else {
            player.speedX = 0;
            player.speedY = 0;
        }
    }

    private void updateKeys(int keyCode, boolean pressed) {
        int action = getGameAction(keyCode);

        if (keyCode == KEY_NUM2 || action == UP) inputClimbUpJump = pressed;

        if (keyCode == KEY_NUM5 || action == FIRE) inputShoot = pressed;
        if (keyCode == KEY_NUM9 || action == GAME_A) inputGrenade = pressed;

        if (keyCode == KEY_NUM8 || action == DOWN) inputClimbDown = pressed;
        if (keyCode == KEY_NUM4 || action == LEFT) inputLeft = pressed;
        if (keyCode == KEY_NUM6 || action == RIGHT) inputRight = pressed;

        if (keyCode == KEY_NUM1) inputRotateLeft = pressed;
        if (keyCode == KEY_NUM3) inputRotateRight = pressed;
    }

    private void resetKeys() {
        inputLeft = false;
        inputRight = false;
        inputRotateLeft = false;
        inputRotateRight = false;
        inputClimbDown = false;
        inputClimbUpJump = false;
        inputShoot = false;
        inputGrenade = false;
    }

    private void changeSetting(int settingIndex) {
        switch (settingIndex) {
            case 0:
                targetFpsChoice = (targetFpsChoice + 1) % 3;
                SETTINGS_CHOICES[0] = "Target FPS: " + FPS_CHOICES[targetFpsChoice] + " FPS";
                break;
            case 1:
                debug = !debug;
                SETTINGS_CHOICES[1] = "Debug stats: " + (debug ? "YES" : "NO");
                break;
            case 2:
                syncAimDirection = !syncAimDirection;
                SETTINGS_CHOICES[2] = "Sync aim with direction: " + (syncAimDirection ? "YES" : "NO");
                break;
        }


        GameSettings saved = shootan.instance.settings;
        saved.targetFpsChoice = targetFpsChoice;
        saved.debug = debug;
        saved.syncAimDirection = syncAimDirection;
        shootan.instance.saveSettings();
    }

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);

        if (player != null && player.isDied && screen == SCREEN_NONE) {
            if (keyCode == KEY_NUM0) {
                if (multiplayer != null) {
                    multiplayer.sendRevive();
                } else {
                    player.isDied = false;
                    player.hp = 100;
                    player.respawn(lvl);
                }

                return;
            }

            if (keyCode == KEY_NUM5 && multiplayer != null) {
                screen = SCREEN_CHAT;
                fromDeathScreen = true;
                return;
            }
        }

        if (screen != SCREEN_NONE) {
            switch (screen) {
                case SCREEN_PAUSE: {
                    if (keyCode == KEY_NUM5 || action == FIRE) {
                        switch (pauseChoice) {
                            case 0: screen = SCREEN_NONE; break;
                            case 1: if (multiplayer != null) screen = SCREEN_CHAT; break;
                            case 2: screen = SCREEN_SETTINGS; break;
                            case 3:
                                shouldClose = true;
                                if (multiplayer != null) multiplayer.close();
                                shootan.instance.display.setCurrent(shootan.instance.mainMenu);
                                break;
                        }

                        return;
                    }

                    if (keyCode == KEY_NUM2 || action == UP) pauseChoice = (pauseChoice - 1) % PAUSED_CHOICES.length;
                    if (keyCode == KEY_NUM8 || action == DOWN) pauseChoice = (pauseChoice + 1) % PAUSED_CHOICES.length;

                    if (keyCode == KEY_NUM0 || keyCode == -7) {
                        pauseChoice = 0;
                        screen = SCREEN_NONE;
                    }

                    if (pauseChoice < 0) pauseChoice = PAUSED_CHOICES.length - 1;

                    break;
                }

                case SCREEN_CHAT: {
                    if (keyCode == KEY_NUM0 || keyCode == -7) {
                        screen = fromDeathScreen ? SCREEN_NONE : SCREEN_PAUSE;
                        fromDeathScreen = false;
                    }

                    if (keyCode == KEY_NUM5 || action == FIRE) {
                        shootan.instance.display.setCurrent(chatBox);
                        return;
                    }
                    break;
                }

                case SCREEN_SETTINGS: {
                    if (keyCode == KEY_NUM0 || keyCode == -7) screen = SCREEN_PAUSE;
                    if (keyCode == KEY_NUM2 || action == UP) settingChoice = (settingChoice - 1) % SETTINGS_CHOICES.length;
                    if (keyCode == KEY_NUM8 || action == DOWN) settingChoice = (settingChoice + 1) % SETTINGS_CHOICES.length;

                    if (settingChoice < 0) settingChoice = SETTINGS_CHOICES.length - 1;

                    if (keyCode == KEY_NUM5 || action == FIRE) changeSetting(settingChoice);

                    break;
                }
            }

            return;
        }

        updateKeys(keyCode, true);

        if (keyCode == KEY_NUM0 || keyCode == -7) {
            screen = SCREEN_PAUSE;
            resetKeys();
            updateLeaderboard();
            return;
        }

        if (player != null) {
            if (keyCode == KEY_STAR) {
                do {
                    player.weapon++;
                    player.weapon %= 3;
                } while (!player.weapons[player.weapon]);

                if (multiplayer != null) {
                    multiplayer.sendChangeWeapon(player.weapon);
                }
            }
        }
    }

    protected void keyReleased(int keyCode) {
        updateKeys(keyCode, false);
    }

    public void commandAction(Command command, Displayable displayable) {
        if (command == chatSend) {
            String msg = filterChatMessage(chatBox.getString());

            if (msg.length() > 0) {
                try {
                    multiplayer.sendChatMessage(msg);
                } catch (IOException ignored) {}
            }
        }

        shootan.instance.display.setCurrent(this);
        chatBox.setString(null);
    }

    private static String filterChatMessage(String text) {
        StringBuffer result = new StringBuffer();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n' || c == '\r') {
                c = ' ';
            }

            if (c >= 32 && c <= 126) {
                result.append(c);
            }
        }

        return result.toString().trim();
    }
}