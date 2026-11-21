package com.forjastudios.bdacustica.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AcousticCache {

    private static final int MAX_ENTRIES = 512;
    private static final long TTL_TICKS = 3L;

    private final Map<CacheKey, CacheEntry> map = new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public AcousticResult get(Vec3 sourcePos, Vec3 listenerPos, long gameTick) {
        CacheKey key = CacheKey.of(sourcePos, listenerPos);
        CacheEntry entry = map.get(key);
        if (entry != null && (gameTick - entry.tick) <= TTL_TICKS) {
            return entry.result;
        }
        return null;
    }

    public void put(Vec3 sourcePos, Vec3 listenerPos, long gameTick, AcousticResult result) {
        map.put(CacheKey.of(sourcePos, listenerPos), new CacheEntry(gameTick, result));
    }

    public void clear() {
        map.clear();
    }

    private record CacheKey(long srcPacked, long dstPacked) {
        static CacheKey of(Vec3 src, Vec3 dst) {
            return new CacheKey(BlockPos.containing(src).asLong(), BlockPos.containing(dst).asLong());
        }
    }

    private record CacheEntry(long tick, AcousticResult result) {}
}
