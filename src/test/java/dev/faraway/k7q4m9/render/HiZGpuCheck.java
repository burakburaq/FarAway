package dev.faraway.k7q4m9.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.backend.opengl.*;
import dev.faraway.k7q4m9.streaming.ByteBudget;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL43C.*;

/** Explicit live-context check. Readback here validates GPU output; production never reads visibility. */
public final class HiZGpuCheck {
    private static Class<?> type;
    public static void main(String[] args)throws Exception {
        if(!org.lwjgl.sdl.SDLInit.SDL_Init(org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO))throw new IllegalStateException("SDL video unavailable");
        var config=Files.createTempDirectory("faraway-quad-check-");
        var loader=net.fabricmc.loader.api.FabricLoader.getInstance();var configField=loader.getClass().getDeclaredField("configDir");configField.setAccessible(true);configField.set(loader,config);
        var backend=new GlBackend();
        try {
            backend.loadLibrary();RenderSystem.initRenderThread();
            var device=backend.createDevice(new com.mojang.renderpearl.api.device.GpuDebugOptions(3,true,true,false));RenderSystem.initRenderer(device);
            checkVanillaSeam();
            checkFrustumOutline();
            checkLeafBackface(config.resolve("faraway-lod.properties"));
            var report=Files.createTempFile("faraway-hiz-", ".txt");
            try{run(report);System.out.println(Files.readString(report));}finally{Files.deleteIfExists(report);RenderSystem.shutdownRenderer();}
        }finally{backend.unloadLibrary();org.lwjgl.sdl.SDLInit.SDL_Quit();Files.deleteIfExists(config);}
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception {
        var m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }
    private static void checkQuadPipelines()throws Exception {
        var renderer=Class.forName("dev.faraway.k7q4m9.render.PreviewRenderer");
        var formatField=renderer.getDeclaredField("QUAD_FORMAT");formatField.setAccessible(true);
        var format=(com.mojang.renderpearl.api.vertex.VertexFormat)formatField.get(null);
        if(format.getVertexSize()!=40 || format.getStepRate()!=1)throw new AssertionError("Quad records must advance once per instance, with a 40-byte stride");
        try(var source=new com.mojang.renderpearl.api.pipeline.ShaderSource() {
            final Map<net.minecraft.resources.Identifier,CachedIncludeSource> includes=new HashMap<>();
            private String read(String path) {
                try(var in=HiZGpuCheck.class.getClassLoader().getResourceAsStream(path)) {
                    if(in==null)throw new IllegalArgumentException("Missing shader "+path);
                    return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            }
            public String getShader(net.minecraft.resources.Identifier id,com.mojang.renderpearl.api.pipeline.ShaderType type) {return read("assets/"+id.getNamespace()+"/shaders/"+id.getPath()+(type==com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX?".vsh":".fsh"));}
            public CachedIncludeSource getInclude(net.minecraft.resources.Identifier id) {return includes.computeIfAbsent(id,k->CachedIncludeSource.create(k,read("assets/"+k.getNamespace()+"/shaders/include/"+k.getPath())));}
            public void close(){includes.values().forEach(CachedIncludeSource::close);}
        }) {
            for(String name:new String[]{"QUADS","QUADS_NO_CULL","QUADS_WIRE","QUADS_WIRE_NO_CULL","QUADS_PAINT","QUADS_PAINT_NO_CULL","VANILLA_SEAM","VANILLA_SEAM_CULL","FRUSTUM_EDGES_OVERLAY","FRUSTUM_ARROW"}) {
                var field=renderer.getDeclaredField(name);field.setAccessible(true);
                var pipeline=(com.mojang.renderpearl.api.pipeline.RenderPipeline)field.get(null);
                var pending=RenderSystem.getDevice().compilePipeline(pipeline,source,Runnable::run).join();
                try(var compiled=pending.finishCompile()) {if(compiled==null)throw new AssertionError("Quad pipeline failed: "+name);if(name.equals("QUADS"))checkQuadDraw(compiled);}
            }
        }
    }
    private static void checkLeafBackface(Path config) throws Exception {
        var settings=dev.faraway.k7q4m9.client.LodSettings.class;
        var current=settings.getDeclaredField("current");current.setAccessible(true);
        var load=settings.getDeclaredMethod("load");load.setAccessible(true);
        var previous=current.get(null);
        var arena=new GeometryArena(new ByteBudget(4*1024*1024));
        var constructor=dev.faraway.k7q4m9.mesh.TileMesh.class.getDeclaredConstructor(
            dev.faraway.k7q4m9.terrain.TileKey.class,byte[].class,byte[].class,byte[].class,int.class,int.class);
        constructor.setAccessible(true);
        var vertices=ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        for(int c:new int[]{0,1,2,2,1,3}) {
            vertices.putFloat(c&1).putFloat(c>>1).putFloat(16)
                .put((byte)255).put((byte)255).put((byte)255).put((byte)8);
        }
        var mesh=(dev.faraway.k7q4m9.mesh.TileMesh)constructor.newInstance(
            new dev.faraway.k7q4m9.terrain.TileKey(0,0,2),vertices.array(),new byte[0],new byte[0],0,1);
        try(var regular=arena.upload(mesh,false);var packed=arena.uploadQuads(mesh.packQuads(()->false))) {
            regular.page.visible.add(regular);
            packed.page.visible.add(packed);
            for(boolean cull:new boolean[]{true,false}) {
                Files.writeString(config,"opaqueLeaves=true\ngpuBackfaceCulling="+cull+"\n");
                current.set(null,load.invoke(null));
                regular.page.prepare(0,0,0);
                packed.page.prepare(0,0,0);
                long expected=cull?0:2;
                if(regular.selectedTriangles!=expected || packed.selectedTriangles!=expected)
                    throw new AssertionError("Opaque leaf submission must follow GPU Backface Culling, enabled="+cull);
            }
        } finally {
            current.set(null,previous);
            Files.deleteIfExists(config);
            arena.beginFrame();
        }
    }

    private static void checkFrustumOutline() throws Exception {
        var mesh=new FrustumOutlineMesh(0,0,65);
        mesh.add(new dev.faraway.k7q4m9.terrain.TileKey(0,0,1),null,64);
        mesh.add(new dev.faraway.k7q4m9.terrain.TileKey(1,0,1),null,67);
        var data=mesh.finish().order(ByteOrder.nativeOrder());
        if(data.remaining()!=20*16) throw new AssertionError("Tiles should connect both shared corners at different heights");
        var uploader=Class.forName("dev.faraway.k7q4m9.render.PreviewRenderer$GpuTile")
            .getDeclaredMethod("upload",ByteBuffer.class,int.class);
        uploader.setAccessible(true);
        try(var buffer=(GpuBuffer)uploader.invoke(null,data,GpuBuffer.USAGE_VERTEX)) {
            if(data.remaining()/16!=20) throw new AssertionError("Uploading an outline must preserve its vertex count for drawing");
        }
        for(int at=0;at<data.remaining();at+=16) {
            if(data.getFloat(at+4)<65) throw new AssertionError("No outline vertex may drop below world sea level");
        }
        for(int at=16*16;at<data.remaining();at+=32) {
            if(data.getFloat(at)!=32 || data.getFloat(at+16)!=32 || data.getFloat(at+8)!=data.getFloat(at+24)
                || data.getFloat(at+4)!=65 || data.getFloat(at+20)!=67)
                throw new AssertionError("Frustum corner joins must be vertical and meet both actual edges");
        }
    }

    private static void checkVanillaSeam() {
        var east=net.minecraft.core.Direction.EAST;
        var full=new VanillaSeamMesh.Face(net.minecraft.world.phys.shapes.Shapes.block(),0x03ffffff);
        var air=new VanillaSeamMesh.Face(net.minecraft.world.phys.shapes.Shapes.empty(),0);
        var unedited=new VanillaSeamMesh(List.of());
        unedited.addColumn(0,3,east,64,64,60,false,0x01719850,-1,y -> full);
        if(unedited.data().hasRemaining()) throw new AssertionError("Matching solid columns must share no exposed face");

        var edited=new VanillaSeamMesh(List.of());
        edited.addColumn(0,3,east,64,64,60,false,0x01719850,-1,y -> y==62?air:full);
        var vertices=edited.data().order(ByteOrder.nativeOrder());
        if(vertices.remaining()!=96 || vertices.getFloat(0)!=0
            || vertices.getFloat(4)!=62 || vertices.getFloat(20)!=63)
            throw new AssertionError("Removing a border block below the top must expose precisely that LOD face");

        var half=new VanillaSeamMesh.Face(net.minecraft.world.phys.shapes.Shapes.box(0,0,0,1,.5,1),0x03ffffff);
        var slab=new VanillaSeamMesh(List.of());
        slab.addColumn(0,3,east,64,64,60,false,0x01719850,-1,y -> y==63?half:full);
        var slabVertices=slab.data().order(ByteOrder.nativeOrder());
        if(slabVertices.remaining()!=96 || slabVertices.getFloat(4)!=63.5 || slabVertices.getFloat(20)!=64)
            throw new AssertionError("The shared face must respect partial vanilla block geometry");
    }

    private static void checkQuadDraw(com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline pipeline)throws Exception {
        var arena=new GeometryArena(new ByteBudget(4*1024*1024));var hiz=new HiZ();
        var target=new TextureTarget("Quad draw check",32,16,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
        var white=new TextureTarget("Quad light check",16,16,GpuFormat.RGBA8_UNORM,null);
        var atlas=new TextureTarget("Quad texture check",16,16,GpuFormat.RGBA8_UNORM,null);
        var buffers=new ArrayList<GpuBuffer>();var slots=new ArrayList<GeometryArena.Slot>();
        var sampler=RenderSystem.getDevice().createSampler(com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,com.mojang.renderpearl.api.textures.FilterMode.NEAREST,com.mojang.renderpearl.api.textures.FilterMode.NEAREST,1,java.util.OptionalDouble.empty());
        try {
            var constructor=dev.faraway.k7q4m9.mesh.TileMesh.class.getDeclaredConstructor(dev.faraway.k7q4m9.terrain.TileKey.class,byte[].class,byte[].class,byte[].class,int.class,int.class);constructor.setAccessible(true);
            for(int i=0;i<2;i++) {
                var data=ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
                for(int c:new int[]{0,1,2,2,1,3})data.putFloat((i==0?-.9f:.1f)+((c&1)==0?0:.8f)).putFloat((c&2)==0?-.8f:.8f).putFloat(16).putInt(i==0?0x010000ff:0x0000ff00);
                var mesh=(dev.faraway.k7q4m9.mesh.TileMesh)constructor.newInstance(new dev.faraway.k7q4m9.terrain.TileKey(0,0,2),data.array(),new byte[0],new byte[0],-1,1);
                mesh=mesh.packQuads(()->false).optimize(true,()->false);
                if(mesh.quadCount()!=1 || mesh.triangleCount()!=2 || mesh.vertexCount()!=0)throw new AssertionError("Packed payload lost its triangles after optimize");
                var slot=arena.uploadQuads(mesh);slots.add(slot);slot.page.visible.add(slot);
            }
            if(slots.get(0).page!=slots.get(1).page || slots.get(1).vertex==0)throw new AssertionError("Quad slots must share a page with separate base instances");
            var dynamic=MemoryUtil.memCalloc(160).order(ByteOrder.nativeOrder());var projection=MemoryUtil.memCalloc(64).order(ByteOrder.nativeOrder());
            try {
                for(int i=0;i<4;i++){dynamic.putFloat(i*20,1);projection.putFloat(i*20,1);dynamic.putFloat(128+i*4,1);}
                projection.putFloat(40,1f/32);
                buffers.add(RenderSystem.getDevice().createBuffer(()->"Quad check transforms",GpuBuffer.USAGE_UNIFORM,dynamic));
                buffers.add(RenderSystem.getDevice().createBuffer(()->"Quad check projection",GpuBuffer.USAGE_UNIFORM,projection));
                dynamic.putFloat(64,1);
                buffers.add(RenderSystem.getDevice().createBuffer(()->"Quad check randomized textures",GpuBuffer.USAGE_UNIFORM,dynamic));
            }finally{MemoryUtil.memFree(dynamic);MemoryUtil.memFree(projection);}
            var coverage=MemoryUtil.memCalloc(VanillaCoverage.BYTES).order(ByteOrder.nativeOrder());
            var materials=MemoryUtil.memCalloc(dev.faraway.k7q4m9.mesh.TextureMaterial.MATERIAL_UNIFORM_BYTES).order(ByteOrder.nativeOrder());
            var texture=MemoryUtil.memAlloc(16*16*4);
            try {
                // Material 0 uses a single mirrored variant on the south face.
                materials.putFloat(8,1).putFloat(12,1);
                int header=254*16;
                materials.putInt(header+4,1).putInt(header+8,1).putInt(header+12,1<<5);
                materials.putInt(header*2,1<<(5*3)).putInt(header*2+4,1);
                buffers.add(RenderSystem.getDevice().createBuffer(()->"Quad check coverage",GpuBuffer.USAGE_UNIFORM,coverage));
                buffers.add(RenderSystem.getDevice().createBuffer(()->"Quad check materials",GpuBuffer.USAGE_UNIFORM,materials));
                coverage.putInt(0,-1).putInt(16,2).putInt(32,1);
                buffers.add(RenderSystem.getDevice().createBuffer(()->"Quad check boundary coverage",GpuBuffer.USAGE_UNIFORM,coverage));
                coverage.putInt(16+VanillaCoverage.WORDS*4,1);
                buffers.add(RenderSystem.getDevice().createBuffer(()->"Quad check repaired boundary",GpuBuffer.USAGE_UNIFORM,coverage));
                for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                    byte color=(byte)(x<8?0:255);
                    texture.put(color).put(color).put(color).put((byte)255);
                }
                texture.flip();
                var encoder=RenderSystem.getDevice().createCommandEncoder();
                encoder.clearColorTexture(white.getColorTexture(),new org.joml.Vector4f(1));
                encoder.submit();
                int previous=glGetInteger(GL_TEXTURE_BINDING_2D);
                try {
                    glBindTexture(GL_TEXTURE_2D,((GlTexture)atlas.getColorTexture()).glId());
                    glTexSubImage2D(GL_TEXTURE_2D,0,0,0,16,16,GL_RGBA,GL_UNSIGNED_BYTE,texture);
                } finally {
                    glBindTexture(GL_TEXTURE_2D,previous);
                }
            } finally {MemoryUtil.memFree(coverage);MemoryUtil.memFree(materials);MemoryUtil.memFree(texture);}

            var pixels=MemoryUtil.memAlloc(32*16*4);
            try {
                for(int mode=0;mode<4;mode++) {
                    boolean randomized=mode!=0, covered=mode>=2, repaired=mode==3;
                    var encoder=RenderSystem.getDevice().createCommandEncoder();
                    try(var pass=encoder.createRenderPass(()->"Quad batching correctness",target.getColorTextureView(),java.util.Optional.of(new org.joml.Vector4f(0)),target.getDepthTextureView(),java.util.OptionalDouble.of(0))) {
                        pass.setPipeline(pipeline);
                        pass.setUniform("DynamicTransforms",buffers.get(randomized?2:0));
                        pass.setUniform("Projection",buffers.get(1));
                        pass.setUniform("FarAwayCoverage",buffers.get(repaired?6:covered?5:3));
                        pass.setUniform("FarAwayMaterials",buffers.get(4));
                        pass.setUniform("Sampler0",atlas.getColorTextureView(),sampler);
                        pass.setUniform("Sampler2",white.getColorTextureView(),sampler);
                        if(slots.get(0).page.draw(pass,0,0,20,hiz)!=1)throw new AssertionError("Quad page must submit in one API call");
                    }
                    encoder.submit();
                    int previous=glGetInteger(GL_TEXTURE_BINDING_2D);
                    try {
                        glBindTexture(GL_TEXTURE_2D,((GlTexture)target.getColorTexture()).glId());
                        glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                    } finally {
                        glBindTexture(GL_TEXTURE_2D,previous);
                    }
                    int left=(8*32+4)*4,right=(8*32+27)*4;
                    if(Byte.toUnsignedInt(pixels.get(left))!=(randomized&&!repaired?255:0) || Byte.toUnsignedInt(pixels.get(left+3))!=(repaired?0:255))
                        throw new AssertionError("Randomized Textures must mirror the textured quad only when enabled. enabled="+randomized+" red="+Byte.toUnsignedInt(pixels.get(left))+" alpha="+Byte.toUnsignedInt(pixels.get(left+3)));
                    if(Byte.toUnsignedInt(pixels.get(right))!=0 || Byte.toUnsignedInt(pixels.get(right+1))!=(covered?0:255))
                        throw new AssertionError("Only the wall whose owning chunk is covered should be discarded");
                }
                arena.beginFrame();
            }finally{MemoryUtil.memFree(pixels);}
        }finally{slots.forEach(GeometryArena.Slot::close);buffers.forEach(GpuBuffer::close);sampler.close();target.destroyBuffers();white.destroyBuffers();atlas.destroyBuffers();hiz.close();}

    }
    public static void run(Path report)throws Exception {
        type=Class.forName("dev.faraway.k7q4m9.render.HiZ",true,RenderSystem.class.getClassLoader());
        var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object hiz=ctor.newInstance();
        var paintEnabled=type.getDeclaredField("paintEnabled");paintEnabled.setAccessible(true);paintEnabled.set(hiz,true);
        var target=new TextureTarget("Hi-Z correctness check",63,35,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
        int old=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        GpuBuffer input=null;
        try {
            var encoder=RenderSystem.getDevice().createCommandEncoder();encoder.clearDepthTexture(target.getDepthTexture(),.5);encoder.submit();
            var zeroToOne=RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            var camera=new CameraRenderState();camera.viewRotationMatrix.identity();camera.projectionMatrix.setPerspective((float)Math.toRadians(70),63f/35,90_000_000f,.05f,zeroToOne);
            checkQuadPipelines();
            int cases=0;
            for(var origin:List.of(Vec3.ZERO,new Vec3(29_999_900,100,-29_999_900))) {
                camera.pos=origin;
                var boxes=List.of(new AABB(-.1,-.1,-100,.1,.1,-90),new AABB(-.001,-.001,-.08,.001,.001,-.06),new AABB(-1,-1,1,1,1,2),new AABB(-1,-1,-1,1,1,1),new AABB(-100,-1,-10,1,1,-9),new AABB(-1,-1,-60_000_000,1,1,-59_999_990));
                boxes=boxes.stream().map(b->b.move(origin)).toList();
                if(!(boolean)invoke(hiz,"begin",new Class[]{GlTexture.class,int.class,int.class,CameraRenderState.class,List.class,ByteBudget.class},target.getDepthTexture(),63,35,camera,boxes,new ByteBudget(128L*1024*1024)))throw new AssertionError("Hi-Z inactive");
                var field=type.getDeclaredField("frame");field.setAccessible(true);var frame=field.get(hiz);var visible=frame.getClass().getDeclaredField("visible");visible.setAccessible(true);
                glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)visible.get(frame)).handle());
                int[] flags=new int[6];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,flags);
                if(!Arrays.equals(flags,new int[]{0,1,1,1,0,0}))throw new AssertionError("Visibility "+Arrays.toString(flags)+" at "+origin);
                var first=BufferUtils.createIntBuffer(2).put(new int[]{0,6}).flip();var counts=BufferUtils.createIntBuffer(2).put(new int[]{6,6}).flip();var base=BufferUtils.createIntBuffer(2).put(new int[]{0,0}).flip();PointerBuffer offsets=BufferUtils.createPointerBuffer(2).put(0).put(24).flip();var ids=BufferUtils.createIntBuffer(2).put(new int[]{0,1}).flip();
                var args=(GpuBufferSlice)invoke(hiz,"queue",new Class[]{IntBuffer.class,IntBuffer.class,IntBuffer.class,PointerBuffer.class,IntBuffer.class,boolean.class,int.class},first,counts,base,offsets,ids,true,2);
                var water=(GpuBufferSlice)invoke(hiz,"queue",new Class[]{IntBuffer.class,IntBuffer.class,IntBuffer.class,PointerBuffer.class,IntBuffer.class,boolean.class,int.class},first,counts,base,offsets,ids,false,1);
                var quadFirst=BufferUtils.createIntBuffer(2).put(new int[]{5,9}).flip();
                var quadCounts=BufferUtils.createIntBuffer(2).put(new int[]{3,7}).flip();
                var quads=(GpuBufferSlice)invoke(hiz,"queue",new Class[]{IntBuffer.class,IntBuffer.class,IntBuffer.class,PointerBuffer.class,IntBuffer.class,boolean.class,int.class,boolean.class},quadFirst,quadCounts,base,offsets,ids,false,2,true);
                invoke(hiz,"filterCommands",new Class[]{});glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)args.buffer()).handle());
                int[] words=new int[10];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,args.offset(),words);if(words[0]!=0||words[5]!=6||words[7]!=6)throw new AssertionError("Indirect commands "+Arrays.toString(words));
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)quads.buffer()).handle());int[] quadWords=new int[8];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,quads.offset(),quadWords);
                if(!Arrays.equals(quadWords,new int[]{0,3,0,5,6,7,0,9}))throw new AssertionError("Quad indirect instance ranges must survive Hi-Z filtering "+Arrays.toString(quadWords));
                var paint=(GpuBufferSlice)invoke(hiz,"painted",new Class[]{GpuBufferSlice.class},args);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)paint.buffer()).handle());int[] paintedWords=new int[10];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,paint.offset(),paintedWords);
                if(paintedWords[0]!=6||paintedWords[5]!=0)throw new AssertionError("Paint must select hidden commands only "+Arrays.toString(paintedWords));
                if(input==null){var data=MemoryUtil.memCalloc(2*64*48).order(ByteOrder.nativeOrder());try{data.putFloat(2*16+12,0).putFloat(5*16+12,1).putFloat(3*16,123);input=RenderSystem.getDevice().createBuffer(()->"Hi-Z check instances",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,data);}finally{MemoryUtil.memFree(data);}}
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)water.buffer()).handle());int[] waterWords=new int[4];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,water.offset(),waterWords);if(waterWords[0]!=0)throw new AssertionError("Non-indexed command not culled");
                var compact=(Object[])invoke(hiz,"compactAll",new Class[]{GpuBufferSlice.class,int.class,int[].class},input.slice(),64*48,new int[]{2,1});
                for(int g=0;g<2;g++) {
                    var cmd=compact[g].getClass().getDeclaredMethod("command");cmd.setAccessible(true);var draw=(GpuBufferSlice)cmd.invoke(compact[g]);
                    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)draw.buffer()).handle());int[] plane=new int[4];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,draw.offset(),plane);if(plane[0]!=6||plane[1]!=(g==0?1:0))throw new AssertionError("Plane group "+g+" "+Arrays.toString(plane));
                }
                var instances=compact[0].getClass().getDeclaredMethod("instances");instances.setAccessible(true);var output=(GpuBufferSlice)instances.invoke(compact[0]);glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)output.buffer()).handle());float[] rows=new float[12];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,output.offset(),rows);if(rows[0]!=123)throw new AssertionError("Wrong instance selected");
                var paintDrawing=type.getDeclaredField("paintDrawing");paintDrawing.setAccessible(true);paintDrawing.set(hiz,true);
                var paintedPlanes=(Object[])invoke(hiz,"compactAll",new Class[]{GpuBufferSlice.class,int.class,int[].class},input.slice(),64*48,new int[]{2,1});
                paintDrawing.set(hiz,false);
                for(int g=0;g<2;g++){
                    var cmd=paintedPlanes[g].getClass().getDeclaredMethod("command");cmd.setAccessible(true);var draw=(GpuBufferSlice)cmd.invoke(paintedPlanes[g]);
                    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)draw.buffer()).handle());int[] red=new int[4];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,draw.offset(),red);if(red[1]!=1)throw new AssertionError("Painted plane selection "+Arrays.toString(red));
                }
                if(cases==0) {
                    var cf=type.getDeclaredField("counters");cf.setAccessible(true);Object counter=cf.get(hiz);
                    var rate=counter.getClass().getDeclaredMethod("setPollRate",int.class);rate.setAccessible(true);rate.invoke(counter,5);
                    var period=counter.getClass().getDeclaredField("periodNanos");period.setAccessible(true);if(period.getLong(counter)!=200_000_000L)throw new AssertionError("5 Hz must use a 200 ms interval");
                    var begin=counter.getClass().getDeclaredMethod("begin",type,long[].class,ByteBudget.class);begin.setAccessible(true);
                    begin.invoke(counter,hiz,new long[]{100,200,300,400,500,600},new ByteBudget(128L*1024*1024));
                    var plane=counter.getClass().getDeclaredMethod("plane",int.class);plane.setAccessible(true);plane.invoke(counter,0);plane.invoke(counter,2);plane.invoke(counter,5);
                    var add=counter.getClass().getDeclaredMethod("add",int.class,long.class,boolean.class);add.setAccessible(true);
                    add.invoke(counter,0,2L,true);add.invoke(counter,1,3L,true);add.invoke(counter,1,5L,false);add.invoke(counter,2,1L<<32,false);
                    var finish=counter.getClass().getDeclaredMethod("finish",GpuBuffer.class);finish.setAccessible(true);finish.invoke(counter,visible.get(frame));glFlush();
                    long deadline=System.nanoTime()+2_000_000_000L;Object result=null;
                    while(System.nanoTime()<deadline) {begin.invoke(counter,hiz,null,new ByteBudget(128L*1024*1024));result=counter.getClass().getMethod("snapshot").invoke(counter);if(result!=null)break;Thread.sleep(5);}
                    if(result==null)throw new AssertionError("Async HUD counters timed out");
                    String[] names={"preTriangles","postTriangles","preSamples","postSamples","prePlanes","preVoxels","postPlanes","postVoxels"};long[] expected={(1L<<32)+10,(1L<<32)+8,600,500,2,1,1,1};
                    for(int i=0;i<names.length;i++)if((long)result.getClass().getMethod(names[i]).invoke(result)!=expected[i])throw new AssertionError("Counter "+names[i]+" "+result);
                    rate.invoke(counter,2);var due=counter.getClass().getDeclaredMethod("due");due.setAccessible(true);
                    if(period.getLong(counter)!=500_000_000L||!(boolean)due.invoke(counter))throw new AssertionError("Changed poll rate must apply immediately");
                }
                invoke(hiz,"end",new Class[]{});cases+=10;
            }
            // Wide, thin tile: coarse square mip cells include empty sky, even though the
            // complete projected tile lies behind a narrow strip of opaque depth.
            var depthData=MemoryUtil.memCallocFloat(63*35);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
            try {
                for(int y=10;y<=23;y++)for(int x=0;x<63;x++)depthData.put(y*63+x,.5f);
                glBindTexture(GL_TEXTURE_2D,((GlTexture)target.getDepthTexture()).glId());glTexSubImage2D(GL_TEXTURE_2D,0,0,0,63,35,GL_DEPTH_COMPONENT,GL_FLOAT,depthData);
            }finally{glBindTexture(GL_TEXTURE_2D,oldTexture);MemoryUtil.memFree(depthData);}
            camera.pos=Vec3.ZERO;
            var stripBoxes=List.of(new AABB(-5,-.01,-10,5,.01,-9),new AABB(-5,-5,-10,5,5,-9));
            if(!(boolean)invoke(hiz,"begin",new Class[]{GlTexture.class,int.class,int.class,CameraRenderState.class,List.class,ByteBudget.class},target.getDepthTexture(),63,35,camera,stripBoxes,new ByteBudget(128L*1024*1024)))throw new AssertionError("Sparse-depth check inactive");
            var sf=type.getDeclaredField("frame");sf.setAccessible(true);var st=sf.get(hiz);var sv=st.getClass().getDeclaredField("visible");sv.setAccessible(true);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)sv.get(st)).handle());int[] stripFlags=new int[2];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,stripFlags);
            if(!Arrays.equals(stripFlags,new int[]{0,1}))throw new AssertionError("Sparse strip visibility "+Arrays.toString(stripFlags));invoke(hiz,"end",new Class[]{});cases+=2;
            encoder=RenderSystem.getDevice().createCommandEncoder();encoder.clearDepthTexture(target.getDepthTexture(),0);encoder.submit();
            var openBox=List.of(new AABB(-.1,-.1,-100,.1,.1,-90).move(camera.pos));
            if(!(boolean)invoke(hiz,"begin",new Class[]{GlTexture.class,int.class,int.class,CameraRenderState.class,List.class,ByteBudget.class},target.getDepthTexture(),63,35,camera,openBox,new ByteBudget(128L*1024*1024)))throw new AssertionError("Empty-depth check inactive");
            var ff=type.getDeclaredField("frame");ff.setAccessible(true);var current=ff.get(hiz);var vf=current.getClass().getDeclaredField("visible");vf.setAccessible(true);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);glBindBuffer(GL_SHADER_STORAGE_BUFFER,((GlBuffer)vf.get(current)).handle());int[] open=new int[1];glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,open);if(open[0]!=1)throw new AssertionError("Empty depth hid geometry");cases++;
            int error=glGetError();if(error!=GL_NO_ERROR)throw new AssertionError("OpenGL error "+error);
            Files.writeString(report,"Passed "+cases+" GPU checks: reverse-Z bounds, near/camera/edge fallbacks, 60M precision, indexed/non-indexed/quad-instance indirect filtering, six production quad pipelines, offscreen quad batching/position/color, batched plane compaction, empty-depth fallback, asynchronous 64-bit HUD counters, unique samples and hidden-only paint commands/planes; 63x35 depth pyramid.");
        }finally {glBindBuffer(GL_SHADER_STORAGE_BUFFER,old);if(input!=null)input.close();invoke(hiz,"close",new Class[]{});target.destroyBuffers();}
    }
}
