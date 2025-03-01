package dev.dubhe.curtain.utils;

import dev.dubhe.curtain.Curtain;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static net.minecraft.world.entity.MobCategory.AMBIENT;
import static net.minecraft.world.entity.MobCategory.CREATURE;
import static net.minecraft.world.entity.MobCategory.MONSTER;
import static net.minecraft.world.entity.MobCategory.WATER_AMBIENT;
import static net.minecraft.world.entity.MobCategory.WATER_CREATURE;

public class SpawnReporter {
    public static boolean mock_spawns = false;

    public static Long track_spawns = 0L;
    public static final HashMap<ResourceKey<Level>, Integer> chunkCounts = new HashMap<>();

    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, Object2LongMap<EntityType<?>>> spawn_stats = new HashMap<>();
    public static double mobcap_exponent = 0.0D;

    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, Long> spawn_attempts = new HashMap<>();
    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, Long> overall_spawn_ticks = new HashMap<>();
    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, Long> spawn_ticks_full = new HashMap<>();
    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, Long> spawn_ticks_fail = new HashMap<>();
    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, Long> spawn_ticks_succ = new HashMap<>();
    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, Long> spawn_ticks_spawns = new HashMap<>();
    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, Long> spawn_cap_count = new HashMap<>();
    public static final HashMap<Pair<ResourceKey<Level>, MobCategory>, EvictingQueue<Pair<EntityType<?>, BlockPos>>> spawned_mobs = new HashMap<>();
    public static final HashMap<MobCategory, Integer> spawn_tries = new HashMap<>();
    public static BlockPos lower_spawning_limit = null;
    public static BlockPos upper_spawning_limit = null;
    // in case game gets each thread for each world - these need to belong to workd.
    public static HashMap<MobCategory, Long> local_spawns = null; // per world
    public static HashSet<MobCategory> first_chunk_marker = null;

    static {
        reset_spawn_stats(null, true);
    }

    public static void registerSpawn(Mob mob, MobCategory cat, BlockPos pos) {
        if (lower_spawning_limit != null) {
            if (!((lower_spawning_limit.getX() <= pos.getX() && pos.getX() <= upper_spawning_limit.getX()) &&
                    (lower_spawning_limit.getY() <= pos.getY() && pos.getY() <= upper_spawning_limit.getY()) &&
                    (lower_spawning_limit.getZ() <= pos.getZ() && pos.getZ() <= upper_spawning_limit.getZ())
            )) {
                return;
            }
        }
        Pair<ResourceKey<Level>, MobCategory> key = Pair.of(mob.level().dimension(), cat);
        long count = spawn_stats.get(key).getOrDefault(mob.getType(), 0L);
        spawn_stats.get(key).put(mob.getType(), count + 1);
        spawned_mobs.get(key).put(Pair.of(mob.getType(), pos));
        if (!local_spawns.containsKey(cat)) {
            Curtain.LOGGER.error("Rogue spawn detected for category " + cat.getName() + " for mob " +
                    mob.getType().getDescription().getString() +
                    ". If you see this message let curtain peeps know about it on github issues.");
            local_spawns.put(cat, 0L);
        }
        local_spawns.put(cat, local_spawns.get(cat) + 1);
    }

    public static final int MAGIC_NUMBER = (int) Math.pow(17.0D, 2.0D);

    public static List<Component> printMobcapsForDimension(ServerLevel world, boolean multiline) {
        ResourceKey<Level> dim = world.dimension();
        String name = dim.location().getPath();
        List<Component> lst = new ArrayList<>();
        if (multiline)
            lst.add(Messenger.s(String.format("Mobcaps for %s:", name)));
        NaturalSpawner.SpawnState lastSpawner = world.getChunkSource().getLastSpawnState();
        Object2IntMap<MobCategory> dimCounts = lastSpawner.getMobCategoryCounts();
        int chunkcount = chunkCounts.getOrDefault(dim, -1);
        if (dimCounts == null || chunkcount < 0) {
            lst.add(Messenger.c("g   --UNAVAILABLE--"));
            return lst;
        }

        List<String> shortCodes = new ArrayList<>();
        for (MobCategory enumcreaturetype : MobCategory.values()) {
            int cur = dimCounts.getOrDefault(enumcreaturetype, -1);
            int max = (int) (chunkcount * ((double) enumcreaturetype.getMaxInstancesPerChunk() / MAGIC_NUMBER)); // from ServerChunkManager.CHUNKS_ELIGIBLE_FOR_SPAWNING
            String color = Messenger.heatmap_color(cur, max);
            String mobColor = Messenger.creatureTypeColor(enumcreaturetype);
            if (multiline) {
                int rounds = spawn_tries.get(enumcreaturetype);
                lst.add(Messenger.c(String.format("w   %s: ", enumcreaturetype.getName()),
                        (cur < 0) ? "g -" : (color + " " + cur), "g  / ", mobColor + " " + max,
                        (rounds == 1) ? "w " : String.format("gi  (%d rounds/tick)", spawn_tries.get(enumcreaturetype))
                ));
            } else {
                shortCodes.add(color + " " + ((cur < 0) ? "-" : cur));
                shortCodes.add("g /");
                shortCodes.add(mobColor + " " + max);
                shortCodes.add("g ,");
            }
        }
        if (!multiline) {
            if (shortCodes.size() > 0) {
                shortCodes.remove(shortCodes.size() - 1);
                lst.add(Messenger.c(shortCodes.toArray(new Object[0])));
            } else {
                lst.add(Messenger.c("g   --UNAVAILABLE--"));
            }

        }
        return lst;
    }

