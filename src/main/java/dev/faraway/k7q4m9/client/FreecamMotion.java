package dev.faraway.k7q4m9.client;

/** Frame-rate-independent free camera velocity and movement. */
public final class FreecamMotion {
    private double distance=1, heightDifference=1, vx,vy,vz,scrollRemainder;
    private int multiplierStep=0;
    public double exponent() { return multiplierStep*0.05; }
    public double multiplier() { return Math.pow(2,exponent()); }
    public double speed() { return Math.max(16,distance*Math.pow(2,exponent()-2)); }
    public double verticalSpeed() { return Math.max(8,Math.sqrt(heightDifference))*Math.pow(2,exponent()+2); }
    public void updateHeightDifference(double dy) {
        if(Double.isFinite(dy))heightDifference=Math.abs(dy);
    }
    public void updateDistance(double dx,double dz) {
        double next=Math.hypot(dx,dz);
        if(Double.isFinite(next))distance=Math.max(1,next);
    }
    public void scroll(double steps) {
        if(!Double.isFinite(steps))return;
        scrollRemainder+=steps;
        double whole=scrollRemainder>0?Math.floor(scrollRemainder):Math.ceil(scrollRemainder);
        scrollRemainder-=whole;
        multiplierStep=(int)Math.max(-40,Math.min(40,multiplierStep+whole));
    }
    public double[] advance(double forward,double strafe,double vertical,float yaw,double seconds) {
        double speed=speed();
        double magnitude=Math.hypot(forward,strafe);
        if(magnitude>1) { forward/=magnitude; strafe/=magnitude; }
        double radians=Math.toRadians(yaw),sin=Math.sin(radians),cos=Math.cos(radians);
        double tx=(-strafe*cos-forward*sin)*speed, tz=(forward*cos-strafe*sin)*speed;
        double ty=Math.max(-1,Math.min(1,vertical))*verticalSpeed();
        double dt=Math.max(0,Math.min(0.25,seconds));
        double oldX=vx,oldY=vy,oldZ=vz;
        vx=smooth(vx,tx,dt); vy=smooth(vy,ty,dt); vz=smooth(vz,tz,dt);
        return new double[]{(oldX+vx)*dt/2,(oldY+vy)*dt/2,(oldZ+vz)*dt/2};
    }
    private static double smooth(double current,double target,double dt) {
        // Approximately 95% of a step in 1 second; release reaches 95% in 0.15 s.
        double tau=target==0 || current*target<0?0.15/3:1.0/3;
        double result=target+(current-target)*Math.exp(-dt/tau);
        return target==0 && Math.abs(result)<1e-5?0:result;
    }
}
