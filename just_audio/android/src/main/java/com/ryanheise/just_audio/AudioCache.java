package com.ryanheise.just_audio;

import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import android.content.Context;
import java.io.File;

public class AudioCache {
    private static SimpleCache audioCache;

    public static SimpleCache getInstance(Context context, long maxCache) {
        LeastRecentlyUsedCacheEvictor cacheEvictor = new LeastRecentlyUsedCacheEvictor(maxCache);
        if (audioCache == null) audioCache = new SimpleCache(new File(context.getCacheDir(), "justAudioCache"), cacheEvictor, new ExoDatabaseProvider(context));
        return audioCache;
    }
}