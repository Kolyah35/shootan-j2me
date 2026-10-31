public class RemotePlayer {
    public String username;
    public int id;

    public int x = 0;
    public int y = 0;
    public int hp = 100;
    public byte hat;
    public byte weapon;
    public boolean isDied = false;
    public int transform;

    public RemotePlayer(int id, String username, int x, int y, byte hp, byte hat, byte weapon, boolean isDied) {
        this.id = id;
        this.username = username;
        this.x = x;
        this.y = y;
        this.hp = hp;
        this.hat = hat;
        this.weapon = weapon;
        this.isDied = isDied;
    }
}