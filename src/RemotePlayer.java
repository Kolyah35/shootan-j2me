public class RemotePlayer implements Scorable {
    public String username;
    public int id;

    public int x = 0;
    public int y = 0;
    public int hp = 100;
    public byte hat;
    public byte weapon;
    public boolean isDied = false;
    public int transform;
    public int score;

    public int usernameWidth = 0;

    public RemotePlayer(int id, String username, int x, int y, byte hp, byte hat, byte weapon, boolean isDied) {
        this.id = id;
        this.username = username;
        this.x = x;
        this.y = y;
        this.hp = hp;
        this.hat = hat;
        this.weapon = weapon;
        this.isDied = isDied;
        this.usernameWidth = Resources.tinyFont.stringWidth(username);
    }

    public int getScore() { return this.score; }
    public String getUsername() { return this.username; }
}