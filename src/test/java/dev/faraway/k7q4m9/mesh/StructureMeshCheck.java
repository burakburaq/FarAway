package dev.faraway.k7q4m9.mesh;

import java.nio.*;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Quadrant;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.random.Weighted;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import dev.faraway.k7q4m9.terrain.TileKey;

/** No game/client/server: checks real vanilla stair/slab shapes and atomic mesh composition. */
public final class StructureMeshCheck {
    public static void main(String[] args) throws java.io.IOException {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var grass = textureVariants("grass_block", "snowy=false");
        check(grass.choices().size() == 4 && grass.choices().stream().map(c -> (c.transforms() >>> 9) & 7).distinct().count() == 4,
            "Grass must retain four distinct top-face rotations");
        check(textureVariants("grass_block", "snowy=true").faceMask() == 0, "Snowy grass must keep its fixed texture orientation");
        var stone = textureVariants("stone", "");
        check(stone.faceMask() == 63 && stone.choices().size() == 4, "Stone must support mirrors on every face and both declared rotations");
        var netherrack = textureVariants("netherrack", "");
        check(netherrack.faceMask() == 63 && netherrack.choices().size() == 16, "Netherrack must support its full set of X/Y rotations");
        var builder=new DecorationMeshBuilder(); var pos=new BlockPos(0,0,0);
        for(var plant:new net.minecraft.world.level.block.Block[]{Blocks.WHEAT,Blocks.CARROTS,Blocks.SHORT_GRASS,Blocks.TALL_GRASS,Blocks.POPPY,Blocks.SUNFLOWER,Blocks.PUMPKIN_STEM})
            check(!builder.build(new TileKey(0,0,1),Map.of(pos,plant.defaultBlockState()),() -> false).vertices().hasRemaining(),"Crops, grass and flowers must not create LOD geometry");
        check(builder.build(new TileKey(0,0,1),Map.of(pos,Blocks.FARMLAND.defaultBlockState()),() -> false).vertices().hasRemaining(),"Farmland must remain visible");
        for(var unsupported:new net.minecraft.world.level.block.Block[]{Blocks.COBWEB,Blocks.CHEST,Blocks.TRAPPED_CHEST})
            check(!builder.build(new TileKey(0,0,1),Map.of(pos,unsupported.defaultBlockState()),() -> false).vertices().hasRemaining(),"Unsupported cobwebs and chests must not create LOD geometry");
        check(builder.build(new TileKey(0,0,1),Map.of(pos,Blocks.OAK_FENCE.defaultBlockState()),() -> false).vertices().hasRemaining(),"Supported partial blocks must remain visible");
        check(TextureMaterial.SPRITES[TextureMaterial.block(Blocks.OAK_FENCE.defaultBlockState())].equals("oak_planks"),"Oak fences must use oak planks");
        check(TextureMaterial.SPRITES[TextureMaterial.block(Blocks.SPRUCE_SLAB.defaultBlockState())].equals("spruce_planks"),"Spruce slabs must use spruce planks");
        check(TextureMaterial.SPRITES[TextureMaterial.block(Blocks.STONE_BRICK_SLAB.defaultBlockState())].equals("stone_bricks"),"Stone brick slabs must use stone bricks");
        check(TextureMaterial.SPRITES[TextureMaterial.block(Blocks.FARMLAND.defaultBlockState(),3)].equals("farmland"),"Farmland top must use farmland texture");
        check(TextureMaterial.SPRITES[TextureMaterial.block(Blocks.FARMLAND.defaultBlockState(),0)].equals("dirt"),"Farmland sides must use dirt");
        check(TextureMaterial.SPRITES[TextureMaterial.block(Blocks.BIRCH_DOOR.defaultBlockState())].equals("birch_door_bottom"),"Door texture must match wood type and half");
        check(TextureMaterial.block(Blocks.GOLD_BLOCK.defaultBlockState())==TextureMaterial.UNKNOWN,"Unmapped blocks must use the visible unknown material");
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.OAK_SLAB,Blocks.OAK_STAIRS}) {
            var blocks=Map.of(pos,block.defaultBlockState());
            var exact=builder.build(new TileKey(0,0,1),blocks,() -> false);
            double expected=block==Blocks.OAK_SLAB?.5:.75;
            check(Math.abs(volume(exact)-expected)<.0001,"LOD 0 must preserve the actual slab/stair volume");
            for(int spacing:new int[]{2,4,64}) {
                var full=builder.build(new TileKey(0,0,spacing),blocks,() -> false);
                check(full.vertices().remaining()==36*16 && Math.abs(volume(full)-1)<.0001,"LOD 1+ must render stairs/slabs as full cubes");
            }
        }
        var neighbors=Map.of(pos,Blocks.OAK_LOG.defaultBlockState(),pos.east(),Blocks.OAK_LEAVES.defaultBlockState());
        var leafKey=new TileKey(0,0,1);
        var cutout=builder.build(leafKey,neighbors,(x,z)->0x709050,false,()->false);
        var opaque=builder.build(leafKey,neighbors,(x,z)->0x709050,true,()->false);
        check(cutout.triangleCount()-opaque.triangleCount()==2,"Opaque leaves must remove the shared log face: cutout="+cutout.triangleCount()+", opaque="+opaque.triangleCount());
        var pair=builder.build(new TileKey(0,0,2),Map.of(pos,Blocks.OAK_SLAB.defaultBlockState(),pos.east(),Blocks.OAK_STAIRS.defaultBlockState()),() -> false);
        check(pair.vertices().remaining()==36*16 && Math.abs(volume(pair)-2)<.0001,
            "Simplified neighbors must remove their shared face and merge matching exterior faces");
        var grid=new GridStructureMeshBuilder().build(new TileKey(0,0,2),Map.of(pos,Blocks.OAK_SLAB.defaultBlockState(),pos.east(),Blocks.OAK_STAIRS.defaultBlockState()),(x,z) -> 0x709050,() -> false);
        check(grid.triangleCount()==12 && Math.abs(volume(grid)-8)<.0001,"Structure blocks in one LOD cell must form one grid voxel");
        var packed=grid.optimize(true,() -> false);
        check(packed.vertexStride()==8 && packed.indexCount()>0 && packed.triangleCount()==grid.triangleCount() && packed.bytes()<grid.bytes(),"Grid packing and vertex sharing must preserve triangles and reduce bytes");
        var packedVertices=packed.vertices().order(ByteOrder.nativeOrder());
        for(int i=0;i<packed.vertexCount();i++) {
            int position=packedVertices.getInt(i*8),gx=position&63,gz=(position>>>6)&63,y=(short)((position>>>12)&65535);
            check(gx<=1 && gz<=1 && (y==0 || y==32),"Packed grid coordinates must decode exactly");
        }
        var unaligned=builder.build(new TileKey(0,0,2),Map.of(pos,Blocks.OAK_SLAB.defaultBlockState()),() -> false);
        var fallback=unaligned.optimize(true,() -> false);
        check(fallback.vertexStride()==16 && fallback.triangleCount()==unaligned.triangleCount(),"Non-grid coordinates must retain precise vertices");
        int unique=66000;
        var many=ByteBuffer.allocate(unique*2*16).order(ByteOrder.nativeOrder());
        for(int repeat=0;repeat<2;repeat++) for(int i=0;i<unique;i++) many.putFloat(i).putFloat(0).putFloat(0).putInt(-1);
        var large=new TileMesh(new TileKey(0,0,1),many.array(),new byte[0],new byte[0],0,0).optimize(false,() -> false);
        check(large.vertexCount()==unique && large.indexStride()==4 && large.indexCount()==unique*2,"Large shared meshes must use 32-bit indices without overflow");
        var key=new TileKey(0,0,2);
        byte[] vertices=new byte[48],indices=ByteBuffer.allocate(6).order(ByteOrder.nativeOrder()).putShort((short)2).putShort((short)0).putShort((short)1).array();
        vertices[32]=30; vertices[0]=10; vertices[16]=20;
        var terrain=new TileMesh(key,vertices,indices,new byte[96],-10,10);
        check(terrain.triangleCount()==3,"CPU triangle count must include indexed terrain and water");
        var combined=terrain.append(pair);
        check(combined.indexCount()==0 && combined.vertices().remaining()==48+pair.vertices().remaining(),"Terrain and structures must form one draw payload");
        check(combined.vertices().get(0)==30 && combined.vertices().get(16)==10 && combined.vertices().get(32)==20,"Indexed terrain order must survive atomic mesh composition");
        check(combined.waterVertices().remaining()==96,"Atomic mesh composition must preserve water");
        System.out.println("Structure LOD shapes and combined tile payload checks passed");
    }
    private static TextureVariantRules.Rule textureVariants(String block, String state) throws java.io.IOException {
        var path = "/assets/minecraft/blockstates/" + block + ".json";
        try (var stream = StructureMeshCheck.class.getResourceAsStream(path)) {
            check(stream != null, "Missing vanilla blockstate " + block);
            var definition = JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("variants").get(state);
            if (!definition.isJsonArray()) return TextureVariantRules.Rule.NONE;

            var contents = new SpriteContents(Identifier.withDefaultNamespace("block/" + block), new FrameSize(16, 16), new NativeImage(16, 16, true));
            try (var sprite = new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, contents, 32, 32, 0, 0, 0) {}) {
                var material = new BakedQuad.MaterialInfo(sprite, null, null, null, null, -1, null, 0);
                var interner = new ModelBaker.Interner() {
                    public Vector3fc vector(Vector3fc vector) { return vector; }
                    public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo info) { return info; }
                };
                var variants = new ArrayList<Weighted<BlockStateModel>>();
                for (var entry : definition.getAsJsonArray()) {
                    var variant = Variant.CODEC.parse(JsonOps.INSTANCE, entry).getOrThrow();
                    boolean mirrored = variant.modelLocation().getPath().endsWith("_mirrored");
                    var uv = new CuboidFace.UVs(mirrored ? 16 : 0, 0, mirrored ? 0 : 16, 16);
                    var quads = new java.util.EnumMap<Direction, BakedQuad>(Direction.class);
                    for (Direction direction : Direction.values()) {
                        var quad = FaceBakery.bakeQuad(interner, new Vector3f(), new Vector3f(16), uv, Quadrant.R0,
                            material, direction, variant.modelState().asModelState(), null);
                        quads.put(quad.direction(), quad);
                    }
                    var model = new SingleVariant(new BlockStateModelPart() {
                        public List<BakedQuad> getQuads(Direction direction) {
                            return direction != null && quads.containsKey(direction) ? List.of(quads.get(direction)) : List.of();
                        }
                        public boolean useAmbientOcclusion() { return false; }
                        public Material.Baked particleMaterial() { return new Material.Baked(sprite, false); }
                        public int materialFlags() { return 0; }
                    });
                    var json = entry.getAsJsonObject();
                    variants.add(new Weighted<>(model, json.has("weight") ? json.get("weight").getAsInt() : 1));
                }
                return TextureVariantRules.fromVariants(variants, TextureVariantRules.quads(variants.getFirst().value()));
            }
        }
    }

    private static double volume(TileMesh mesh) {
        var b=mesh.vertices().order(ByteOrder.nativeOrder()); double value=0;
        for(int i=0;i<b.remaining();i+=48) {
            double ax=b.getFloat(i),ay=b.getFloat(i+4),az=b.getFloat(i+8),bx=b.getFloat(i+16),by=b.getFloat(i+20),bz=b.getFloat(i+24),cx=b.getFloat(i+32),cy=b.getFloat(i+36),cz=b.getFloat(i+40);
            value+=(ax*(by*cz-bz*cy)+ay*(bz*cx-bx*cz)+az*(bx*cy-by*cx))/6;
        }
        return value;
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