    public static List<Component> recent_spawns(Level world, MobCategory creature_type) {
        List<Component> lst = new ArrayList<>();
        if ((track_spawns == 0L)) {
            lst.add(Messenger.s("Spawn tracking not started"));
            return lst;
        }
        String type_code = creature_type.getName();

        lst.add(Messenger.s(String.format("Recent %s spawns:", type_code)));
        for (Pair<EntityType<?>, BlockPos> pair : spawned_mobs.get(Pair.of(world.dimension(), creature_type)).keySet()) // getDImTYpe
        {
            lst.add(Messenger.c(
                    "w  - ",
                    Messenger.tp("wb", pair.getRight()),
                    String.format("w : %s", pair.getLeft().getDescription().getString())
            ));
        }

        if (lst.size() == 1) {
            lst.add(Messenger.s(" - Nothing spawned yet, sorry."));
        }
        return lst;

    }

    public static List<Component> show_mobcaps(BlockPos pos, ServerLevel worldIn) {
        DyeColor under = WoolTool.getWoolColorAtPosition(worldIn, pos.below());
        if (under == null) {
            if (track_spawns > 0L) {
                return tracking_report(worldIn);
            } else {
                return printMobcapsForDimension(worldIn, true);
            }
        }
        MobCategory creature_type = get_type_code_from_wool_code(under);
        if (creature_type != null) {
            if (track_spawns > 0L) {
                return recent_spawns(worldIn, creature_type);
            } else {
                return printEntitiesByType(creature_type, worldIn, true);

            }

        }
        if (track_spawns > 0L) {
            return tracking_report(worldIn);
        } else {
            return printMobcapsForDimension(worldIn, true);
        }

    }

    public static MobCategory get_type_code_from_wool_code(DyeColor color) {
        return switch (color) {
            case RED -> MONSTER;
            case GREEN -> CREATURE;
            case BLUE -> WATER_CREATURE;
            case BROWN -> AMBIENT;
            case CYAN -> WATER_AMBIENT;
            default -> null;
        };
    }

    public static List<Component> printEntitiesByType(MobCategory cat, Level worldIn, boolean all) //Class<?> entityType)
    {
        List<Component> lst = new ArrayList<>();
        lst.add(Messenger.s(String.format("Loaded entities for %s class:", cat)));
        for (Entity entity : ((ServerLevel) worldIn).getEntities(EntityTypeTest.forClass(Entity.class), (e) -> e.getType().getCategory() == cat)) {
            boolean persistent = entity instanceof Mob && (((Mob) entity).isPersistenceRequired() || ((Mob) entity).requiresCustomPersistence());
            if (!all && persistent)
                continue;

            EntityType<?> type = entity.getType();
            BlockPos pos = entity.blockPosition();
            lst.add(Messenger.c(
                    "w  - ",
                    Messenger.tp(persistent ? "gb" : "wb", pos),
                    String.format(persistent ? "g : %s" : "w : %s", type.getDescription().getString())
            ));

        }
        if (lst.size() == 1) {
            lst.add(Messenger.s(" - Empty."));
        }
        return lst;
    }

    public static void initialize_mocking() {
        mock_spawns = true;

    }

    public static void stop_mocking() {
        mock_spawns = false;
    }

