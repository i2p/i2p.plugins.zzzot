ZzzOT I2P Open Tracker Plugin
-----------------------------

This is a very simple in-memory open tracker, wrapped into an I2P plugin.
Plugin su3 binaries are available at http://stats.i2p/i2p/plugins/

The plugin starts a new http server tunnel, eepsite, and Jetty server running at
port 7662. The tracker status is available at http://127.0.0.1:7662/tracker/
If other files are desired on the eepsite, they can be added at eepsite/docroot

The open tracker code and jsps were written from scratch, but depend on some
code in i2psnark.jar from the I2P installation for bencoding, and of course on
other i2p libraries. See the license files in I2P for i2p and i2psnark licenses.
There is also some code modified from Jetty. See LICENSES.txt for the
zzzot and Jetty licenses.

I2P source must be installed and built in ../i2p.i2p to compile this package.

As of release 0.19.0:
- Full scrape is disabled by default

As of release 0.20.0:
- I2P 2.9.0 or higher required to build and run
- UDP announces are supported, see http://i2p-projekt.i2p/spec/proposals/160
- Non-compact responses are no longer supported
- Seedless support is removed
- Memory usage greatly reduced

Valid announce URLs:
    /a
    /announce
    /announce.jsp
    /announce.php
    /tracker/a
    /tracker/announce
    /tracker/announce.jsp
    /tracker/announce.php

UDP announce URLs (default port is 6969):
    udp://yourb32string.b32.i2p:6969/

Valid scrape URLs:
    /scrape
    /scrape.jsp
    /scrape.php
    /tracker/scrape
    /tracker/scrape.jsp
    /tracker/scrape.php

You may use the rest of the eepsite for other purposes; for example you
may place torrent files in: eepsite/docroot/torrents/
