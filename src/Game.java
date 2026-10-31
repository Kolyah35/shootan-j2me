import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.lcdui.game.Sprite;

import java.io.*;
import java.util.Enumeration;
import java.util.Random;
import java.util.Vector;

public class Game extends GameCanvas {
    private static Game game;

    private Graphics g;

    boolean inputClimbUp = false;
    boolean inputClimbDown = false;
    boolean inputJump = false;
    boolean inputLeft = false;
    boolean inputRight = false;
    boolean inputRotateLeft = false;
    boolean inputRotateRight = false;

    long ticks = 0;

    public static class Player extends Entity {
        public String username;

        public double angle = 0;

        public int id;
        public byte hat = 0;
        public byte weapon = 0;
        public int hp = 100;
        public boolean onGround = false;
        public boolean onLadder = false;

        public boolean[] weapons = new boolean[3];
        public boolean grenade = false;

        public boolean isDied = false;

        public int coyoteTime = 0;
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

    private Game() {
        super(false);
    }

    public static Game getInstance() {
        if (game == null) {
            game = new Game();
        }

        return game;
    }

    public void start(String playerName, String host, boolean offline) throws IOException {
        g = getGraphics();

        try {
            if (!offline) {
                drawConnectingScreen("Connecting to " + host + "...");
                multiplayer = new Multiplayer(host, playerName);
            } else {
                // offline mode
                connectedServer(Level.createFromRes("/world.dat"));
            }
        } catch (Exception e) {
            // TODO: Error screen
            System.out.println("Error: " + e.getMessage());
        }

        int offX = 0;
        int offY = 0;

        setFullScreenMode(true);

        while (true) {
            if (multiplayer != null) multiplayer.update();

            // draw bg
            g.setColor(0x040432);
            g.fillRect(0, 0, this.getWidth(), this.getHeight());

            if (player != null && lvl != null) {
                timer.advanceTime();

                for (int i = 0; i < timer.ticks; i++) {
                    this.ticks++;

                    if (!player.isDied) {
                        tickPlayer();
                    }

                    Enumeration em = bullets.elements();

                    while (em.hasMoreElements()) {
                        Bullet b = (Bullet)em.nextElement();

                        float prevSpeedX = b.speedX;
                        float prevSpeedY = b.speedY;

                        lvl.moveEntity(b, 0.3f, 0.2f);

                        boolean shouldDelete =
                                b.x < 0 || b.x > Level.LEVEL_SIZE || b.y < 0 || b.y > Level.LEVEL_SIZE || prevSpeedX != b.speedX || prevSpeedY != b.speedY;

                        if (shouldDelete) bullets.removeElement(b);
                    }

                    Enumeration gr = grenades.elements();

                    while (gr.hasMoreElements()) {
                        Grenade grenade = (Grenade)gr.nextElement();

                        grenade.speedY += 0.02f;

                        float prevSpeedX = grenade.speedX;
//                        float prevSpeedY = grenade.speedY;

                        lvl.moveEntity(grenade, 0.5f, 0.5f);

                        if (prevSpeedX != grenade.speedX) grenade.speedX *= -1;
                        if (grenade.speedX > -0.05f && grenade.speedX < 0.05f) grenade.speedX = 0;

                        grenade.speedX *= 0.95f;
                        grenade.speedY *= 0.99f;

                        if (grenade.x < 0 || grenade.x > Level.LEVEL_SIZE || grenade.y < 0 || grenade.y > Level.LEVEL_SIZE) bullets.removeElement(grenade);
                    }

                    offX = player.pixelsX() - getWidth() / 2;
                    offY = player.pixelsY() - getHeight() / 2;

                    if (offX < 0) offX = 0;
                    if (offX > Level.LEVEL_SIZE * 8 - getWidth()) offX = Level.LEVEL_SIZE * 8 - getWidth();

                    if (offY < 0) offY = 0;
                    if (offY > Level.LEVEL_SIZE * 8 - getHeight()) offY = Level.LEVEL_SIZE * 8 - getHeight();
                }

                lvl.render(g, offX, offY, getWidth(), getHeight());

                if (multiplayer != null) {
                    // draw remote players
                    for (int i = 0; i < multiplayer.remotePlayers.size(); i++) {
                        RemotePlayer pl = (RemotePlayer) multiplayer.remotePlayers.elementAt(i);

                        if (pl.x - offX >= 0 && pl.x - offX < getWidth() && pl.y - offY > 0 && pl.y - offY < getHeight()) {
                            g.drawRegion(Resources.spritesheet, 32, 8, 8, 8, 0, pl.x - offX, pl.y - offY, Graphics.TOP | Graphics.LEFT);
                        }
                    }

                    for (int i = 0; i < bullets.size(); i++) {
                        Bullet bullet = (Bullet) bullets.elementAt(i);

                        if (bullet.x - offX >= 0 && bullet.x - offX < getWidth() && bullet.y - offY > 0 && bullet.y - offY < getHeight()) {
                            g.drawRegion(Resources.spritesheet, 0, 20, 8, 6, 0, (int) (bullet.x * 8) - offX, (int) (bullet.y * 8) - offY, Graphics.TOP | Graphics.LEFT);
                        }
                    }
                }

                double cos = Math.cos(player.angle);
                double sin = Math.sin(player.angle);

                int cx = player.pixelsX() - offX + 4;
                int cy = player.pixelsY() - offY + 4;

                int transform = cos < 0 ? Sprite.TRANS_MIRROR : Sprite.TRANS_NONE;

                // draw player
                if (!player.isDied)
                    g.drawRegion(Resources.spritesheet, 32, 8, 8, 8, transform, cx - 4, cy - 4, Graphics.TOP | Graphics.LEFT);

                int wx;

                switch (player.weapon) {
                    case 0:
                        wx = transform == Sprite.TRANS_NONE ? cx : cx - 14;
                        g.drawRegion(Resources.spritesheet, 0, 0, 14, 8, transform, wx, cy - 4, Graphics.TOP | Graphics.LEFT);
                        break;
                    case 1:
                        wx = transform == Sprite.TRANS_NONE ? cx : cx - 24;
                        g.drawRegion(Resources.spritesheet, 20, 0, 24, 8, transform, wx, cy - 4, Graphics.TOP | Graphics.LEFT);
                        break;
                    case 2:
                        wx = transform == Sprite.TRANS_NONE ? cx : cx - 32;
                        g.drawRegion(Resources.spritesheet, 0, 12, 32, 8, transform, wx, cy - 4, Graphics.TOP | Graphics.LEFT);
                        break;
                }

                g.drawRegion(Resources.spritesheet, 54, 32, 5, 5, 0, cx + (int)(20 * cos), cy + (int)(20 * sin), Graphics.HCENTER | Graphics.VCENTER);
            }

            flushGraphics();
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
            if (bullet.id == id) bullets.removeElementAt(i);

            return;
        }
    }

    public void addGrenade(Grenade grenade) {
        if (bullets.contains(grenade)) {
            return;
        }

        bullets.addElement(grenade);
    }

    public void removeGrenade(int id) {
        for (int i = 0; i < grenades.size(); i++) {
            Grenade grenade = (Grenade)grenades.elementAt(i);
            if (grenade.id == id) grenades.removeElementAt(i);

            return;
        }
    }

    public void connectedServer(Level lvl) {
        this.lvl = lvl;
        timer = new Timer(60.f);
    }

    private void tickPlayer() throws IOException {
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

    private void drawConnectingScreen(String text) {
        g.setColor(0);
        g.drawString(text, this.getWidth() / 2, 0, Graphics.TOP|Graphics.HCENTER);
        flushGraphics();
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
            player.weapon++;
            player.weapon %= 3;
        }

        if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) {
            if (multiplayer != null) {
                multiplayer.sendShoot((float) player.angle);
            } else {
                Bullet bullet = new Bullet();
                bullet.x = player.x;
                bullet.y = player.y;
            }
        }
    }

    protected void keyReleased(int keyCode) {
        updateKeys(keyCode, false);
    }
}
