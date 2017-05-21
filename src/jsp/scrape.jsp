<%@page import="java.util.ArrayList,java.util.List,java.util.Map,java.util.HashMap,net.i2p.zzzot.*,org.klomp.snark.bencode.BEncoder" %><%

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
        String xff = request.getHeader("X-Forwarded-For");
        String xfs = request.getHeader("X-Forwarded-Server");

	boolean fail = false;
	String msg = "bad";

	if (xff != null || xfs != null) {
		fail = true;
		msg = "Non-I2P access denied";
	        //response.setStatus(403, msg);
	        response.setStatus(403);
	}

	boolean all = info_hash == null;

        Torrents torrents = ZzzOTController.getTorrents();
	if (torrents == null && !fail) {
		fail = true;
		msg = "tracker is down";
	}

	InfoHash ih = null;
	if ((!all) && !fail) {
		try {
			ih = torrents.createInfoHash(info_hash);
		} catch (Exception e) {
			fail = true;
			msg = "bad infohash " + e;
		}
	}

	// build 3-level dictionary
	Map<String, Object> m = new HashMap<String, Object>(4);
	if (fail) {
		m.put("failure reason", msg);		
	} else {
		List<InfoHash> ihList = new ArrayList<InfoHash>();
		if (all)
			ihList.addAll(torrents.keySet());
		else
			ihList.add(ih);
		// requires I2P 0.9.30-8
		Map<byte[], Map> files = new HashMap<byte[], Map>();
		for (InfoHash ihash : ihList) {
			Peers peers = torrents.get(ihash);
			if (peers == null)
				continue;
			Map<String, Object> dict = new HashMap<String, Object>();
			int size = peers.size();
			int seeds = peers.countSeeds();
			dict.put("complete", Integer.valueOf(seeds));
			dict.put("incomplete", Integer.valueOf(size - seeds));
			dict.put("downloaded", Integer.valueOf(0));
			files.put(ihash.getData(), dict);
		}
		m.put("files", files);
	}
	try {
		BEncoder.bencode(m, cout);
	} catch (IllegalArgumentException iae) {
		// before I2P 0.9.30-8
		// just let it truncate, wasn't valid before anyway
	}

/*
 *  Remove the newline on the last line or
 *  it will generate an IllegalStateException
 *
 */
%>