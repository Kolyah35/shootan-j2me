import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.lcdui.game.Sprite;

import java.io.*;
import java.util.Enumeration;
import java.util.Random;
import java.util.Vector;

public class Game extends GameCanvas implements Runnable {
    private static final int TARGET_FPS = 30;
    private static final long FRAME_TIME = 1000 / TARGET_FPS;

    private Graphics g;

    boolean inputClimbUp = false;
    boolean inputClimbDown = false;
    boolean inputJump = false;
    boolean inputLeft = false;
    boolean inputRight = false;
    boolean inputRotateLeft = false;
    boolean inputRotateRight = false;

    long ticks = 0;

//    public static final int[] MAX_BULLETS = {20, 12, 4};
    public static final float[] BULLET_SPEED = {0.6f, 0.7f, 0.8f};
    public static final int[] RELOAD_TIME = {15, 80, 105};

    public static final int GRENADE_BURST_RADIUS = 3;
    public static final int GRENADE_DAMAGE = 50;
    public static final float GRENADE_SPEED = 0.7f;
    public static final int GRENADE_LIFETIME = 125;

    public static class Player extends Entity {
        public double angle = 0;

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
    };

    public Multiplayer multiplayer = null;
    public Level lvl = null;
    public Player player = new Player();
    public Timer timer = null;

    public static class Bullet extends Entity {
        public int id;
        public float angle;
        public int ownerId;
        public int weaponId;
    };

    public Vector bullets = new Vector();

    public static class Grenade extends Entity {
        public int id;
        public int lifeTime;
    }

    private final Vector grenades = new Vector();

    // if not zero then round end and leaderboard screen pops out
    public int winnerId = 0;

    private Image darkOverlay;

    public Game(String playerName, String host, boolean offline) throws IOException {
        super(false);

        if (!offline) {
            multiplayer = new Multiplayer(this, host, playerName);
        } else {
            // offline mode
            connectedServer(Level.createFromRes("/world.dat"));
        }

        // create death overlay
        int[] pixels = new int[getWidth() * getHeight()];

        int color = 0x80000000;

        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = color;
        }

