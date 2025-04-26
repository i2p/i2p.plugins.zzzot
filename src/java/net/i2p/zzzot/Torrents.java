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
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Integer _interval;
    private final int _udpLifetime;
    private final AtomicInteger _announces = new AtomicInteger();

    /**
     *  @param interval in seconds
     *  @param udpInterval in seconds
     */
    public Torrents(int interval, int udpLifetime) {
        super();
        _hashCache = new SDSCache<InfoHash>(InfoHash.class, InfoHash.LENGTH, CACHE_SIZE);
        _pidCache = new SDSCache<PID>(PID.class, PID.LENGTH, CACHE_SIZE);
        _interval = Integer.valueOf(interval);
        _udpLifetime = udpLifetime;
    }

    public int countPeers() {
        int rv = 0;
        for (Peers p : values()) {
             rv += p.size();
        }
        return rv;
    }

    /**
     *  @return in seconds
     *  @since 0.12.0
     */
    public Integer getInterval() {
        return _interval;
    }

    /**
     *  @return in seconds
     *  @since 0.20.0
     */
    public int getUDPLifetime() {
        return _udpLifetime;
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
     *  This is called for every announce except for event = STOPPED.
     *  Hook it here to keep an announce counter.
     *
     *  @since 0.20.0
     */
    @Override
    public Peers putIfAbsent(InfoHash ih, Peers p) {
        _announces.incrementAndGet();
        return super.putIfAbsent(ih, p);
    }

    /**
     *  Return the number of announces since the last call.
     *  Resets the counter to zero.
     *
     *  @since 0.20.0
     */
    public int getAnnounces() {
        return _announces.getAndSet(0);
    }

    /**
     *  @since 0.12.0
     */
    @Override
    public void clear() {
        super.clear();
        clearCaches();
        _announces.set(0);
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
