package dev.faraway.k7q4m9.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.faraway.k7q4m9.render.MipmapSpriteContents;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SpriteContents.class)
abstract class MipmapSpriteContentsMixin implements MipmapSpriteContents {
    @Shadow @Final @Mutable private int width;
    @Shadow @Final @Mutable private int height;
    @Shadow @Final @Mutable private NativeImage originalImage;
    @Shadow private NativeImage[] byMipLevel;

    @Override
    public boolean faraway$alignFramesForMipmaps(int level) {
        int alignment=1<<level;
        int alignedWidth=(width+alignment-1)&-alignment;
        int alignedHeight=(height+alignment-1)&-alignment;
        if(width==alignedWidth && height==alignedHeight) return false;

        int columns=originalImage.getWidth()/width;
        int rows=originalImage.getHeight()/height;
        var aligned=new NativeImage(originalImage.format(),Math.multiplyExact(columns,alignedWidth),
            Math.multiplyExact(rows,alignedHeight),false);
        try {
            // Resize each frame in place in the sheet grid. Vanilla animation
            // keeps the frame indices, order, interpolation and timings.
            for(int row=0;row<rows;row++) for(int column=0;column<columns;column++) {
                for(int y=0;y<alignedHeight;y++) for(int x=0;x<alignedWidth;x++) {
                    int sourceX=column*width+x*width/alignedWidth;
                    int sourceY=row*height+y*height/alignedHeight;
                    aligned.setPixel(column*alignedWidth+x,row*alignedHeight+y,originalImage.getPixel(sourceX,sourceY));
                }
            }
        } catch(RuntimeException | Error failure) {
            aligned.close();
            throw failure;
        }
        for(var mip:byMipLevel) if(mip!=null) mip.close();
        originalImage=aligned;
        byMipLevel=new NativeImage[]{aligned};
        width=alignedWidth;
        height=alignedHeight;
        return true;
    }
}
