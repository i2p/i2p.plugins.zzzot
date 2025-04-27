<%@page import="java.io.ByteArrayInputStream,java.util.ArrayList,java.util.Collections,java.util.List,java.util.Map,java.util.HashMap,java.util.concurrent.ConcurrentMap,net.i2p.data.Base64,net.i2p.data.Destination,net.i2p.zzzot.*,org.klomp.snark.bencode.BEncoder" %><%

/*
 *  Above one-liner is so there is no whitespace -> IllegalStateException
 *
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

/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 */
	// would be nice to make these configurable
	final int MAX_RESPONSES = 25;
	final boolean ALLOW_IP_MISMATCH = false;
	final boolean ALLOW_COMPACT_RESPONSE = true;
	final boolean ALLOW_NONCOMPACT_RESPONSE = false;

	// so the chars will turn into bytes correctly
	request.setCharacterEncoding("ISO-8859-1");
        // above doesn't work for the query string
        // https://wiki.eclipse.org/Jetty/Howto/International_Characters
        // we could also do ((org.eclipse.jetty.server.Request) request).setQueryEncoding("ISO-8859-1")
        request.setAttribute("org.eclipse.jetty.server.Request.queryEncoding", "ISO-8859-1");
	java.io.OutputStream cout = response.getOutputStream();
	response.setCharacterEncoding("ISO-8859-1");
	response.setContentType("text/plain");
        response.setHeader("Pragma", "no-cache");
	String info_hash = request.getParameter("info_hash");
	String peer_id = request.getParameter("peer_id");
	// ignored
	String port = request.getParameter("port");
	// ignored
	String uploaded = request.getParameter("uploaded");
	// ignored
	String downloaded = request.getParameter("downloaded");
	String sleft = request.getParameter("left");
	String event = request.getParameter("event");
	String ip = request.getParameter("ip");
	String numwant = request.getParameter("numwant");
	boolean compact = ALLOW_COMPACT_RESPONSE && request.getParameter("compact") != null;
	// use to enforce destination
        String him = request.getHeader("X-I2P-DestB64");
        String xff = request.getHeader("X-Forwarded-For");
        String xfs = request.getHeader("X-Forwarded-Server");

	boolean fail = false;
	String msg = "bad announce";

	if (xff != null || xfs != null) {
		fail = true;
		msg = "Non-I2P access denied";
	        //response.setStatus(403, msg);
	        response.setStatus(403);
	}

	if (!compact && !ALLOW_NONCOMPACT_RESPONSE && !fail) {
		fail = true;
		msg = "non-compact responses unsupported";
        }

	if (info_hash == null && !fail) {
		fail = true;
		msg = "no info hash";
	}

	if (!fail && info_hash.length() != 20) {
		fail = true;
		msg = "bad info hash length " + info_hash.length();
	}

	if (ip == null && !fail) {
		fail = true;
		msg = "no ip (dest)";
	}

	if (peer_id == null && !fail) {
		fail = true;
		msg = "no peer id";
	}

	if (!fail && peer_id.length() != 20) {
		fail = true;
		msg = "bad peer id length " + peer_id.length();
	}

 	Torrents torrents = ZzzOTController.getTorrents();
	if (torrents == null && !fail) {
		fail = true;
		msg = "tracker is down";
	}

	InfoHash ih = null;
	if (!fail) {
		try {
			ih = torrents.createInfoHash(info_hash);
		} catch (IllegalArgumentException e) {
			fail = true;
			msg = "bad infohash " + e;
		}
	}

	Destination d = null;
	if (!fail) {
		try {
			if (ip.endsWith(".i2p"))
				ip = ip.substring(0, ip.length() - 4);
			byte[] b = Base64.decode(ip);
			if (b == null)
				throw new Exception();
			d = Destination.create(new ByteArrayInputStream(b));  // cache
		} catch (Exception e) {
			fail = true;
			msg = "bad dest " + e;
		}
	}

	PID pid = null;
	if (!fail) {
		try {
			pid = torrents.createPID(peer_id);
		} catch (IllegalArgumentException e) {
			fail = true;
			msg = "bad peer id " + e;
		}
	}

	// int params

	// ignored
	long up = 0;
	try {
		up = Long.parseLong(uploaded);
		if (up < 0)
			up = 0;
	} catch (NumberFormatException nfe) {};

	// ignored
	long down = 0;
	try {
		down = Long.parseLong(downloaded);
		if (down < 0)
			down = 0;
	} catch (NumberFormatException nfe) {};

	int want = MAX_RESPONSES;
	try {
		want = Integer.parseInt(numwant);
		if (want > MAX_RESPONSES)
			want = MAX_RESPONSES;
		else if (want < 0)
			want = 0;
	} catch (NumberFormatException nfe) {};

	// spoof check
	// if him == null, we are not using the I2P HTTP server tunnel, or something is wrong
	boolean matchIP = ALLOW_IP_MISMATCH || him == null || ip == null || ip.equals(him);
	if (want <= 0 && (!matchIP) && !fail) {
		fail = true;
		msg = "ip mismatch";
	}

	long left = 0;
	if (!"completed".equals(event)) {
		try {
			left = Long.parseLong(sleft);
			if (left < 0)
				left = 0;
		} catch (NumberFormatException nfe) {};
	}

	Map<String, Object> m = new HashMap<String, Object>(8);
	if (fail) {
		m.put("failure reason", msg);		
	} else if ("stopped".equals(event)) {
		Peers peers = torrents.get(ih);
		if (matchIP && peers != null)
			peers.remove(pid);
		m.put("interval", torrents.getInterval());
	} else {
		Peers peers = torrents.get(ih);
		if (peers == null) {
			peers = new Peers();
			Peers p2 = torrents.putIfAbsent(ih, peers);
			if (p2 != null)
				peers = p2;
		}

		// fixme same peer id, different dest
		Peer p = peers.get(pid);
		if (p == null) {
		  	ConcurrentMap<String, String> destCache = ZzzOTController.getDestCache();
			p = new Peer(pid.getData(), d, destCache);
			// don't add if spoofed
			if (matchIP) {
				Peer p2 = peers.putIfAbsent(pid, p);
				if (p2 != null)
					p = p2;
			}
		}
		// don't update if spoofed
		if (matchIP)
			p.setLeft(left);

		m.put("interval", torrents.getInterval());
		int size = peers.size();
		int seeds = peers.countSeeds();
		m.put("complete", Integer.valueOf(seeds));
		m.put("incomplete", Integer.valueOf(size - seeds));
		if (want <= 0) {
			// snark < 0.7.13 always wants a list
			m.put("peers", java.util.Collections.EMPTY_LIST);
		} else {
			List<Peer> peerlist = new ArrayList<Peer>(peers.values());
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
					Collections.shuffle(peerlist);
					peerlist = peerlist.subList(0, want);
				}
			}
			if (compact) {
				// one big string
				byte[] peerhashes = new byte[32 * peerlist.size()];
				for (int i = 0; i < peerlist.size(); i++)
					System.arraycopy(peerlist.get(i).getHashBytes(), 0, peerhashes, i * 32, 32);
				m.put("peers", peerhashes);
			} else if (ALLOW_NONCOMPACT_RESPONSE) {
				m.put("peers", peerlist);
			} else {
				// won't get here
			}
		}
	}
	BEncoder.bencode(m, cout);

/*
 *  Remove the newline on the last line or
 *  it will generate an IllegalStateException
 *
 */
%>