    public static void reset_spawn_stats(MinecraftServer server, boolean full) {

        spawn_stats.clear();
        spawned_mobs.clear();
        for (MobCategory enumcreaturetype : MobCategory.values()) {
            if (full) {
                spawn_tries.put(enumcreaturetype, 1);
            }
            if (server != null) for (ResourceKey<Level> dim : server.levelKeys()) {
                Pair<ResourceKey<Level>, MobCategory> key = Pair.of(dim, enumcreaturetype);
                overall_spawn_ticks.put(key, 0L);
                spawn_attempts.put(key, 0L);
                spawn_ticks_full.put(key, 0L);
                spawn_ticks_fail.put(key, 0L);
                spawn_ticks_succ.put(key, 0L);
                spawn_ticks_spawns.put(key, 0L);
                spawn_cap_count.put(key, 0L);
                spawn_stats.put(key, new Object2LongOpenHashMap<>());
                spawned_mobs.put(key, new EvictingQueue<>());
            }
        }
        track_spawns = 0L;
    }

    private static String getWorldCode(ResourceKey<Level> world) {
        if (world == Level.OVERWORLD) return "";
        return "(" + world.location().getPath().toUpperCase(Locale.ROOT).replace("THE_", "").charAt(0) + ")";
    }

    public static List<Component> tracking_report(Level worldIn) {

        List<Component> report = new ArrayList<>();
        if (track_spawns == 0L) {
            report.add(Messenger.c(
                    "w Spawn tracking disabled, type '",
                    "wi /spawn tracking start", "/spawn tracking start",
                    "w ' to enable"));
            return report;
        }
        long duration = worldIn.getServer().getTickCount() - track_spawns;
        report.add(Messenger.c("bw --------------------"));
        String simulated = mock_spawns ? "[SIMULATED] " : "";
        String location = (lower_spawning_limit != null) ? String.format("[in (%d, %d, %d)x(%d, %d, %d)]",
                lower_spawning_limit.getX(), lower_spawning_limit.getY(), lower_spawning_limit.getZ(),
                upper_spawning_limit.getX(), upper_spawning_limit.getY(), upper_spawning_limit.getZ()) : "";
        report.add(Messenger.s(String.format("%sSpawn statistics %s: for %.1f min", simulated, location, (duration / 72000.0) * 60)));
        for (MobCategory enumcreaturetype : MobCategory.values()) {
            //String type_code = String.format("%s", enumcreaturetype);
            for (ResourceKey<Level> dim : worldIn.getServer().levelKeys()) //String world_code: new String[] {"", " (N)", " (E)"})
            {
                Pair<ResourceKey<Level>, MobCategory> code = Pair.of(dim, enumcreaturetype);
                if (spawn_ticks_spawns.get(code) > 0L) {
                    double hours = overall_spawn_ticks.get(code) / 72000.0;
                    report.add(Messenger.s(String.format(" > %s%s (%.1f min), %.1f m/t, %%{%.1fF %.1f- %.1f+}; %.2f s/att",
                            enumcreaturetype.getName().substring(0, 3), getWorldCode(dim),
                            60 * hours,
                            (1.0D * spawn_cap_count.get(code)) / spawn_attempts.get(code),
                            (100.0D * spawn_ticks_full.get(code)) / spawn_attempts.get(code),
                            (100.0D * spawn_ticks_fail.get(code)) / spawn_attempts.get(code),
                            (100.0D * spawn_ticks_succ.get(code)) / spawn_attempts.get(code),
                            (1.0D * spawn_ticks_spawns.get(code)) / (spawn_ticks_fail.get(code) + spawn_ticks_succ.get(code))
                    )));
                    for (EntityType<?> type : spawn_stats.get(code).keySet()) {
                        report.add(Messenger.s(String.format("   - %s: %d spawns, %d per hour",
                                type.getDescription().getString(),
                                spawn_stats.get(code).getLong(type),
                                (72000 * spawn_stats.get(code).getLong(type) / duration))));
                    }
                }
            }
        }
        return report;
    }


    public static void killEntity(LivingEntity entity) {
        if (entity.isPassenger()) {
            entity.getVehicle().discard();
        }
        if (entity.isVehicle()) {
            for (Entity e : entity.getPassengers()) {
                e.discard();
            }
        }
        if (entity instanceof Ocelot) {
            for (Entity e : entity.getCommandSenderWorld().getEntities(entity, entity.getBoundingBox())) {
                e.discard();
            }
        }
        entity.discard();
    }

    private static List<MobSpawnSettings.SpawnerData> getSpawnEntries(ServerLevel serverLevel, StructureManager structureManager, ChunkGenerator chunkGenerator, MobCategory mobCategory, BlockPos blockPos, @Nullable Holder<Biome> holder) {
        return NaturalSpawner.isInNetherFortressBounds(blockPos, serverLevel, mobCategory, structureManager) ? NetherFortressStructure.FORTRESS_ENEMIES.unwrap() : chunkGenerator.getMobsAt(holder != null ? holder : serverLevel.getBiome(blockPos), structureManager, mobCategory, blockPos).unwrap();
    }

}
