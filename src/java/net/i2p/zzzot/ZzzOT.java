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

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;

/**
 *  Instantiate this to fire it up
 */
class ZzzOT {

    private final Torrents _torrents;
    private final Cleaner _cleaner;
    private final long EXPIRE_TIME;

    private static final String PROP_INTERVAL = "interval";
    private static final long CLEAN_TIME = 4*60*1000;
    private static final int DEFAULT_INTERVAL = 27*60;
    private static final int MIN_INTERVAL = 15*60;
    private static final int MAX_INTERVAL = 6*60*60;

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
        _torrents = new Torrents(interval);
        EXPIRE_TIME = 1000 * (interval + interval / 2);
        _cleaner  = new Cleaner(ctx);
    }

    Torrents getTorrents() {
        return _torrents;
    }

    void start() {
        _cleaner.forceReschedule(CLEAN_TIME);
    }

    void stop() {
        _cleaner.cancel();
        _torrents.clear();
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {

        /** must schedule later */
        public Cleaner(I2PAppContext ctx) {
            super(ctx.simpleTimer2());
        }

        public void timeReached() {
            long now = System.currentTimeMillis();
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
            }
            schedule(CLEAN_TIME);
        }
    }
}
