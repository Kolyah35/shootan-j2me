public class Timer {
    private static final long MS_PER_SECOND = 1000L;
    private static final long MAX_MS_PER_UPDATE = 1000L;
    private static final int MAX_TICKS_PER_UPDATE = 100;

    /**
     * Amount of ticks per second
     */
    private final float ticksPerSecond;

    /**
     * Last time updated in milliseconds
     */
    private long lastTime = System.currentTimeMillis();


    /**
     * Framerate of the advanceTime update
     */
    public float fps = 0.0F;

    /**
     * Passed time since last game update
     */
    public float passedTime = 0.0F;

    /**
     * The amount of ticks for the current game update.
     * It's the passed time as an integer
     */
    public int ticks;

    /**
     * The overflow of the current tick, caused by casting the passed time to an integer
     */
    public float partialTicks;

    /**
     * Timer to control the tick speed independently of the framerate
     *
     * @param ticksPerSecond Amount of ticks per second
     */
    public Timer(float ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
    }

    /**
     * This function calculates the amount of ticks required to reach the ticksPerSecond.
     * Call this function in the main render loop of the game
     */
    public void advanceTime() {
        // TODO: fully remove float operations
        long now = System.currentTimeMillis();
        long passedMs = now - this.lastTime;

        if (passedMs <= 0) return;

        // Store nano time of this update
        this.lastTime = now;

        passedMs = Math.min(MAX_MS_PER_UPDATE, passedMs);

        // Calculate fps
        this.fps = (float) (MS_PER_SECOND / passedMs);

        // Calculate passed time and ticks
        this.passedTime += passedMs * this.ticksPerSecond / MS_PER_SECOND;
        this.ticks = (int) this.passedTime;

        // Maximum ticks per update
        this.ticks = Math.min(MAX_TICKS_PER_UPDATE, this.ticks);

        // Calculate the overflow of the current tick
        this.passedTime -= this.ticks;
        this.partialTicks = this.passedTime;
    }
}
