package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.terrain.VegetationField;
import dev.faraway.k7q4m9.mesh.TextureMaterial;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.TreeFeature;

/** Shared immutable biome descriptions; inspecting features never runs placement. */
public final class VegetationProfiles {
    public static final VegetationField.Profile EMPTY=new VegetationField.Profile(0,0,0,0,false);
    private VegetationProfiles() {}
    public static VegetationField.Profile profile(WorldDefinition world,int id) {
        var registry=world.registries().lookupOrThrow(Registries.BIOME); var biome=registry.byId(id);
        if(biome==null) return new VegetationField.Profile(0,0,0,0,false);
        boolean trees=world.generator().getBiomeGenerationSettings(registry.wrapAsHolder(biome)).features().stream().flatMap(set -> set.stream())
            .anyMatch(h -> h.value().getFeatures().anyMatch(f -> f.value() instanceof TreeFeature));
        if(!trees) return new VegetationField.Profile(0,0,0,0,false);
        String n=registry.getKey(biome).getPath();
        int log=TextureMaterial.logType(n);
        if(n.contains("jungle")) return new VegetationField.Profile(.95,20,7,0x376d29,false,log);
        if(n.contains("cherry")) return new VegetationField.Profile(.7,9,5,0xd98caa,false,log);
        if(n.contains("pale_garden")) return new VegetationField.Profile(.9,11,6,0x899782,false,log);
        if(n.contains("taiga") || n.contains("grove")) return new VegetationField.Profile(.8,15,4,0x365b42,true,log);
        if(n.contains("forest")) return new VegetationField.Profile(.85,n.contains("birch")?11:10,5,n.contains("birch")?0x6b893d:0x456b32,false,log);
        if(n.contains("swamp")) return new VegetationField.Profile(.65,n.contains("mangrove")?12:9,6,0x526f35,false,log);
        if(n.contains("savanna")) return new VegetationField.Profile(.3,8,6,0x77803d,false,log);
        return new VegetationField.Profile(.09,8,4,0x54823a,false);
    }
    public static int foliageTint(VegetationField.Profile p,int biomeTint) {
        return p.logMaterial()==12?0x619961:p.logMaterial()==13?0x80a755:p.logMaterial()==18 || p.logMaterial()==19?0xffffff:biomeTint;
    }
}
