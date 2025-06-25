package net.i2p.zzzot;
/*
 *  Copyright 2022 zzz (zzz@mail.i2p)
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.datagram.Datagram2;
import net.i2p.client.datagram.Datagram3;
import net.i2p.crypto.SipHashInline;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.util.I2PAppThread;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  Hook into the session and handle UDP announces
 *  Ref: Proposal 160, BEP 15
 *
 *  @since 0.19.0
 */
public class UDPHandler implements I2PSessionMuxedListener {

    private final I2PAppContext _context;
    private final Log _log;
    private final I2PTunnel _tunnel;
    private final ZzzOT _zzzot;
    private final Cleaner _cleaner;
    private final long sipk0, sipk1;
    private final Map<Hash, Destination> _destCache;
    private final AtomicInteger _announces = new AtomicInteger();
    private volatile boolean _running;
    private ThreadPoolExecutor _executor;
    /** how long to wait before dropping an idle thread */
    private static final long HANDLER_KEEPALIVE_MS = 2*60*1000;

    // The listen port.
    public final int PORT;
    private static final long MAGIC = 0x41727101980L;
    private static final int ACTION_CONNECT = 0;
    private static final int ACTION_ANNOUNCE = 1;
    private static final int ACTION_SCRAPE = 2;
    private static final int ACTION_ERROR = 3;
    private static final int MAX_RESPONSES = 25;
    private static final int EVENT_NONE = 0;
    private static final int EVENT_COMPLETED = 1;
    private static final int EVENT_STARTED = 2;
    private static final int EVENT_STOPPED = 3;
    // keep it short, we should have the leaseset,
    // if a new ratchet session was created
    private final long LOOKUP_TIMEOUT = 2000;
    private final long CLEAN_TIME;
    private final long STAT_TIME = 2*60*1000;
    private static final byte[] INVALID = DataHelper.getUTF8("Invalid connection ID");
    private static final byte[] PROTOCOL = DataHelper.getUTF8("Bad protocol");
    private static final byte[] SCRAPE = DataHelper.getUTF8("Scrape unsupported");