        darkOverlay = Image.createRGBImage(pixels, getWidth(), getHeight(), true);
    }

    public void run() {
        g = getGraphics();

        int offX = 0;
        int offY = 0;

        setFullScreenMode(true);

        while (true) {
            long frameStart = System.currentTimeMillis();

            if (multiplayer != null) multiplayer.update();

            // draw bg
            g.setColor(0x040432);
            g.fillRect(0, 0, this.getWidth(), this.getHeight());

            if (player != null && lvl != null) {
                timer.advanceTime();

                for (int tick = 0; tick < timer.ticks; tick++) {
                    if (!player.isDied) {
                        tickPlayer();
                    }

                    // update bullets
                    for (int i = bullets.size() - 1; i >= 0; i--) {
                        Bullet b = (Bullet)bullets.elementAt(i);

                        float prevSpeedX = b.speedX;
                        float prevSpeedY = b.speedY;

                        lvl.moveEntity(b, 0.3f, 0.2f);

                        boolean shouldDelete =
                                b.x < 0 || b.x > Level.LEVEL_SIZE || b.y < 0 || b.y > Level.LEVEL_SIZE || prevSpeedX != b.speedX || prevSpeedY != b.speedY;

                        if (shouldDelete) bullets.removeElement(b);
                    }

                    // update grenades
                    for (int i = grenades.size() - 1; i >= 0; i--) {
                        Grenade grenade = (Grenade)grenades.elementAt(i);

                        grenade.speedY += 0.02f;

                        float prevSpeedX = grenade.speedX;
                        // float prevSpeedY = grenade.speedY;

                        lvl.moveEntity(grenade, 0.5f, 0.5f);

                        if (prevSpeedX != grenade.speedX) grenade.speedX *= -1;
                        if (grenade.speedX > -0.05f && grenade.speedX < 0.05f) grenade.speedX = 0;

                        grenade.speedX *= 0.95f;
                        grenade.speedY *= 0.99f;

                        if (grenade.x < 0 || grenade.x > Level.LEVEL_SIZE || grenade.y < 0 || grenade.y > Level.LEVEL_SIZE) grenades.removeElement(grenade);
                    }

                    for (int i = 0; i < player.reloadTimes.length; i++) {
                        if (player.reloadTimes[i] > 0) {
                            player.reloadTimes[i]--;
                        }
                    }

                    // offset shit
                    offX = player.pixelsX() - getWidth() / 2;
                    offY = player.pixelsY() - getHeight() / 2;

                    if (offX < 0) offX = 0;
                    if (offX > Level.LEVEL_SIZE * 8 - getWidth()) offX = Level.LEVEL_SIZE * 8 - getWidth();

                    if (offY < 0) offY = 0;
                    if (offY > Level.LEVEL_SIZE * 8 - getHeight()) offY = Level.LEVEL_SIZE * 8 - getHeight();
                }

                // TODO: render the whole level in Image and draw visible region and figure out would it be faster or not
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

                            Resources.tinyFont.draw(g, pl.username,
                                    x + 4 - Resources.tinyFont.stringWidth(pl.username) / 2, y - height);
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
                }

                // TODO: precalculated cos and sin table
                double cos = Math.cos(player.angle);
                double sin = Math.sin(player.angle);

                int cx = player.pixelsX() - offX + 4;
                int cy = player.pixelsY() - offY + 4;

                int transform = cos < 0 ? Sprite.TRANS_MIRROR : Sprite.TRANS_NONE;

                // draw player and other things
                if (!player.isDied) {
                    g.drawRegion(Resources.spritesheet, 32, 8, 8, 8, transform, cx - 4, cy - 4, Graphics.TOP | Graphics.LEFT);

                    if (player.hat != 0) {
                        drawHat(player.pixelsX(), player.pixelsY(), transform, player.hat);
                    }

                    drawWeapon(player.weapon, transform, cx, cy - 4, false);

                    g.drawRegion(Resources.spritesheet, 54, 32, 5, 5, 0, cx + (int)(20 * cos), cy + (int)(20 * sin), Graphics.HCENTER | Graphics.VCENTER);

                    if (player.reloadTimes[player.weapon] > 0) {
                        Resources.defaultFont.draw(g, "RELOADING... ", 0, 12);
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
                } else {
                    g.drawImage(darkOverlay, 0, 0, Graphics.TOP | Graphics.LEFT);
                    String deadText = "You died!\nScore: " + player.score + "\nPress 0 to revive.";
                    Resources.defaultFont.draw(g, deadText,
                            (getWidth() - Resources.defaultFont.stringWidth(deadText)) / 2,
                            (getHeight() - Resources.defaultFont.stringHeight(deadText)) / 2);
                }
            }

            flushGraphics();

            long elapsed = System.currentTimeMillis() - frameStart;
            long sleep = FRAME_TIME - elapsed;

            if (sleep > 0) {
                try { Thread.sleep(sleep); }
                catch (InterruptedException e) { break; }
            }
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

    public int getWeaponWidth(byte weapon) {
        switch (weapon) {
            case 0: return 14;
            case 1: return 24;
            case 2: return 32;
        }

        return 0;
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

    public void drawHat(int playerX, int playerY, int transform, byte hat) {
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

    private void tickPlayer() {
        // scary code
        if (inputClimbUp || inputClimbDown) {
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
                player.speedY = inputClimbUp ? -0.2f : 0.2f;
            }
//            else if (player.onLadder) {
//                player.onLadder = false;
//                player.speedY = inputClimbUp ? -0.3f : 0.3f;
//            }
        }

        if (inputJump) {
            if (player.coyoteTime > 0) {
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

        if (inputRotateRight || inputRotateLeft) {
            if (inputRotateRight) player.angle += 0.06f;
            if (inputRotateLeft) player.angle -= 0.06f;

            if (multiplayer != null) {
                multiplayer.sendAngle((float) Math.toDegrees(player.angle));
            }
        }

        if (!player.onLadder) {
            player.speedY += 0.02f;
        }

        float prevSpeedY = player.speedY;

        lvl.moveEntity(player, 1.f, 1.f);

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
        if (keyCode == KEY_NUM2) inputClimbUp = pressed;
        else if (getGameAction(keyCode) == UP) inputJump = pressed;

        if (keyCode == KEY_NUM8) inputClimbDown = pressed;
        if (keyCode == KEY_NUM4 || getGameAction(keyCode) == LEFT) inputLeft = pressed;
        if (keyCode == KEY_NUM6 || getGameAction(keyCode) == RIGHT) inputRight = pressed;

        if (keyCode == KEY_NUM1) inputRotateLeft = pressed;
        if (keyCode == KEY_NUM3) inputRotateRight = pressed;
    }

    protected void keyPressed(int keyCode) {
        updateKeys(keyCode, true);

        if (keyCode == KEY_STAR) {
            do {
                player.weapon++;
                player.weapon %= 3;
            } while (!player.weapons[player.weapon]);

            if (multiplayer != null) {
                multiplayer.sendChangeWeapon(player.weapon);
            }
        }

        if ((keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) && !player.isDied && player.reloadTimes[player.weapon] == 0) {
            if (multiplayer != null) {
                multiplayer.sendShoot((float) player.angle);
            } else {
                Bullet bullet = new Bullet();
                bullet.x = player.x;
                bullet.y = player.y;
                bullet.angle = (float) player.angle;
                bullet.speedY = (float) Math.sin(player.angle) * 0.6f;
                bullet.speedX = (float) Math.cos(player.angle) * 0.6f;
                bullet.weaponId = player.weapon;
                bullet.id = bullets.size();
                bullet.ownerId = player.id;

                addBullet(bullet);
            }

            player.reloadTimes[player.weapon] = RELOAD_TIME[player.weapon];
        }

        if (keyCode == KEY_NUM9 && !player.isDied && player.grenade) {
            if (multiplayer != null) {
                multiplayer.sendThrowGrenade((float) player.angle);
            } else {
                Grenade grenade = new Grenade();
                grenade.x = player.x + 0.5f;
                grenade.y = player.y + 0.5f;
                grenade.speedY = (float) Math.sin(player.angle) * 0.7f;
                grenade.speedX = (float) Math.cos(player.angle) * 0.7f;
                grenade.id = grenades.size();
                grenade.lifeTime = 125;

                addGrenade(grenade);
            }

            player.grenade = false;
        }

        if (keyCode == KEY_NUM0 && player.isDied) {
            if (multiplayer != null) {
                multiplayer.sendRevive();
            } else {
                player.isDied = false;
            }
        }
    }

    protected void keyReleased(int keyCode) {
        updateKeys(keyCode, false);
    }
}
