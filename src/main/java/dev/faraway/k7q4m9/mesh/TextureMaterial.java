package dev.faraway.k7q4m9.mesh;

import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/** Compact approximate materials; 255 denotes an untextured legacy vertex. */
public final class TextureMaterial {
    public static final int UNKNOWN=80;
    private static final int LAVA_SURFACE=81;
    private static final Identifier LAVA_SPRITE=Identifier.withDefaultNamespace("block/lava_still");
    private static final String[] WOODS={"oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry","pale_oak","bamboo","crimson","warped","poplar"};
    public static final String[] SPRITES=new String[254];
    public static final int MATERIAL_UNIFORM_BYTES = (SPRITES.length * 2 + TextureVariantRules.VARIANT_CAPACITY) * 16;
    private static final java.util.Map<String,Integer> INDICES=new java.util.HashMap<>();
    private record ModelMaterial(net.minecraft.resources.Identifier sprite,TextureVariantRules.Rule variants) {}
    private static final java.util.Map<ModelMaterial,Integer> MODEL_INDICES=new java.util.HashMap<>();
    private static final TextureVariantRules.Rule[] VARIANTS=new TextureVariantRules.Rule[SPRITES.length];
    private static final java.util.Map<Identifier,Integer> SPRITE_INDICES=new java.util.HashMap<>();
    private static final java.util.Set<String> MODEL_WARNINGS=new java.util.HashSet<>();
    private static final java.util.Map<BlockState,int[]> MODEL_STATES=new java.util.HashMap<>();
    private static final net.minecraft.resources.Identifier[] MODEL_SPRITES=new net.minecraft.resources.Identifier[254];
    private static int nextModel=LAVA_SURFACE+1,revision;
    static {
        var names=new java.util.ArrayList<String>(java.util.List.of("grass_block_top","sand","stone","snow","terracotta","oak_planks","oak_log","oak_leaves","cobblestone","dirt","sandstone","bricks","spruce_log","birch_log","jungle_log","acacia_log","dark_oak_log","mangrove_log","cherry_log","pale_oak_log","spruce_leaves","birch_leaves","jungle_leaves","acacia_leaves","dark_oak_leaves","mangrove_leaves","cherry_leaves","pale_oak_leaves"));
        for(String wood:WOODS) if(!wood.equals("oak")) names.add(wood+"_planks");
        names.add("farmland"); names.add("farmland_moist");
        for(String wood:WOODS) { names.add(wood+"_door_bottom"); names.add(wood+"_door_top"); }
        for(String metal:new String[]{"iron","copper","exposed_copper","weathered_copper","oxidized_copper"}) { names.add(metal+"_door_bottom"); names.add(metal+"_door_top"); }
        names.add("stone_bricks"); names.add("smooth_stone");
        if(names.size()>80) throw new IllegalStateException("Texture material palette exhausted");
        java.util.Arrays.fill(SPRITES,"stone");
        for(int i=0;i<names.size();i++) { SPRITES[i]=names.get(i); INDICES.put(names.get(i),i); SPRITE_INDICES.put(Identifier.withDefaultNamespace("block/"+names.get(i)),i); }
    }
    private TextureMaterial() {}
    public static int terrain(int color) {
        return switch(color) { case 0xd8c38a -> 1; case 0x92928c -> 2; case 0xe7edf0 -> 3; case 0xb86b40 -> 4; default -> 0; };
    }
    public static int block(BlockState state) { return block(state,3); }
    public static int block(BlockState state,int face) {
        var client=net.minecraft.client.Minecraft.getInstance();
        if(client!=null && client.level!=null && !state.is(net.minecraft.tags.BlockTags.LEAVES)) {
            synchronized(TextureMaterial.class) {
                return MODEL_STATES.computeIfAbsent(state,TextureMaterial::resolveModel)[face];
            }
        }
        return fallbackBlock(state,face);
    }
    private static int fallbackBlock(BlockState state,int face) {
        String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if(state.getBlock() instanceof net.minecraft.world.level.block.FarmlandBlock) {
            if(face!=3) return 9;
            return INDICES.get(state.getValue(net.minecraft.world.level.block.FarmlandBlock.MOISTURE)>0?"farmland_moist":"farmland");
        }
        if(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            String sprite=name+(state.getValue(net.minecraft.world.level.block.DoorBlock.HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER?"_top":"_bottom");
            return INDICES.getOrDefault(sprite,UNKNOWN);
        }
        if(name.contains("leaves")) return leafForLog(logType(name));
        if(name.contains("log") || name.contains("wood") || name.contains("stem")) return logType(name);
        for(String wood:WOODS) if(name.startsWith(wood+"_") && (name.endsWith("_planks") || name.endsWith("_stairs") || name.endsWith("_slab") || name.endsWith("_fence") || name.endsWith("_fence_gate") || name.endsWith("_pressure_plate"))) return INDICES.get(wood+"_planks");
        if(name.contains("cobblestone")) return 8;
        if(name.contains("sandstone")) return 10;
        if(name.contains("sand")) return 1;
        if(name.contains("snow")) return 3;
        if(name.contains("terracotta")) return 4;
        if(name.startsWith("stone_brick")) return INDICES.get("stone_bricks");
        if(name.startsWith("smooth_stone")) return INDICES.get("smooth_stone");
        if(name.contains("brick")) return 11;
        if(name.contains("dirt") || name.contains("mud")) return 9;
        if(name.contains("grass")) return 0;
        if(name.equals("stone") || name.startsWith("stone_") || name.startsWith("smooth_stone")) return 2;
        return UNKNOWN;
    }
    private static int[] resolveModel(BlockState state) {
        int[] result=new int[6]; java.util.Arrays.fill(result,UNKNOWN);
        var client=net.minecraft.client.Minecraft.getInstance();
        var models=client.getModelManager().getBlockStateModelSet();
        var model=models.get(state);
        if(model==models.missingModel()) {
            warnMaterial(state,-1,"missing block model");
            return result;
        }
        var faces=TextureVariantRules.quads(model);
        var rule=TextureVariantRules.fromModel(model,faces);
        var particle=model.particleMaterial().sprite();
        var fallback=particle.atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS)?particle.contents().name():null;
        for(int face=0;face<faces.length;face++) {
            var sprite=faces[face]==null?null:TextureVariantRules.spriteName(faces[face]);
            if(!usableSprite(sprite)) sprite=fallback;
            if(!usableSprite(sprite)) {
                warnMaterial(state,face,"no usable block-atlas sprite");
                continue;
            }
            int material=registerModelSprite(sprite,rule.forSprite(sprite,faces));
            if(material==UNKNOWN) {
                warnMaterial(state,face,"palette full for "+sprite);
                // A valid log model can still use its existing bark material if
                // the only missing entry is a new end-grain or bark variant.
                if(state.is(BlockTags.LOGS)) {
                    var bark=usableSprite(fallback)?SPRITE_INDICES.get(fallback):null;
                    material=bark!=null?bark:fallbackBlock(state,face);
                }
            }
            result[face]=material;
        }
        return result;
    }