    public UDPHandler(I2PAppContext ctx, I2PTunnel tunnel, ZzzOT zzzot, int port) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPHandler.class);
        _tunnel = tunnel;
        _zzzot = zzzot;
        CLEAN_TIME = (zzzot.getTorrents().getUDPLifetime() + 60) * 1000;
        PORT = port;
        _cleaner = new Cleaner();
        sipk0 = ctx.random().nextLong();
        sipk1 = ctx.random().nextLong();
        // the highest-traffic zzzot is running about 3000 announces/minute,
        // give us enough to respond to the first announce after the connection
        _destCache = new LHMCache<Hash, Destination>(1024);
    }

    public synchronized void start() {
        _running = true;
        _executor = new CustomThreadPoolExecutor();
        _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        (new I2PAppThread(new Waiter(), "ZzzOT UDP startup", true)).start();
        long[] r = new long[] { 5*60*1000 };
        _context.statManager().createRequiredRateStat("plugin.zzzot.announces.udp", "UDP announces per minute", "Plugins", r);
    }

    /**
     *  @since 0.20.0
     */
    public synchronized void stop() {
        _running = false;
        _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        _executor.shutdownNow();
        _executor = null;
        _cleaner.cancel();
        _context.statManager().removeRateStat("plugin.zzzot.announces.udp");
        _announces.set(0);
    }

    private class Waiter implements Runnable {
        public void run() {
            while (_running) {
                // requires I2P 0.9.53 (1.7.0)
                List<I2PSession> sessions = _tunnel.getSessions();
                if (sessions.isEmpty()) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    continue;
                }
                I2PSession session = sessions.get(0);
                session.addMuxedSessionListener(UDPHandler.this, I2PSession.PROTO_DATAGRAM2, PORT);
                session.addMuxedSessionListener(UDPHandler.this, I2PSession.PROTO_DATAGRAM3, PORT);
                _cleaner.schedule(STAT_TIME);
                if (_log.shouldInfo())
                    _log.info("got session");
                break;
            }
        }
    }

    /// begin listener methods ///

    public void messageAvailable(I2PSession sess, int id, long size) {
        throw new IllegalStateException("muxed");
    }

    /**
     *  @since 0.9.53
     */
    public void messageAvailable(I2PSession session, int id, long size, int proto, int fromPort, int toPort) {
        if (_log.shouldDebug())
            _log.debug("Got " + size + " bytes, proto: " + proto + " from port: " + fromPort + " to port: " + toPort);
        try {
            // receive message
            byte[] msg = session.receiveMessage(id);
            if (proto == I2PSession.PROTO_DATAGRAM2) {
                // load datagram into it
                Datagram2 dg = Datagram2.load(_context, session, msg);
                handle(session, dg.getSender(), null, fromPort, dg.getPayload());
            } else if (proto == I2PSession.PROTO_DATAGRAM3) {
                Datagram3 dg = Datagram3.load(_context, session, msg);
                handle(session, null, dg.getSender(), fromPort, dg.getPayload());
            } else {
                if (_log.shouldWarn())
                    _log.warn("dropping message with unknown protocol " + proto);
            }
        } catch (Exception e) {
            if (_log.shouldWarn())
                _log.warn("error receiving datagram", e);
        }
    }

    public void reportAbuse(I2PSession arg0, int arg1) {}

    public void disconnected(I2PSession arg0) {
        _cleaner.cancel();
    }

    public void errorOccurred(I2PSession arg0, String arg1, Throwable arg2) {
        _log.error(arg1, arg2);
    }

    /// end listener methods ///
        
    /**
     *  One of from or fromHash non-null
     *  @param from non-null for connect request
     *  @param fromHash non-null for announce request
     */
    private void handle(I2PSession session, Destination from, Hash fromHash, int fromPort, byte[] data) {
        int sz = data.length;
        if (sz < 16) {
            if (_log.shouldWarn())
                _log.warn("dropping short msg length " + sz);
            return;
        }
        long connID = DataHelper.fromLong8(data, 0);
        int action = (int) DataHelper.fromLong(data, 8, 4);
        if (action == ACTION_CONNECT) {
            if (connID != MAGIC) {
                if (_log.shouldWarn())
                    _log.warn("dropping bad connect magic " + connID);
                return;
            }
            if (from == null) {
                if (_log.shouldWarn())
                    _log.warn("dropping dg3 connect");
                int transID = (int) DataHelper.fromLong(data, 12, 4);
                sendError(session, fromHash, fromPort, transID, PROTOCOL);
                return;
            }
            handleConnect(session, from, fromPort, data);
        } else if (action == ACTION_ANNOUNCE) {
            if (fromHash == null) {
                if (_log.shouldWarn())
                    _log.warn("dropping dg2 announce");
                int transID = (int) DataHelper.fromLong(data, 12, 4);
                sendError(session, from, fromPort, transID, PROTOCOL);
                return;
            }
            handleAnnounce(session, connID, fromHash, fromPort, data);
        } else if (action == ACTION_SCRAPE) {
            if (_log.shouldWarn())
                _log.warn("got unsupported scrape");
            int transID = (int) DataHelper.fromLong(data, 12, 4);
            if (from != null)
                sendError(session, from, fromPort, transID, SCRAPE);
            else
                sendError(session, fromHash, fromPort, transID, SCRAPE);
        } else {
            if (_log.shouldWarn())
                _log.warn("dropping bad action " + action);
            // TODO send error?
        }
    }

    /**
     *  @param from non-null
     */
    private void handleConnect(I2PSession session, Destination from, int fromPort, byte[] data) {
        int transID = (int) DataHelper.fromLong(data, 12, 4);
        long connID = generateCID(from.calculateHash());
        byte[] resp = new byte[18];
        DataHelper.toLong(resp, 4, 4, transID);
        DataHelper.toLong8(resp, 8, connID);
        // Addition to BEP 15
        DataHelper.toLong(resp, 16, 2, _zzzot.getTorrents().getUDPLifetime());
        try {
            session.sendMessage(from, resp, I2PSession.PROTO_DATAGRAM_RAW, PORT, fromPort);
            if (_log.shouldDebug())
                _log.debug("sent connect reply with conn ID " + connID + " to " + from.toBase32());
            synchronized(_destCache) {
                _destCache.put(from.calculateHash(), from);
            }
        } catch (I2PSessionException ise) {
            if (_log.shouldWarn())
                _log.warn("error sending connect reply", ise);
        }
    }

    /**
     *  @param from may be null
     */
    private void handleAnnounce(I2PSession session, long connID, Hash fromHash, int fromPort, byte[] data) {
        int sz = data.length;
        if (sz < 96) {
            if (_log.shouldWarn())
                _log.warn("dropping short announce length " + sz);
            return;
        }
        int transID = (int) DataHelper.fromLong(data, 12, 4);
        boolean ok = validateCID(fromHash, connID);
        if (!ok) {
            if (_log.shouldWarn())
                _log.warn("conn ID invalid: " + connID);
            sendError(session, fromHash, fromPort, transID, INVALID);
            return;
        }

        // parse packet
        byte[] bih = new byte[InfoHash.LENGTH];
        System.arraycopy(data, 16, bih, 0, InfoHash.LENGTH);
        InfoHash ih = new InfoHash(bih);
        byte[] bpid = new byte[PID.LENGTH];
        System.arraycopy(data, 36, bpid, 0, PID.LENGTH);
        PID pid = new PID(bpid);
        // ignored
        //long dl = DataHelper.fromLong8(data, 56);
        //long ul = DataHelper.fromLong8(data, 72);
        int event = (int) DataHelper.fromLong(data, 80, 4);
        long left = event == EVENT_COMPLETED ? 0 : DataHelper.fromLong8(data, 64);
        // ignored
        //long ip = DataHelper.fromLong(data, 84, 4);
        //long key = DataHelper.fromLong(data, 88, 4);
        // Note: BEP 15 spec default is -1 but we read as a positive long
        long want = DataHelper.fromLong(data, 92, 4);
        if (want > MAX_RESPONSES)
            want = MAX_RESPONSES;
        // ignored
        //int port = (int) DataHelper.fromLong(data, 96, 2);

        Torrents torrents = _zzzot.getTorrents();
        Peers peers = torrents.get(ih);
        if (peers == null && event != EVENT_STOPPED) {
            _announces.incrementAndGet();
            peers = new Peers();
            Peers p2 = torrents.putIfAbsent(ih, peers);
            if (p2 != null)
                peers = p2;
        }
        int size;
        int seeds;
        List<Peer> peerlist;
        if (event == EVENT_STOPPED) {
            if (peers != null)
                peers.remove(pid);
            peerlist = null;
            size = 0;
            seeds = 0;
        } else {
            Peer p = peers.get(pid);
            if (p == null) {
                p = new Peer(pid.getData(), fromHash);
                Peer p2 = peers.putIfAbsent(pid, p);
                if (p2 != null)
                    p = p2;
            }
            p.setLeft(left);

            size = peers.size();
            seeds = peers.countSeeds();
            if (want <= 0 || event == EVENT_STOPPED) {
                peerlist = null;
            } else {
                peerlist = new ArrayList<Peer>(peers.values());
                peerlist.remove(p);   // them
                if (want < size - 1) {
                    if (size > 150) {
                        // If size is huge, use random iterator for efficiency
                        List<Peer> rv = new ArrayList<Peer>(size);
                        for (RandomIterator<Peer> iter = new RandomIterator<Peer>(peerlist); iter.hasNext(); ) {
                            rv.add(iter.next());
                        }
                        peerlist = rv;
                    } else {
                        Collections.shuffle(peerlist, _context.random());
                        peerlist = peerlist.subList(0, (int) want);
                    }
                }
            }
        }

        int count = peerlist != null ? peerlist.size() : 0;
        byte[] resp = new byte[20 + (32 * count)];
        resp[3] = (byte) ACTION_ANNOUNCE;
        DataHelper.toLong(resp, 4, 4, transID);
        DataHelper.toLong(resp, 8, 4, torrents.getInterval());
        DataHelper.toLong(resp, 12, 4, size - seeds);
        DataHelper.toLong(resp, 16, 4, seeds);
        if (peerlist != null) {
            for (int i = 0; i < count; i++) {
                System.arraycopy(peerlist.get(i).getHashBytes(), 0, resp, 20 + (i * 32), 32);
            }
        }

        Destination from = lookupCache(fromHash);
        if (from == null) {
            try {
                _executor.execute(new Lookup(session, fromHash, fromPort, resp));
            } catch (RejectedExecutionException ree) {
                if (_log.shouldWarn())
                    _log.warn("error sending announce reply - thread pool full");
            }
            return;
        }

        try {
            session.sendMessage(from, resp, I2PSession.PROTO_DATAGRAM_RAW, PORT, fromPort);
            if (_log.shouldDebug())
                _log.debug("sent announce reply to " + from);
        } catch (I2PSessionException ise) {
            if (_log.shouldWarn())
                _log.warn("error sending announce reply", ise);
        }
    }

    /**
     *  @param from non-null
     *  @param msg non-null
     */
    private void sendError(I2PSession session, Hash toHash, int toPort, long transID, byte[] msg) {
        Destination to = lookupCache(toHash);
        if (to == null) {
            if (_log.shouldInfo())
                _log.info("don't have cached dest to send error to " + toHash.toBase32());
            return;
        }
        // don't bother looking up via I2CP
        sendError(session, to, toPort, transID, msg);
    }

    /**
     *  @param from non-null
     *  @param msg non-null
     */
    private void sendError(I2PSession session, Destination to, int toPort, long transID, byte[] msg) {
        byte[] resp = new byte[8 + msg.length];
        DataHelper.toLong(resp, 0, 4, ACTION_ERROR);
        DataHelper.toLong(resp, 4, 4, transID);
        System.arraycopy(msg, 0, resp, 8, msg.length);
        try {
            session.sendMessage(to, resp, I2PSession.PROTO_DATAGRAM_RAW, PORT, toPort);
            if (_log.shouldDebug())
                _log.debug("sent error to " + to.toBase32());
        } catch (I2PSessionException ise) {
            if (_log.shouldWarn())
                _log.warn("error sending connect reply", ise);
        }
    }

    /**
     *  Blocking.
     *  @return null on failure
     */
    private Destination lookup(I2PSession session, Hash hash) {
        Destination rv = lookupCache(hash);
        if (rv != null)
            return rv;
        return lookupI2CP(session, hash);
    }

    /**
     *  Nonblocking.
     *  @return null on failure
     */
    private Destination lookupCache(Hash hash) {
        // Test deferred
        //if (true) return null;
        synchronized(_destCache) {
            return _destCache.get(hash);
        }
    }

    /**
     *  Blocking.
     *  @return null on failure
     */
    private Destination lookupI2CP(I2PSession session, Hash hash) {
        Destination rv;
        try {
            rv = session.lookupDest(hash, LOOKUP_TIMEOUT);
        } catch (I2PSessionException ise) {
            if (_log.shouldWarn())
                _log.warn("lookup error", ise);
            return null;
        }
        if (rv == null) {
            if (_log.shouldWarn())
                _log.warn("lookup failed for response to " + hash.toBase32());
        }
        return rv;
    }

    private long generateCID(Hash hash) {
        byte[] buf = new byte[40];
        System.arraycopy(hash.getData(), 0, buf, 0, 32);
        long time = _context.clock().now() / CLEAN_TIME;
        DataHelper.toLong8(buf, 32, time);
        return SipHashInline.hash24(sipk0, sipk1, buf);
    }

    private boolean validateCID(Hash hash, long cid) {
        byte[] buf = new byte[40];
        System.arraycopy(hash.getData(), 0, buf, 0, 32);
        // current epoch
        long time = _context.clock().now() / CLEAN_TIME;
        DataHelper.toLong8(buf, 32, time);
        long c = SipHashInline.hash24(sipk0, sipk1, buf);
        if (cid == c)
            return true;
        // previous epoch
        time--;
        DataHelper.toLong8(buf, 32, time);
        c = SipHashInline.hash24(sipk0, sipk1, buf);
        return cid == c;
    }

    /**
     *  Update the announce stat and set the announce count to 0
     */
    private class Cleaner extends SimpleTimer2.TimedEvent {
        public Cleaner() { super(_context.simpleTimer2()); }
        public void timeReached() {
            long count = _announces.getAndSet(0);
            _context.statManager().addRateData("plugin.zzzot.announces.udp",  count / (STAT_TIME / (60*1000L)));
            schedule(STAT_TIME);
        }
    }

    /**
     *  Until we have a nonblocking lookup API in I2CP
     *
     *  @since 0.20.0
     */
    private class Lookup implements Runnable {
        private final I2PSession _session;
        private final Hash _hash;
        private final int _port;
        private final byte[] _msg;

        public Lookup(I2PSession sess, Hash h, int port, byte[] msg) {
            _session = sess;
            _hash = h;
            _port = port;
            _msg = msg;
        }

        public void run() {
            // blocking
            Destination d = lookupI2CP(_session, _hash);
            if (d == null) {
                if (_log.shouldWarn())
                    _log.warn("deferred lookup failed for " + _hash.toBase32());
                return;
            }
            try {
                _session.sendMessage(d, _msg, I2PSession.PROTO_DATAGRAM_RAW, PORT, _port);
                if (_log.shouldDebug())
                    _log.debug("sent deferred reply to " + _hash.toBase32());
            } catch (I2PSessionException ise) {
                if (_log.shouldWarn())
                    _log.warn("error sending deferred reply", ise);
            }
        }
    }

    /**
     *  Until we have a nonblocking lookup API in I2CP
     *
     *  @since 0.20.0
     */
    private static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor() {
             super(0, 25, HANDLER_KEEPALIVE_MS, TimeUnit.MILLISECONDS,
                   new SynchronousQueue<Runnable>(), new CustomThreadFactory());
        }
    }

    /**
     *  Just to set the name and set Daemon
     *
     *  @since 0.20.0
     */
    private static class CustomThreadFactory implements ThreadFactory {
        private final AtomicInteger _executorThreadCount = new AtomicInteger();

        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName("ZzzOT lookup " + _executorThreadCount.incrementAndGet());
            rv.setDaemon(true);
            return rv;
        }
    }
}
