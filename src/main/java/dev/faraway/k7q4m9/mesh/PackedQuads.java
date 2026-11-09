package dev.faraway.k7q4m9.mesh;

import java.nio.*;
import java.io.ByteArrayOutputStream;
import java.util.function.BooleanSupplier;

/** Exact axis-aligned rectangles: origin, two signed extents, axes and four RGBA8 corners. */
public final class PackedQuads {
    public static final int STRIDE=40;
    public record Result(byte[] quads,byte[] fallback) {}
    private PackedQuads() {}
    public static Result pack(byte[] vertices,BooleanSupplier cancelled) {
        var in=ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
        var quads=new ByteArrayOutputStream();var fallback=new ByteArrayOutputStream();
        var record=ByteBuffer.allocate(STRIDE).order(ByteOrder.nativeOrder());
        int at=0;
        for(;at+96<=vertices.length;at+=96) {
            if((at&65535)==0 && cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            int u=axis(in,at,at+16),v=axis(in,at,at+32);
            boolean valid=u>=0 && v>=0 && u!=v;
            for(int c=0;valid && c<3;c++) {
                float a=in.getFloat(at+c*4),b=in.getFloat(at+16+c*4),d=in.getFloat(at+32+c*4),e=in.getFloat(at+80+c*4);
                valid=Float.isFinite(a)&&Float.isFinite(b)&&Float.isFinite(d)&&Float.isFinite(e)
                    && e==b+(d-a) && in.getInt(at+48+c*4)==in.getInt(at+32+c*4) && in.getInt(at+64+c*4)==in.getInt(at+16+c*4);
            }
            // The fragment material must remain identical over both triangles.
            int material=Byte.toUnsignedInt(vertices[at+15]);
            for(int c=1;valid && c<6;c++) valid=Byte.toUnsignedInt(vertices[at+c*16+15])==material;
            valid=valid && in.getInt(at+60)==in.getInt(at+44) && in.getInt(at+76)==in.getInt(at+28);
            if(!valid) {fallback.write(vertices,at,96);continue;}
            record.clear();record.putFloat(in.getFloat(at)).putFloat(in.getFloat(at+4)).putFloat(in.getFloat(at+8));
            record.putFloat(in.getFloat(at+16+u*4)-in.getFloat(at+u*4));
            record.putFloat(in.getFloat(at+32+v*4)-in.getFloat(at+v*4));record.putInt(u|(v<<2));
            record.putInt(in.getInt(at+12)).putInt(in.getInt(at+28)).putInt(in.getInt(at+44)).putInt(in.getInt(at+92));
            quads.writeBytes(record.array());
        }
        fallback.write(vertices,at,vertices.length-at);
        return new Result(quads.toByteArray(),fallback.toByteArray());
    }
    private static int axis(ByteBuffer in,int a,int b) {
        int axis=-1;
        for(int c=0;c<3;c++)if(in.getFloat(a+c*4)!=in.getFloat(b+c*4)) {if(axis>=0)return -1;axis=c;}
        return axis;
    }
}
