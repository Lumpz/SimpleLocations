package dev.locations;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.util.math.BlockPos;

public final class LocationsState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, List<StoredLocation>>>(){}.getType();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("locations").resolve("locations.json");

    private final Map<String, List<Location>> byDim = new HashMap<>();
    private static final LocationsState INSTANCE = new LocationsState();
    private LocationsState() {}

    public static LocationsState get(MinecraftServer server) {
        return INSTANCE;
    }

    public List<Location> getLocations(Identifier dimensionId) {
        return byDim.computeIfAbsent(dimensionId.toString(), k -> new ArrayList<>());
    }

    public void add(Identifier dimensionId, Location loc) {
        getLocations(dimensionId).add(loc);
    }

    public boolean remove(Identifier dimensionId, String name) {
        List<Location> list = getLocations(dimensionId);
        return list.removeIf(l -> l.name.equalsIgnoreCase(name));
    }

    public Location find(Identifier dimensionId, String name) {
        for (Location l : getLocations(dimensionId)) {
            if (l.name.equalsIgnoreCase(name)) return l;
        }
        return null;
    }

    /* ----------------- Persistence ----------------- */

    public synchronized void load() {
        try {
            if (!Files.exists(FILE)) {
                Files.createDirectories(FILE.getParent());
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                Map<String, List<StoredLocation>> flat = GSON.fromJson(reader, MAP_TYPE);
                byDim.clear();
                if (flat != null) {
                    for (Map.Entry<String, List<StoredLocation>> e : flat.entrySet()) {
                        List<Location> list = new ArrayList<>();
                        for (StoredLocation s : e.getValue()) {
                            Location.Shape shape;
                            try {
                                shape = s.shape == null ? Location.Shape.CIRCLE
                                        : Location.Shape.valueOf(s.shape.toUpperCase(Locale.ROOT));
                            } catch (Exception ex) {
                                shape = Location.Shape.CIRCLE;
                            }
                            list.add(new Location(
                                    s.name,
                                    new BlockPos(s.x, s.y, s.z),
                                    s.radius,
                                    s.color == 0 ? -1 : s.color,
                                    shape,
                                    s.fancy
                            ));
                        }
                        byDim.put(e.getKey(), list);
                    }
                }
            }
        } catch (IOException ioe) {
            System.err.println("[locations] Failed to load config: " + ioe);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Map<String, List<StoredLocation>> flat = new HashMap<>();
            for (Map.Entry<String, List<Location>> e : byDim.entrySet()) {
                List<StoredLocation> out = new ArrayList<>();
                for (Location l : e.getValue()) {
                    StoredLocation s = new StoredLocation();
                    s.name = l.name;
                    s.x = l.center.getX();
                    s.y = l.center.getY();
                    s.z = l.center.getZ();
                    s.radius = l.radius;
                    s.color = l.colorRgb;
                    s.shape = l.shape.name();
                    s.fancy = l.fancy;
                    out.add(s);
                }
                flat.put(e.getKey(), out);
            }
            try (BufferedWriter w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(flat, MAP_TYPE, w);
            }
        } catch (IOException ioe) {
            System.err.println("[locations] Failed to save config: " + ioe);
        }
    }

    private static final class StoredLocation {
        String name;
        int x;
        int y;
        int z;
        int radius;
        int color;     // -1 or 0 means default
        String shape;  // "CIRCLE" or "SQUARE"
        boolean fancy; // center-screen title
    }
}
