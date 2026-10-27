package dev.kuro.client.echo.event;

/** Fired around LocalPlayer.sendPosition. The Pre event's x/y/z/onGround/yaw/pitch are what the movement packet carries. */
public class EventMove extends Event {
    private double x, y, z;
    private boolean onGround;
    private double yaw, pitch;

    public EventMove(Event.Stage stage, double x, double y, double z, boolean onGround, double yaw, double pitch) {
        super(stage);
        this.x = x;
        this.y = y;
        this.z = z;
        this.onGround = onGround;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public boolean isOnGround() { return onGround; }
    public double getYaw() { return yaw; }
    public double getPitch() { return pitch; }
    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }
    public void setZ(double z) { this.z = z; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }
    public void setYaw(double yaw) { this.yaw = yaw; }
    public void setPitch(double pitch) { this.pitch = pitch; }

    public static class Pre extends EventMove {
        public Pre(double x, double y, double z, boolean onGround, double yaw, double pitch) {
            super(Event.Stage.PRE, x, y, z, onGround, yaw, pitch);
        }
    }

    public static class Post extends EventMove {
        public Post(double x, double y, double z, boolean onGround, double yaw, double pitch) {
            super(Event.Stage.POST, x, y, z, onGround, yaw, pitch);
        }
    }
}