    private static boolean usableSprite(Identifier sprite) {
        return sprite!=null && !sprite.equals(MissingTextureAtlasSprite.getLocation());
    }

    private static void warnMaterial(BlockState state,int face,String reason) {
        if(MODEL_WARNINGS.size()>=32) return;
        var block=BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if(MODEL_WARNINGS.add(block+" "+reason)) dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn(
            "FarAway material resolution block={} state={} face={} reason={}",block,state,face,reason);
    }

    public static int lavaSurface() { return LAVA_SURFACE; }

    private static int registerModelSprite(Identifier name,TextureVariantRules.Rule variants) {
        var key=new ModelMaterial(name,variants);
        Integer existing=MODEL_INDICES.get(key);
        if(existing!=null) return existing;
        Integer sameSprite=SPRITE_INDICES.get(name);
        if(sameSprite!=null && materialVariants(sameSprite).equals(variants)) return sameSprite;
        if(nextModel>=MODEL_SPRITES.length) return sameSprite==null?UNKNOWN:sameSprite;
        int index=nextModel++;
        MODEL_SPRITES[index]=name;
        VARIANTS[index]=variants;
        MODEL_INDICES.put(key,index);
        SPRITE_INDICES.putIfAbsent(name,index);
        revision++;
        return index;
    }

