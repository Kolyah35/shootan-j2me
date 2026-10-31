public class Entity {
    public float x;
    public float y;

    public float speedX;
    public float speedY;

    public int pixelsX() { return (int)(this.x * 8.f); }
    public int pixelsY() { return (int)(this.y * 8.f); }
}
