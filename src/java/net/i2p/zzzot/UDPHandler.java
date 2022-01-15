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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.util.I2PAppThread;
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
    private final I2PDatagramDissector _diss;
    // conn ID to dest and time added
    private final Map<Long, DestAndTime> _connectCache;
    private final Cleaner _cleaner;

    // The listen port.
    // We listen on all ports, so the announce URL
    // doesn't need a port.
    public static final int PORT = I2PSession.PORT_ANY;
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
    private static final long CLEAN_TIME = 2*60*1000;


    public UDPHandler(I2PAppContext ctx, I2PTunnel tunnel, ZzzOT zzzot) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPHandler.class);
        _tunnel = tunnel;
        _zzzot = zzzot;
        _diss = new I2PDatagramDissector();
        _connectCache = new ConcurrentHashMap<Long, DestAndTime>();
        _cleaner = new Cleaner();
    }

    public void start() {
        (new I2PAppThread(new Waiter(), "ZzzOT UDP startup", true)).start();
    }

    private class Waiter implements Runnable {
        public void run() {
            while (true) {
                // requires I2P 0.9.53 (1.7.0)
                List<I2PSession> sessions = _tunnel.getSessions();
                if (sessions.isEmpty()) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    continue;
                }
                I2PSession session = sessions.get(0);
                session.addMuxedSessionListener(UDPHandler.this, I2PSession.PROTO_DATAGRAM, PORT);
                session.addMuxedSessionListener(UDPHandler.this, I2PSession.PROTO_DATAGRAM_RAW, PORT);
                _cleaner.schedule(CLEAN_TIME);
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
            if (proto == I2PSession.PROTO_DATAGRAM) {
                // load datagram into it
                _diss.loadI2PDatagram(msg);
                handle(session, _diss.getSender(), fromPort, _diss.getPayload());
            } else if (proto == I2PSession.PROTO_DATAGRAM_RAW) {
                handle(session, null, fromPort, _diss.getPayload());
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
        _connectCache.clear();
    }

    public void errorOccurred(I2PSession arg0, String arg1, Throwable arg2) {
        _log.error(arg1, arg2);
    }

    /// end listener methods ///
        
    private void handle(I2PSession session, Destination from, int fromPort, byte[] data) {
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
                    _log.warn("dropping raw connect");
                return;
            }
            handleConnect(session, from, fromPort, data);
        } else if (action == ACTION_ANNOUNCE) {
            handleAnnounce(session, connID, from, fromPort, data);
        } else if (action == ACTION_SCRAPE) {
            if (_log.shouldWarn())
                _log.warn("got unsupported scrape");
        } else {
            if (_log.shouldWarn())
                _log.warn("dropping bad action " + action);
        }
    }

    /**
     *  @param from non-null
     */
    private void handleConnect(I2PSession session, Destination from, int fromPort, byte[] data) {
        int transID = (int) DataHelper.fromLong(data, 12, 4);
        long connID = _context.random().nextLong();
        byte[] resp = new byte[16];
        DataHelper.toLong(resp, 4, 4, transID);
        DataHelper.toLong8(resp, 8, connID);
        try {
            session.sendMessage(from, resp, I2PSession.PROTO_DATAGRAM_RAW, PORT, fromPort);
            if (_log.shouldDebug())
                _log.debug("sent connect reply to " + from);
            _connectCache.put(Long.valueOf(connID), new DestAndTime(from, _context.clock().now()));
        } catch (I2PSessionException ise) {
            if (_log.shouldWarn())
                _log.warn("error sending connect reply", ise);
        }
    }

    /**
     *  @param from may be null
     */
    private void handleAnnounce(I2PSession session, long connID, Destination from, int fromPort, byte[] data) {
        int sz = data.length;
        if (sz < 96) {
            if (_log.shouldWarn())
                _log.warn("dropping short announce length " + sz);
            return;
        }
        if (from == null) {
            DestAndTime dat = _connectCache.get(Long.valueOf(connID));
            if (dat == null) {
                if (_log.shouldWarn())
                    _log.warn("no connID found " + connID);
                return;
            }
            from = dat.dest;
        }

        // parse packet
        int transID = (int) DataHelper.fromLong(data, 12, 4);
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
        long want = DataHelper.fromLong(data, 92, 4);
        if (want > MAX_RESPONSES)
            want = MAX_RESPONSES;
        // ignored
        //int port = (int) DataHelper.fromLong(data, 96, 2);

        Torrents torrents = _zzzot.getTorrents();
        Peers peers = torrents.get(ih);
        if (peers == null && event != EVENT_STOPPED) {
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
                ConcurrentMap<String, String> destCache = _zzzot.getDestCache();
                p = new Peer(pid.getData(), from, destCache);
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
        DataHelper.toLong(resp, 20, 2, count);
        if (peerlist != null) {
            for (int i = 0; i < count; i++) {
                System.arraycopy(peerlist.get(i).getHashObject().getData(), 0, resp, 22 + (i * 32), 32);
            }
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

    private static class DestAndTime {
        public final Destination dest;
        public final long time;

        public DestAndTime(Destination d, long t) {
            dest = d;
            time = t;
        }
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {

        public Cleaner() { super(_context.simpleTimer2()); }

        public void timeReached() {
            if (!_connectCache.isEmpty()) {
                long exp = _context.clock().now() - CLEAN_TIME;
                for (Iterator<DestAndTime> iter = _connectCache.values().iterator(); iter.hasNext(); ) {
                    DestAndTime dat = iter.next();
                    if (dat.time < exp)
                        iter.remove();
                }
            }
            schedule(CLEAN_TIME);
        }
    }
}