    private static TextureVariantRules.Rule materialVariants(int index) {
        var rule = VARIANTS[index];
        if (rule != null) return rule;
        if (index >= UNKNOWN || INDICES.getOrDefault(SPRITES[index], -1) != index) return TextureVariantRules.Rule.NONE;

        // The approximate terrain palette stores sprite names rather than block states.
        String name = SPRITES[index];
        String block = name.endsWith("_top") ? name.substring(0, name.length() - 4) : name;
        var state = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(block)).defaultBlockState();
        var model = net.minecraft.client.Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        var faces = TextureVariantRules.quads(model);
        rule = TextureVariantRules.fromModel(model, faces).forSprite(sprite(index), faces);
        VARIANTS[index] = rule;
        return rule;
    }

    public static synchronized void writeTextureVariants(java.nio.ByteBuffer data) {
        var choices = new java.util.ArrayList<TextureVariantRules.Choice>();
        var offsets = new java.util.HashMap<java.util.List<TextureVariantRules.Choice>, Integer>();
        for (int material = 0; material < SPRITES.length; material++) {
            var rule = materialVariants(material);
            var variants = rule.choices();
            Integer offset = offsets.get(variants);
            if (!variants.isEmpty() && offset == null && choices.size() + variants.size() <= TextureVariantRules.VARIANT_CAPACITY) {
                offset = choices.size();
                offsets.put(variants, offset);
                choices.addAll(variants);
            }
            if (offset == null) {
                data.putInt(0).putInt(0).putInt(0).putInt(0);
            } else {
                data.putInt(offset).putInt(variants.size()).putInt(variants.getLast().endWeight()).putInt(rule.faceMask());
            }
        }
        for (var choice : choices) {
            data.putInt(choice.transforms()).putInt(choice.endWeight()).putInt(0).putInt(0);
        }
        while (data.hasRemaining()) data.putInt(0);
    }
    public static synchronized int revision() { return revision; }
    public static synchronized net.minecraft.resources.Identifier sprite(int index) {
        if(index==LAVA_SURFACE) return LAVA_SPRITE;
        return MODEL_SPRITES[index]!=null?MODEL_SPRITES[index]:net.minecraft.resources.Identifier.withDefaultNamespace("block/"+SPRITES[index]);
    }
    public static synchronized void resetModels() {
        MODEL_STATES.clear(); MODEL_INDICES.clear(); MODEL_WARNINGS.clear();
        SPRITE_INDICES.values().removeIf(index -> index>=UNKNOWN); java.util.Arrays.fill(VARIANTS,null); java.util.Arrays.fill(MODEL_SPRITES,null); nextModel=LAVA_SURFACE+1; revision++;
    }
    public static int logType(String name) {
        if(name.contains("cherry")) return 18;
        if(name.contains("spruce") || name.contains("taiga") || name.equals("grove")) return 12;
        if(name.contains("birch")) return 13;
        if(name.contains("jungle")) return 14;
        if(name.contains("acacia") || name.contains("savanna")) return 15;
        if(name.contains("dark_oak") || name.contains("dark_forest")) return 16;
        if(name.contains("mangrove")) return 17;
        if(name.contains("pale_oak") || name.contains("pale_garden")) return 19;
        return 6;
    }
    static boolean isLeaf(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || state.is(net.minecraft.tags.BlockTags.LEAVES);
    }
    public static int leafForLog(int log) { return log>=12 && log<=19?log+8:7; }
}
