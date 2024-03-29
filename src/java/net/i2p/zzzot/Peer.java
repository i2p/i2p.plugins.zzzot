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

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentMap;

import net.i2p.crypto.SHA256Generator;
import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

/*
 *  A single peer for a single torrent.
 *  Save a couple stats, and implements
 *  a Map so we can BEncode it
 *  So it's like PeerID but in reverse - we make a Map from the
 *  data. PeerID makes the data from a Map.
 */
public class Peer extends HashMap<String, Object> {

    private long lastSeen;
    private long bytesLeft;
    private static final Integer PORT = Integer.valueOf(6881);

    public Peer(byte[] id, Destination address, ConcurrentMap<String, String> destCache) {
        super(3);
        if (id.length != 20)
            throw new IllegalArgumentException("Bad peer ID length: " + id.length);
        put("peer id", id);
        put("port", PORT);
        // cache the 520-byte address strings
        String dest = address.toBase64() + ".i2p";
	String oldDest = destCache.putIfAbsent(dest, dest);
        if (oldDest != null)
            dest = oldDest;
        put("ip", dest);
    }

    public void setLeft(long l) {
        bytesLeft = l;
        lastSeen = System.currentTimeMillis();
    }

    public boolean isSeed() {
        return bytesLeft <= 0;
    }

    public long lastSeen() {
        return lastSeen;
    }

    /** convert b64.i2p to a Hash, then to a binary string */
    /* or should we just store it in the constructor? cache it? */
    public String getHash() {
        try {
            return new String(getHashObject().getData(), "ISO-8859-1");
        } catch (UnsupportedEncodingException uee) { return null; }
    }

    /**
     *  @since 0.19
     */
    public Hash getHashObject() {
        String ip = (String) get("ip");
        byte[] b = Base64.decode(ip.substring(0, ip.length() - 4));
        return SHA256Generator.getInstance().calculateHash(b);
    }
}
