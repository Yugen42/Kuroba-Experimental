/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.core.manager;

import android.util.LruCache;

import androidx.annotation.NonNull;

import com.github.adamantcheese.chan.core.site.loader.ChanThreadLoader;
import com.github.adamantcheese.chan.core.site.loader.ChanThreadLoader.ChanLoaderCallback;
import com.github.adamantcheese.chan.utils.BackgroundUtils;
import com.github.adamantcheese.chan.utils.Logger;
import com.github.adamantcheese.model.data.descriptor.ChanDescriptor;

import java.util.HashMap;
import java.util.Map;

import static com.github.adamantcheese.chan.Chan.inject;

/**
 * ChanLoaderManager is a manager/factory for ChanLoaders. ChanLoaders for threads are cached.
 * Only one instance of this class should exist, and is dependency injected; as a result,
 * the methods inside are synchronized.
 *
 * <p>Each reference to a loader is a {@link ChanLoaderCallback}, these references can be obtained
 * with {@link #obtain(ChanDescriptor, ChanLoaderCallback)} and released with
 * {@link #release(ChanThreadLoader, ChanLoaderCallback)}. A loader is only cached if it has no more
 * listeners, therefore you can call {@link #obtain(ChanDescriptor, ChanLoaderCallback)} as many
 * times as you want as long as you call release an equal amount of times.
 */
public class ChanLoaderManager {
    private static final String TAG = "ChanLoaderManager";
    public static final int THREAD_LOADERS_CACHE_SIZE = 25;

    // map between a loadable and a chan loader instance for it, currently in use
    private Map<ChanDescriptor, ChanThreadLoader> threadLoaders = new HashMap<>();
    // chan loader cache for released loadables
    private LruCache<ChanDescriptor, ChanThreadLoader> threadLoadersCache =
            new LruCache<>(THREAD_LOADERS_CACHE_SIZE);

    public ChanLoaderManager() {
        inject(this);
    }

    @NonNull
    public synchronized ChanThreadLoader obtain(
            @NonNull ChanDescriptor chanDescriptor,
            ChanLoaderCallback listener
    ) {
        BackgroundUtils.ensureMainThread();

        ChanThreadLoader chanLoader;
        if (chanDescriptor.isThreadDescriptor()) {
            chanLoader = threadLoaders.get(chanDescriptor);
            if (chanLoader == null) {
                chanLoader = threadLoadersCache.get(chanDescriptor);
                if (chanLoader != null) {
                    threadLoadersCache.remove(chanDescriptor);
                    threadLoaders.put(chanDescriptor, chanLoader);
                }
            }

            if (chanLoader == null) {
                chanLoader = new ChanThreadLoader(chanDescriptor);
                threadLoaders.put(chanDescriptor, chanLoader);
            }
        } else {
            chanLoader = new ChanThreadLoader(chanDescriptor);
        }

        chanLoader.addListener(listener);

        return chanLoader;
    }

    public synchronized void release(
            @NonNull ChanThreadLoader chanLoader,
            ChanLoaderCallback listener
    ) {
        BackgroundUtils.ensureMainThread();

        ChanDescriptor chanDescriptor = chanLoader.getChanDescriptor();
        if (chanDescriptor.isThreadDescriptor()) {
            ChanThreadLoader foundChanLoader = threadLoaders.get(chanDescriptor);
            if (foundChanLoader == null) {
                Logger.wtf(TAG, "Loader doesn't exist.");
                throw new IllegalStateException("The released loader does not exist");
            }

            if (chanLoader.removeListener(listener)) {
                threadLoaders.remove(chanDescriptor);
                threadLoadersCache.put(chanDescriptor, chanLoader);
            }
        } else {
            chanLoader.removeListener(listener);
        }
    }
}
