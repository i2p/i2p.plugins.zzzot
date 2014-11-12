package net.i2p.zzzot;
/*
 *  Copyright 2010 zzz (zzz@mail.i2p)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.data.SDSCache;
import net.i2p.util.VersionComparator;


/**
 *  All the torrents
 */
public class Torrents extends ConcurrentHashMap<InfoHash, Peers> {

    private static final int CACHE_SIZE = 2048;
    private final SDSCache<InfoHash> _hashCache;
    private final SDSCache<PID> _pidCache;


    public Torrents() {
        super();
        _hashCache = new SDSCache<InfoHash>(InfoHash.class, InfoHash.LENGTH, CACHE_SIZE);
        _pidCache = new SDSCache<PID>(PID.class, PID.LENGTH, CACHE_SIZE);
    }

    public int countPeers() {
        int rv = 0;
        for (Peers p : values()) {
             rv += p.size();
        }
        return rv;
    }

    /**
     * Pull from cache or return new
     *
     * @throws IllegalArgumentException if data is not the correct number of bytes
     * @since 0.12.0
     */
    public InfoHash createInfoHash(String data) throws IllegalArgumentException {
        byte[] d = DataHelper.getASCII(data);
        if (d.length != InfoHash.LENGTH)
            throw new IllegalArgumentException("bad infohash length " + d.length);
        return _hashCache.get(d);
    }

    /**
     * Pull from cache or return new
     *
     * @throws IllegalArgumentException if data is not the correct number of bytes
     * @since 0.12.0
     */
    public PID createPID(String data) throws IllegalArgumentException {
        byte[] d = DataHelper.getASCII(data);
        if (d.length != PID.LENGTH)
            throw new IllegalArgumentException("bad peer id length " + d.length);
        return _pidCache.get(d);
    }

    /**
     *  @since 0.12.0
     */
    @Override
    public void clear() {
        super.clear();
        clearCaches();
    }

    /**
     *  @since 0.12.0
     */
    private void clearCaches() {
        // not available until 0.9.17
        if (VersionComparator.comp(CoreVersion.VERSION, "0.9.17") >= 0) {
            try {
                _hashCache.clear();
                _pidCache.clear();
            } catch (Throwable t) {}
        }
    }
}
