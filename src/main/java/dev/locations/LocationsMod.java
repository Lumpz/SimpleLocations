package dev.locations;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.util.math.Vec3d;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class LocationsMod implements ModInitializer {
    public static final String MOD_ID = "locations";

    private final Map<UUID, String> lastInside = new HashMap<>();

    // ===== Predefined color presets and helpers =====
    private static final Map<String, Integer> COLOR_PRESETS = new LinkedHashMap<>();
    private static final List<String> COLOR_NAMES;

    static {
        // Minecraft-style classics
        COLOR_PRESETS.put("black",        0x000000);
        COLOR_PRESETS.put("dark_gray",    0x555555);
        COLOR_PRESETS.put("gray",         0xAAAAAA);
        COLOR_PRESETS.put("light_gray",   0xD3D3D3);
        COLOR_PRESETS.put("white",        0xFFFFFF);

        COLOR_PRESETS.put("red",          0xFF5555);
        COLOR_PRESETS.put("dark_red",     0xAA0000);
        COLOR_PRESETS.put("crimson",      0xDC143C);
        COLOR_PRESETS.put("rose",         0xFF007F);
        COLOR_PRESETS.put("salmon",       0xFA8072);
        COLOR_PRESETS.put("hot_pink",     0xFF69B4);
        COLOR_PRESETS.put("magenta",      0xFF00FF);
        COLOR_PRESETS.put("pink",         0xFFC0CB);

        COLOR_PRESETS.put("orange",       0xFFA500);
        COLOR_PRESETS.put("gold",         0xFFAA00);
        COLOR_PRESETS.put("amber",        0xFFBF00);
        COLOR_PRESETS.put("peach",        0xFFDAB9);
        COLOR_PRESETS.put("coral",        0xFF7F50);

        COLOR_PRESETS.put("yellow",       0xFFFF55);
        COLOR_PRESETS.put("lemon",        0xFFF44F);
        COLOR_PRESETS.put("sand",         0xC2B280);
        COLOR_PRESETS.put("beige",        0xF5F5DC);

        COLOR_PRESETS.put("lime",         0x55FF55);
        COLOR_PRESETS.put("green",        0x55AA55);
        COLOR_PRESETS.put("dark_green",   0x00AA00);
        COLOR_PRESETS.put("forest",       0x228B22);
        COLOR_PRESETS.put("olive",        0x808000);
        COLOR_PRESETS.put("chartreuse",   0x7FFF00);
        COLOR_PRESETS.put("spring_green", 0x00FF7F);
        COLOR_PRESETS.put("mint",         0x98FF98);

        COLOR_PRESETS.put("teal",         0x008080);
        COLOR_PRESETS.put("turquoise",    0x40E0D0);
        COLOR_PRESETS.put("aqua",         0x55FFFF);
        COLOR_PRESETS.put("cyan",         0x00FFFF);
        COLOR_PRESETS.put("sky",          0x87CEEB);

        COLOR_PRESETS.put("blue",         0x5555FF);
        COLOR_PRESETS.put("dark_blue",    0x0000AA);
        COLOR_PRESETS.put("navy",         0x000080);
        COLOR_PRESETS.put("indigo",       0x4B0082);

        COLOR_PRESETS.put("purple",       0x800080);
        COLOR_PRESETS.put("violet",       0xEE82EE);
        COLOR_PRESETS.put("light_purple", 0xFF55FF);

        COLOR_PRESETS.put("brown",        0x8B4513);
        COLOR_PRESETS.put("dark_brown",   0x5C4033);
        COLOR_PRESETS.put("maroon",       0x800000);
    }

    static {
        COLOR_NAMES = new ArrayList<>(COLOR_PRESETS.keySet());
    }

    private static int presetColorOrError(String name) {
        Integer rgb = COLOR_PRESETS.get(name.toLowerCase(Locale.ROOT));
        return rgb == null ? -2 : rgb;
    }

     @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(this::registerCommands);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LocationsState.get(server).load();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LocationsState.get(server).save();
        });

        // Enter detection and HUD
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            MinecraftServer server = world.getServer();
            LocationsState state = LocationsState.get(server);

            Identifier dimId = world.getRegistryKey().getValue();
            List<Location> locs = state.getLocations(dimId);
            if (locs.isEmpty()) return;

            for (ServerPlayerEntity p : world.getPlayers()) {
                BlockPos bp = p.getBlockPos();

                Location current = findBestMatch(locs, bp);
                String key = current == null ? null : current.name;

                String prev = lastInside.put(p.getUuid(), key);
                boolean justEntered = !Objects.equals(prev, key) && key != null;

                if (current != null && justEntered) {
                    int rgb = current.colorRgb != -1 ? current.colorRgb : 0xFFFFFF;
                    if (current.fancy) {
                        Text title = Text.literal(current.name)
                                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
                        // underline sized to the name
                        StringBuilder sb = new StringBuilder();
                        int n = Math.max(8, current.name.length() * 2);
                        for (int i = 0; i < n; i++) sb.append('¯');
                        Text subtitle = Text.literal(sb.toString());
                        try {
                            // Center-screen title with fade in/out
							p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 70, 20));
							p.networkHandler.sendPacket(new TitleS2CPacket(title));
							p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
							
							p.networkHandler.sendPacket(
								new PlaySoundS2CPacket(
									SoundEvents.AMBIENT_BASALT_DELTAS_MOOD, // the vanilla event
									SoundCategory.AMBIENT,                  // or MASTER if you want it louder
									p.getX(), p.getY(), p.getZ(),          // play at the player
									1.0f,                                   // volume
									1.0f,                                   // pitch
									p.getRandom().nextLong()                // seed
								)
							);

                        } catch (Throwable t) {
                            // If mappings ever change, fall back to action bar
                            MutableText msg = Text.literal("Now Entering ")
                                    .append(Text.literal(current.name)
                                            .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
                            p.sendMessage(msg, true);
                        }
                    } else {
                        MutableText msg = Text.literal("Now Entering ")
                            .append(
                                Text.literal(current.name)
                                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))
                            );
                        p.sendMessage(msg, true); // action bar
                    }
                }
            }
        });
    }

    // 1.21.x: env type is CommandManager.RegistrationEnvironment
    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
                                  CommandRegistryAccess access,
                                  CommandManager.RegistrationEnvironment env) {

        dispatcher.register(
            literal("location")
                .requires(src -> true) // allow all players
                .then(literal("create")
                    .then(argument("name", StringArgumentType.string())
                        .then(argument("radius", IntegerArgumentType.integer(1, 500))
                            // name radius
                            .executes(ctx -> {
                                ServerCommandSource src = ctx.getSource();
                                ServerPlayerEntity p = src.getPlayer();
                                if (p == null) return 0;

                                String name = StringArgumentType.getString(ctx, "name");
                                final int r = Math.min(IntegerArgumentType.getInteger(ctx, "radius"), 100);
                                Identifier dim = src.getWorld().getRegistryKey().getValue();
                                BlockPos pos = p.getBlockPos();

                                LocationsState state = LocationsState.get(src.getServer());
                                if (state.find(dim, name) != null) {
                                    src.sendError(Text.literal("Location already exists: " + name));
                                    return 0;
                                }

                                state.add(dim, new Location(name, pos, r, -1, Location.Shape.CIRCLE));
                                src.sendFeedback(() -> Text.literal("Created location '" + name + "' shape=circle @ " + pos.toShortString() + " r=" + r), true);
                                return 1;
                            })
                            // name radius shape
                            .then(argument("shape", StringArgumentType.word())
                                .suggests((ctx, b) ->
                                    net.minecraft.command.CommandSource.suggestMatching(List.of("circle", "square"), b))
                                .executes(ctx -> {
                                    ctx.getSource().sendError(Text.literal("Usage: /location create <name> <radius> <circle|square> <color>"));
                                    return 0;
                                })
                                // name radius shape color
                                .then(argument("color", StringArgumentType.word())
                                    .suggests((ctx, b) ->
                                        net.minecraft.command.CommandSource.suggestMatching(COLOR_NAMES, b))
                                    .executes(ctx -> {
                                        ServerCommandSource src = ctx.getSource();
                                        ServerPlayerEntity p = src.getPlayer();
                                        if (p == null) return 0;

                                        String name = StringArgumentType.getString(ctx, "name");
                                        final int r = Math.min(IntegerArgumentType.getInteger(ctx, "radius"), 100);
                                        String shapeStr = StringArgumentType.getString(ctx, "shape");

                                        Location.Shape shape;
                                        if ("circle".equalsIgnoreCase(shapeStr)) shape = Location.Shape.CIRCLE;
                                        else if ("square".equalsIgnoreCase(shapeStr)) shape = Location.Shape.SQUARE;
                                        else {
                                            src.sendError(Text.literal("Shape must be 'circle' or 'square'."));
                                            return 0;
                                        }

                                        String colorStr = StringArgumentType.getString(ctx, "color");
                                        int rgb = presetColorOrError(colorStr);
                                        if (rgb == -2) {
                                            src.sendError(Text.literal("Unknown color name. Use tab to see options."));
                                            return 0;
                                        }

                                        Identifier dim = src.getWorld().getRegistryKey().getValue();
                                        BlockPos pos = p.getBlockPos();

                                        LocationsState state = LocationsState.get(src.getServer());
                                        if (state.find(dim, name) != null) {
                                            src.sendError(Text.literal("Location already exists: " + name));
                                            return 0;
                                        }

                                        state.add(dim, new Location(name, pos, r, rgb, shape));
                                        src.sendFeedback(() -> Text.literal(
                                            "Created location '" + name + "' shape=" + shapeStr.toLowerCase(Locale.ROOT)
                                            + " color=" + colorStr.toLowerCase(Locale.ROOT)
                                            + " @ " + pos.toShortString() + " r=" + r), true);
                                        return 1;
                                    })
                                )
                            )
                        )
                    )
                )
                .then(literal("remove")
                    .then(argument("name", StringArgumentType.string())
                        .executes(ctx -> {
                            ServerCommandSource src = ctx.getSource();
                            ServerPlayerEntity p = src.getPlayer();
                            if (p == null) return 0;
                            Identifier dim = src.getWorld().getRegistryKey().getValue();
                            String name = StringArgumentType.getString(ctx, "name");

                            LocationsState state = LocationsState.get(src.getServer());
                            if (state.remove(dim, name)) {
                                src.sendFeedback(() -> Text.literal("Removed location '" + name + "'"), true);
                                return 1;
                            } else {
                                src.sendError(Text.literal("No such location: " + name));
                                return 0;
                            }
                        })))
                .then(literal("edit")
                    .then(literal("name")
                        .then(argument("newName", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ServerCommandSource src = ctx.getSource();
                                ServerPlayerEntity p = src.getPlayer();
                                if (p == null) return 0;

                                LocationsState state = LocationsState.get(src.getServer());
                                Identifier dim = src.getWorld().getRegistryKey().getValue();
                                BlockPos pos = p.getBlockPos();

                                Location loc = tryFindLocAt(state, dim, pos);
                                if (loc == null) {
                                    src.sendError(Text.literal("You’re not standing inside a location."));
                                    return 0;
                                }

                                String newName = StringArgumentType.getString(ctx, "newName").trim();
                                if (newName.isEmpty()) {
                                    src.sendError(Text.literal("New name cannot be empty."));
                                    return 0;
                                }
                                if (!newName.equalsIgnoreCase(loc.name) && state.find(dim, newName) != null) {
                                    src.sendError(Text.literal("A location with that name already exists here."));
                                    return 0;
                                }

                                Location updated = new Location(newName, loc.center, loc.radius, loc.colorRgb, loc.shape);
                                state.remove(dim, loc.name);
                                state.add(dim, updated);
                                state.save();

                                src.sendFeedback(() -> Text.literal("Renamed location to '" + newName + "'."), true);
                                return 1;
                            })
                        )
                    )
                    .then(literal("color")
                        .then(argument("color", StringArgumentType.word())
                            .suggests((c, b) ->
                                net.minecraft.command.CommandSource.suggestMatching(COLOR_NAMES, b))
                            .executes(ctx -> {
                                ServerCommandSource src = ctx.getSource();
                                ServerPlayerEntity p = src.getPlayer();
                                if (p == null) return 0;

                                LocationsState state = LocationsState.get(src.getServer());
                                Identifier dim = src.getWorld().getRegistryKey().getValue();
                                BlockPos pos = p.getBlockPos();

                                Location loc = tryFindLocAt(state, dim, pos);
                                if (loc == null) {
                                    src.sendError(Text.literal("You’re not standing inside a location."));
                                    return 0;
                                }

                                String colorStr = StringArgumentType.getString(ctx, "color");
                                int rgb = presetColorOrError(colorStr);
                                if (rgb == -2) {
                                    src.sendError(Text.literal("Unknown color. Use tab to see options."));
                                    return 0;
                                }

                                Location updated = new Location(loc.name, loc.center, loc.radius, rgb, loc.shape);
                                state.remove(dim, loc.name);
                                state.add(dim, updated);
                                state.save();

                                src.sendFeedback(() -> Text.literal("Updated color to " + colorStr.toLowerCase(Locale.ROOT) + "."), true);
                                return 1;
                            })
                        )
                    )
                )
                .then(literal("list")
                    .executes(ctx -> {
                        ServerCommandSource src = ctx.getSource();
                        ServerPlayerEntity p = src.getPlayer();
                        if (p == null) return 0;
                        Identifier dim = src.getWorld().getRegistryKey().getValue();

                        LocationsState state = LocationsState.get(src.getServer());
                        List<Location> locs = state.getLocations(dim);
                        if (locs.isEmpty()) {
                            src.sendFeedback(() -> Text.literal("No locations in this dimension."), false);
                        } else {
                            src.sendFeedback(() -> Text.literal("Locations:"), false);
                            for (Location l : locs) {
                                String extra = (l.colorRgb != -1) ? (" color=#" + String.format("%06X", l.colorRgb)) : "";
                                src.sendFeedback(() -> Text.literal(" • " + l.name + " @ " + l.center.toShortString()
                                        + " r=" + l.radius + " shape=" + l.shape.name().toLowerCase(Locale.ROOT) + extra), false);
                            }
                        }
                        return 1;
                    })
                )
				.then(literal("fancy")
                    .then(argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            ServerCommandSource src = ctx.getSource();
                            ServerPlayerEntity p = src.getPlayer();
                            if (p == null) return 0;

                            LocationsState state = LocationsState.get(src.getServer());
                            Identifier dim = src.getWorld().getRegistryKey().getValue();
                            BlockPos pos = p.getBlockPos();

                            Location loc = tryFindLocAt(state, dim, pos);
                            if (loc == null) {
                                src.sendError(Text.literal("You’re not standing inside a location."));
                                return 0;
                            }

                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            Location updated = new Location(loc.name, loc.center, loc.radius, loc.colorRgb, loc.shape, value);
                            state.remove(dim, loc.name);
                            state.add(dim, updated);
                            state.save();
                            src.sendFeedback(() -> Text.literal("Set fancy=" + value + " for '" + loc.name + "'."), true);
                            return 1;
                        })
                    )
                )
        );
    }

    private static Location findBestMatch(List<Location> locs, BlockPos bp) {
        Location best = null;
        long bestKey = Long.MAX_VALUE; // smaller is better
        for (Location loc : locs) {
            long dx = bp.getX() - loc.center.getX();
            long dz = bp.getZ() - loc.center.getZ();
            boolean inside;
            long key;

            if (loc.shape == Location.Shape.CIRCLE) {
                long d2 = dx * dx + dz * dz;
                long r2 = (long) loc.radius * (long) loc.radius;
                inside = d2 <= r2;
                key = d2;
            } else {
                long adx = Math.abs(dx);
                long adz = Math.abs(dz);
                inside = (adx <= loc.radius) && (adz <= loc.radius);
                key = Math.max(adx, adz);
            }

            if (inside && key < bestKey) {
                bestKey = key;
                best = loc;
            }
        }
        return best;
    }

    private static Location tryFindLocAt(LocationsState state, Identifier dim, BlockPos pos) {
        for (Location l : state.getLocations(dim)) {
            if (contains(l, pos)) return l;
        }
        return null;
    }

    private static boolean contains(Location l, BlockPos p) {
        int dx = p.getX() - l.center.getX();
        int dz = p.getZ() - l.center.getZ();
        int r  = l.radius;
        if (l.shape == Location.Shape.SQUARE) {
            return Math.abs(dx) <= r && Math.abs(dz) <= r;
        } else {
            long d2 = (long)dx * dx + (long)dz * dz;
            long r2 = (long)r * r;
            return d2 <= r2;
        }
    }
}
