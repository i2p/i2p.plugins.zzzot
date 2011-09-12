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

import java.io.IOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketEepGet;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base64;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.util.EepGet;

/**
 *  Announce to seedless
 *  @since 0.6
 */
public class SeedlessAnnouncer {

	private static final String SPONGE =
		"VG4Bd~q1RA3BdoF3z5fSR7p0xe1CTVgDMWVGyFchA9Wm2iXUkIR35G45XE31Uc9~IOt-ktNLL2~TYQZ13Vl8udosngDn8RJG1NtVASH4khsbgkkoFLWd6UuvuOjQKBFKjaEPJgxOzh0kxolRPPNHhFuuAGzNLKvz~LI2MTf0P6nwmRg1lBoRIUpSVocEHY4X306nT2VtY07FixbJcPCU~EeRin24yNoiZop-C3Wi1SGwJJK-NS7mnkNzd8ngDJXDJtR-wLP1vNyyBY6NySgqPiIhENHoVeXd5krlR42HORCxEDb4jhoqlbyJq-PrhTJ5HdH4-~gEq09B~~NIHzy7X02XgmBXhTYRtl6HbLMXs6SI5fq9OFgVp5YZWYUklJjMDI7jOrGrEZGSHhnJK9kT6D3CqVIM0cYEhe4ttmTegbZvC~J6DrRTIAX422qRQJBPsTUnv4iFyuJE-8SodP6ikTjRH21Qx73SxqOvmrOiu7Bsp0lvVDa84aoaYLdiGv87AAAA";

	private static final String ANNOUNCE = "announce " + Base64.encode("seedless,eepsite,torrent");

	public void announce(TunnelController controller) {
		// get the I2PTunnel from the controller (no method now)

		// get the I2PTunnelTask from I2PTunnel

		// cast to an I2PTunnelServer

		// get the SocketManager from the server (no method now)
		I2PSocketManager mgr = null;

		I2PAppContext ctx = I2PAppContext.getGlobalContext();
		String url = "http://" + SPONGE + "/Seedless/seedless";
		EepGet get = new I2PSocketEepGet(ctx, mgr, 1, -1, 1024, null, new DummyOutputStream(), url);
		get.addHeader("X-Seedless", ANNOUNCE);
		get.fetch();
	}

	private static class DummyOutputStream extends OutputStream {
		public void write(int b) {}
	}
}
