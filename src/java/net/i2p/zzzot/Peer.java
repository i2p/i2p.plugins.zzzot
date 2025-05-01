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

import net.i2p.data.Destination;
import net.i2p.data.Hash;

/*
 *  A single peer for a single torrent.
 *  Save a couple stats. We no longer support non-compact
 *  announces, so this is no longer a Map that can be BEncoded.
 *  See announce.jsp.
 */
public class Peer {

    private final Hash hash;
    private long lastSeen;
    private long bytesLeft;
    private static final Integer PORT = Integer.valueOf(6881);

    public Peer(byte[] id, Destination address) {
        hash = address.calculateHash();
    }

    /**
     *  @since 0.20.0
     */
    public Peer(byte[] id, Hash h) {
        hash = h;
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

    /**
     *  @since 0.20
     */
    public byte[] getHashBytes() {
        return hash.getData();
    }
}
