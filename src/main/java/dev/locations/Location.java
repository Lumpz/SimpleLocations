package dev.locations;

import net.minecraft.util.math.BlockPos;

public final class Location {
    public final boolean fancy;
    public enum Shape { CIRCLE, SQUARE }

    public final String name;
    public final BlockPos center;
    public final int radius;
    // -1 means default color
    public final int colorRgb;
    public final Shape shape;

    public Location(String name, BlockPos center, int radius) {
        this(name, center, radius, -1, Shape.CIRCLE, false);
    }

    public Location(String name, BlockPos center, int radius, int colorRgb) {
        this(name, center, radius, colorRgb, Shape.CIRCLE, false);
    }

    public Location(String name, BlockPos center, int radius, int colorRgb, Shape shape) {
        this(name, center, radius, colorRgb, shape, false);
    }

    public Location(String name, BlockPos center, int radius, int colorRgb, Shape shape, boolean fancy) {
        this.name = name;
        this.center = center;
        this.radius = radius;
        this.colorRgb = colorRgb;
        this.shape = shape == null ? Shape.CIRCLE : shape;
        this.fancy = fancy;
    }
}
