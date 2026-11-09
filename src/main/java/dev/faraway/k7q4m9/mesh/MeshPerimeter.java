package dev.faraway.k7q4m9.mesh;

/** Ground perimeter in clockwise order, with an independent height at each end of a segment. */
public final class MeshPerimeter {
    public static final int NORTH=0,EAST=1,SOUTH=2,WEST=3;
    private final int cells;
    private final int[] heights;

    MeshPerimeter(int cells) {
        this.cells=cells;
        heights=new int[4*cells];
    }

    public int cells() { return cells; }
    public int startY(int side,int cell) { return (short)heights[side*cells+cell]; }
    public int endY(int side,int cell) { return heights[side*cells+cell]>>16; }
    long bytes() { return heights.length*4L; }

    void set(int side,int cell,int startY,int endY) {
        heights[side*cells+cell]=(endY<<16)|(startY&0xffff);
    }
    void lower(int side,int first,int count,int y) {
        for(int i=first;i<first+count;i++) {
            set(side,i,Math.min(y,startY(side,i)),Math.min(y,endY(side,i)));
        }
    }
}
