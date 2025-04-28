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

import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;

/**
 *  Instantiate this to fire it up
 */
class ZzzOT {

    private final I2PAppContext _context;
    private final Torrents _torrents;
    private final Cleaner _cleaner;
    private final long EXPIRE_TIME;

    private static final String PROP_INTERVAL = "interval";
    private static final String PROP_UDP_LIFETIME = "lifetime";
    private static final long CLEAN_TIME = 4*60*1000;
    private static final long DEST_CACHE_CLEAN_TIME = 3*60*60*1000;
    private static final int DEFAULT_INTERVAL = 27*60;
    private static final int DEFAULT_UDP_LIFETIME = 20*60;
    private static final int MIN_INTERVAL = 15*60;
    private static final int MAX_INTERVAL = 6*60*60;
    private static final int MIN_UDP_LIFETIME = 60;
    private static final int MAX_UDP_LIFETIME = 6*60*60;

    ZzzOT(I2PAppContext ctx, Properties p) {
        String intv = p.getProperty(PROP_INTERVAL);
        int interval = DEFAULT_INTERVAL;
        if (intv != null) {
            try {
                interval = Integer.parseInt(intv);
                if (interval < MIN_INTERVAL)
                    interval = MIN_INTERVAL;
                else if (interval > MAX_INTERVAL)
                    interval = MAX_INTERVAL;
            } catch (NumberFormatException nfe) {}
        }
        intv = p.getProperty(PROP_UDP_LIFETIME);
        int lifetime = DEFAULT_UDP_LIFETIME;
        if (intv != null) {
            try {
                lifetime = Integer.parseInt(intv);
                if (lifetime < MIN_UDP_LIFETIME)
                    interval = MIN_UDP_LIFETIME;
                else if (interval > MAX_UDP_LIFETIME)
                    interval = MAX_UDP_LIFETIME;
            } catch (NumberFormatException nfe) {}
        }
        _torrents = new Torrents(interval, lifetime);
        EXPIRE_TIME = 1000 * (interval + interval / 2);
        _cleaner  = new Cleaner(ctx);
        _context = ctx;
    }

    Torrents getTorrents() {
        return _torrents;
    }

    void start() {
        _cleaner.forceReschedule(CLEAN_TIME);
        long[] r = new long[] { 5*60*1000 };
        _context.statManager().createRequiredRateStat("plugin.zzzot.announces", "Announces per minute", "Plugins", r);
        _context.statManager().createRequiredRateStat("plugin.zzzot.peers", "Number of peers", "Plugins", r);
        _context.statManager().createRequiredRateStat("plugin.zzzot.torrents", "Number of torrents", "Plugins", r);
    }

    void stop() {
        _cleaner.cancel();
        _torrents.clear();
        _context.statManager().removeRateStat("plugin.zzzot.announces");
        _context.statManager().removeRateStat("plugin.zzzot.peers");
        _context.statManager().removeRateStat("plugin.zzzot.torrents");
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {

        private final AtomicInteger _runCount = new AtomicInteger();

        /** must schedule later */
        public Cleaner(I2PAppContext ctx) {
            super(ctx.simpleTimer2());
        }

        public void timeReached() {
            long now = System.currentTimeMillis();
            int peers = 0;
            for (Iterator<Peers> iter = _torrents.values().iterator(); iter.hasNext(); ) {
                Peers p = iter.next();
                int recent = 0;
                for (Iterator<Peer> iterp = p.values().iterator(); iterp.hasNext(); ) {
                     Peer peer = iterp.next();
                     if (peer.lastSeen() < now - EXPIRE_TIME)
                         iterp.remove();
                     else
                         recent++;
                }
                if (recent <= 0)
                    iter.remove();
                else
                    peers += recent;
            }
            _context.statManager().addRateData("plugin.zzzot.announces",  _torrents.getAnnounces() / (CLEAN_TIME / (60*1000L)));
            _context.statManager().addRateData("plugin.zzzot.peers",  peers);
            _context.statManager().addRateData("plugin.zzzot.torrents",  _torrents.size());
            schedule(CLEAN_TIME);
        }
    }
}
