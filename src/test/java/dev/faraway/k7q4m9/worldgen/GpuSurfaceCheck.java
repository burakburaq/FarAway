package dev.faraway.k7q4m9.worldgen;

import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL43C.*;

/** Exercises the compute shader in a hidden native context, without starting Minecraft. */
public final class GpuSurfaceCheck {
    public static void main(String[] args) throws Exception {
        if(!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException("SDL video initialization failed");
        SDLVideo.SDL_GL_SetAttribute(17,3); SDLVideo.SDL_GL_SetAttribute(18,3); SDLVideo.SDL_GL_SetAttribute(21,1);
        long window=SDLVideo.SDL_CreateWindow("FarAway compute validation",32,32,SDLVideo.SDL_WINDOW_OPENGL|SDLVideo.SDL_WINDOW_HIDDEN);
        if(window==0) throw new IllegalStateException("Hidden GL window creation failed");
        long context=SDLVideo.SDL_GL_CreateContext(window);
        if(context==0) throw new IllegalStateException("GL context creation failed");
        try {
            GL.createCapabilities();
            System.out.println("GPU compute validation: "+glGetString(GL_VERSION)+" / "+glGetString(GL_RENDERER));
            int[] first=sample(-30000000,29999472,12345L),second=sample(-30000000,29999472,12345L),other=sample(-30000000,29999472,54321L);
            if(!java.util.Arrays.equals(first,second)) throw new AssertionError("GPU heights are not deterministic");
            if(java.util.Arrays.equals(first,other)) throw new AssertionError("Seed must affect GPU heights");
            for(int h:first) if(h< -63 || h>320) throw new AssertionError("GPU height outside bounds");
            int[] neighbor=sample(-30000000+32*16,29999472,12345L);
            for(int z=0;z<34;z++) if(first[z*34+32]!=neighbor[z*34]) throw new AssertionError("GPU tile border mismatch");
            System.out.println("GPU compute determinism, seed, height bounds and world-scale borders passed");
        } finally { SDLVideo.SDL_GL_DestroyContext(context); SDLVideo.SDL_DestroyWindow(window); SDLInit.SDL_Quit(); }
    }
    private static int[] sample(int x,int z,long seed) throws Exception {
        var result=java.util.concurrent.CompletableFuture.supplyAsync(() -> GpuSurfaceSampler.sample(x,z,34,34,16,seed,-64,320,63,() -> false));
        long deadline=System.nanoTime()+3_000_000_000L;
        while(!result.isDone() && System.nanoTime()<deadline) { GpuSurfaceSampler.pump(); glFlush(); Thread.sleep(1); }
        var heights=result.get(1,java.util.concurrent.TimeUnit.SECONDS);
        if(heights==null) throw new AssertionError("GPU sampling fell back instead of executing");
        return heights.heights();
    }
}
