package dev.faraway.k7q4m9.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Detached camera modes: DebugCam freezes LOD selection; FreeCam follows the flying camera. */
public final class Freecam {
    private static boolean active,debugCam;
    private static Vec3 position,anchor,playerAnchor;
    private static float yaw,pitch,anchorFov;
    private static int anchorHeight,anchorWidth;
    private static long lastFrame;
    private static FreecamMotion motion;
    private static Matrix4f anchorView,anchorProjection,liveView,liveProjection;
    private static FreecamFrustum.Shape frustum;
    private static FreecamFrustum.Geometry frustumGeometry;
    private Freecam() {}
    public static boolean active() { return active; }
    public static boolean debugCamActive() { return active && debugCam; }
    public static boolean freecamActive() { return active && !debugCam; }
    public static void disable() { active=false; debugCam=false; motion=null; anchorView=null; anchorProjection=null; frustum=null; frustumGeometry=null; }
    public static boolean toggle() { return toggleMode(true); }
    public static boolean toggleFreecam() { return toggleMode(false); }
    private static boolean toggleMode(boolean debug) {
        if(active && debugCam==debug) { disable(); return false; }
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || FarAwayClient.benchmarkLocked()) return false;
        if(active) disable();
        debugCam=debug;
        anchorView=liveView==null?null:new Matrix4f(liveView);
        anchorProjection=liveProjection==null?null:new Matrix4f(liveProjection);
        var camera=mc.gameRenderer.mainCamera(); position=camera.position(); anchor=position; playerAnchor=mc.player.position();
        yaw=camera.yRot(); pitch=camera.xRot(); anchorFov=camera.getFov(); anchorHeight=mc.getWindow().getHeight(); anchorWidth=mc.getWindow().getWidth();
        var f=camera.forwardVector(); var l=camera.leftVector(); var u=camera.upVector();
        if(debug) { frustum=FreecamFrustum.create(anchor,new Vec3(f.x(),f.y(),f.z()),new Vec3(-l.x(),-l.y(),-l.z()),new Vec3(u.x(),u.y(),u.z()),anchorFov,(double)mc.getWindow().getWidth()/anchorHeight,mc.level.getSeaLevel(),-30_000_000,30_000_000);
        frustumGeometry=FreecamFrustum.geometry(frustum); }
        motion=new FreecamMotion(); motion.updateHeightDifference(position.y-mc.level.getSeaLevel()); lastFrame=System.nanoTime(); active=true; return true;
    }
    public static void captureView(net.minecraft.client.renderer.state.level.CameraRenderState state) {
        if(liveView==null) { liveView=new Matrix4f(); liveProjection=new Matrix4f(); }
        liveView.set(state.viewRotationMatrix); liveProjection.set(state.projectionMatrix);
        if(debugCamActive() && anchorView==null) { anchorView=new Matrix4f(liveView); anchorProjection=new Matrix4f(liveProjection); }
    }
    public static Matrix4f viewOr(Matrix4f fallback) { return debugCamActive() && anchorView!=null?anchorView:fallback; }
    public static Matrix4f projectionOr(Matrix4f fallback) { return debugCamActive() && anchorProjection!=null?anchorProjection:fallback; }
    public static Vec3 anchorOr(Vec3 fallback) { return debugCamActive()?anchor:fallback; }
    public static BlockPos planPosition(BlockPos fallback) { return active?BlockPos.containing(debugCam?anchor:position):fallback; }
    public static float planFov(float fallback) { return debugCamActive()?anchorFov:fallback; }
    public static int planHeight(int fallback) { return debugCamActive()?anchorHeight:fallback; }
    public static dev.faraway.k7q4m9.streaming.ScreenSpaceLod lodProjection() {
        var mc=Minecraft.getInstance();var camera=mc.gameRenderer.mainCamera();
        if(mc.level==null || liveProjection==null)return null;
        var pos=anchorOr(camera.position());
        var view=debugCamActive()?viewOr(liveView):new Matrix4f().rotation(camera.rotation()).invert();
        return new dev.faraway.k7q4m9.streaming.ScreenSpaceLod(new Matrix4f(projectionOr(liveProjection)).mul(view),pos.x,pos.y,pos.z,
            mc.level.getSeaLevel(),debugCamActive()?anchorWidth:mc.getWindow().getWidth(),planHeight(mc.getWindow().getHeight()));
    }
    public static void turn(double dx,double dy) { yaw+=(float)(dx*0.15); pitch=(float)Math.max(-90,Math.min(90,pitch+dy*0.15)); }
    public static void scroll(double delta) { if(active) motion.scroll(delta); }
    public static Vec3 position() { return position; }
    public static double speedExponent() { return motion==null?0:motion.exponent(); }
    public static double multiplier() { return motion==null?1:motion.multiplier(); }
    public static double speed() { return motion==null?0:motion.speed(); }
    public static FreecamFrustum.Shape frustum() { return frustum; }
    public static FreecamFrustum.Geometry frustumGeometry() { return frustumGeometry; }
    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }
    public static void tick() {
        var level=Minecraft.getInstance().level;
        if(active && motion!=null && level!=null) {
            motion.updateDistance(position.x-anchor.x,position.z-anchor.z);
            motion.updateHeightDifference(position.y-level.getSeaLevel());
        }
    }
    public static void frame() {
        if(!active) return;
        var mc=Minecraft.getInstance(); if(mc.level==null || mc.player==null) { disable(); return; }
        mc.player.setPos(playerAnchor); mc.player.setDeltaMovement(Vec3.ZERO);
        long now=System.nanoTime(); double dt=(now-lastFrame)/1e9; lastFrame=now;
        var o=mc.options; boolean canMove=mc.gui.screen()==null;
        double forward=canMove?((o.keyUp.isDown()?1:0)-(o.keyDown.isDown()?1:0)):0;
        double strafe=canMove?((o.keyRight.isDown()?1:0)-(o.keyLeft.isDown()?1:0)):0;
        double vertical=canMove?((o.keyJump.isDown()?1:0)-(o.keyShift.isDown()?1:0)):0;
        double[] move=motion.advance(forward,strafe,vertical,yaw,dt);
        position=position.add(move[0],move[1],move[2]);
    }
}
