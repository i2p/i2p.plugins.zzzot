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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import static net.i2p.app.ClientAppState.*;
import net.i2p.apps.systray.UrlLauncher;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * This handles the starting and stopping of an eepsite tunnel and jetty
 * from a single static class so it can be called via clients.config.
 *
 * This makes installation of a new eepsite a turnkey operation -
 * the user is not required to configure a new tunnel in i2ptunnel manually.
 *
 * Usage: ZzzOTController -d $PLUGIN [start|stop]
 *
 * @author zzz
 */
public class ZzzOTController implements ClientApp {
    private final I2PAppContext _context;
    private final Log _log;
    private final String[] _args;
    private final ClientAppManager _mgr;
    private Server _server;
    private TunnelController _tunnel;
    private final ZzzOT _zzzot;
    /** only for main() */
    private static volatile ZzzOTController _controller;
    // you wouldn't run two instances in the same JVM, would you?
    private static String _sitename;
    private static boolean _showfooter;
    private static String _footertext;
    private static boolean _fullScrape;
    private final boolean _enableUDP;
    private final int _udpPort;
    private UDPHandler _udp;

    private ClientAppState _state = UNINITIALIZED;

    private static final String NAME = "ZzzOT";
    private static final String DEFAULT_SITENAME = "ZZZOT";
    private static final String PROP_SITENAME = "sitename";
    private static final String VERSION = "0.18.0";
    private static final String DEFAULT_SHOWFOOTER = "true";
    private static final String PROP_SHOWFOOTER = "showfooter";
    private static final String DEFAULT_FOOTERTEXT = "Running <a href=\"http://git.idk.i2p/i2p-hackers/i2p.plugins.zzzot\" target=\"_blank\">ZZZOT</a> " + VERSION;
    private static final String PROP_FOOTERTEXT = "footertext";
    private static final String PROP_FULLSCRAPE = "allowFullScrape";
    private static final String DEFAULT_FULLSCRAPE = "false";
    private static final String PROP_UDP = "udp";
    private static final String DEFAULT_UDP = "false";
    private static final String PROP_UDP_PORT = "udp";
    private static final int DEFAULT_UDP_PORT = 6969;
    private static final String CONFIG_FILE = "zzzot.config";
    private static final String BACKUP_SUFFIX = ".jetty8";
    private static final String[] xmlFiles = {
        "jetty.xml", "contexts/base-context.xml", "contexts/cgi-context.xml",
        "etc/realm.properties", "etc/webdefault.xml" };

    /**
     *  @since 0.12.0
     */
    public ZzzOTController(I2PAppContext ctx, ClientAppManager mgr, String args[]) {
        _context = ctx;
        _log = ctx.logManager().getLog(ZzzOTController.class);
        _mgr = mgr;
        _args = args;
        File cfile = new File(_context.getAppDir(), "plugins/zzzot/" + CONFIG_FILE);
        Properties props = new Properties();
        if (cfile.exists()) {
            try {
                DataHelper.loadProps(props, cfile);
            } catch (IOException ioe) {
                _log.error("Failed loading zzzot config from " + cfile, ioe);
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No config file " + cfile);
        }
        _zzzot = new ZzzOT(ctx, props);
        _sitename = props.getProperty(PROP_SITENAME, DEFAULT_SITENAME);
        _showfooter = Boolean.parseBoolean(props.getProperty(PROP_SHOWFOOTER, DEFAULT_SHOWFOOTER));
        _footertext = props.getProperty(PROP_FOOTERTEXT, DEFAULT_FOOTERTEXT);
        _fullScrape = Boolean.parseBoolean(props.getProperty(PROP_FULLSCRAPE, DEFAULT_FULLSCRAPE));
        _enableUDP = Boolean.parseBoolean(props.getProperty(PROP_UDP, DEFAULT_UDP));
        int p = DEFAULT_UDP_PORT;
        String port = props.getProperty(PROP_UDP_PORT);
        if (port != null) {
            try {
                p = Integer.parseInt(port);
            } catch (NumberFormatException nfe) {}
        }
        _udpPort = p;
        _state = INITIALIZED;
    }

    /**
     *  No longer supported, as we now need the ClientAppManager for the webapp to find us
     */
    public synchronized static void main(String args[]) {
        throw new UnsupportedOperationException("Must use ClientApp interface");
    }

    /**
     *  @return null if not running
     */
    public static Torrents getTorrents() {
        ClientAppManager mgr = I2PAppContext.getGlobalContext().clientAppManager();
        if (mgr == null)
            return null;
        ClientApp z = mgr.getRegisteredApp(NAME);
        if (z == null)
            return null;
        ZzzOTController ctrlr = (ZzzOTController) z;
        return ctrlr._zzzot.getTorrents();
    }


    /**
     *  @return null if not running
     *  @since 0.9.14
     */
    public static ConcurrentMap<String, String> getDestCache() {
        ClientAppManager mgr = I2PAppContext.getGlobalContext().clientAppManager();
        if (mgr == null)
            return null;
        ClientApp z = mgr.getRegisteredApp(NAME);
        if (z == null)
            return null;
        ZzzOTController ctrlr = (ZzzOTController) z;
        return ctrlr._zzzot.getDestCache();
    }

    /**
     *  @return false if not running
     *  @since 0.20.0
     */
    public static boolean isUDPEnabled() {
        ClientAppManager mgr = I2PAppContext.getGlobalContext().clientAppManager();
        if (mgr == null)
            return false;
        ClientApp z = mgr.getRegisteredApp(NAME);
        if (z == null)
            return false;
        ZzzOTController ctrlr = (ZzzOTController) z;
        return ctrlr.getUDPEnabled();
    }

    /**
     *  @return false if not running
     *  @since 0.20.0
     */
    private boolean getUDPEnabled() {
        return _enableUDP;
    }

    /**
     *  @return 0 if not running
     *  @since 0.20.0
     */
    public static int udpPort() {
        ClientAppManager mgr = I2PAppContext.getGlobalContext().clientAppManager();
        if (mgr == null)
            return 0;
        ClientApp z = mgr.getRegisteredApp(NAME);
        if (z == null)
            return 0;
        ZzzOTController ctrlr = (ZzzOTController) z;
        return ctrlr.getUDPPort();
    }

    /**
     *  @since 0.20.0
     */
    public int getUDPPort() {
        return _udpPort;
    }

    /**
     *  @param args ignored
     */
    private void start(String args[]) {
        //File pluginDir = new File(args[1]);
        File pluginDir = new File(_context.getAppDir(), "plugins/zzzot");
        if (!pluginDir.exists())
            throw new IllegalArgumentException("Plugin directory " + pluginDir.getAbsolutePath() + " does not exist");

        // We create the private key file in advance, so that we can
        // create the help.html file from the templates
        // without waiting for i2ptunnel to create it AND build the tunnels before returning.
        Destination dest = null;
        File key = new File(pluginDir, "eepPriv.dat");
        if (!key.exists()) {
            PrivateKeyFile pkf = new PrivateKeyFile(new File(pluginDir, "eepPriv.dat"));
            try {
                dest = pkf.createIfAbsent();
            } catch (Exception e) {
                _log.error("Unable to create " + key.getAbsolutePath() + ' ' + e);
                throw new IllegalArgumentException("Unable to create " + key.getAbsolutePath() + ' ' + e);
            }
            _log.logAlways(Log.INFO, "NOTICE: ZzzOT: New eepsite keys created in " + key.getAbsolutePath());
            _log.logAlways(Log.INFO, "NOTICE: ZzzOT: You should back up this file!");
            String b32 = Base32.encode(dest.calculateHash().getData()) + ".b32.i2p";
            String b64 = dest.toBase64();
            _log.logAlways(Log.INFO, "NOTICE: ZzzOT: Your base 32 address is " + b32);
            _log.logAlways(Log.INFO, "NOTICE: ZzzOT: Your base 64 address is " + b64);
        }
        startJetty(pluginDir, dest);
        startI2PTunnel(pluginDir, dest);
        _zzzot.start();
        // requires I2P 0.9.66 (2.9.0)
        if (_enableUDP) {
            _udp = new UDPHandler(_context, _tunnel.getTunnel(), _zzzot, _udpPort);
            _udp.start();
        }
    }


    private void startI2PTunnel(File pluginDir, Destination dest) {
        File i2ptunnelConfig = new File(pluginDir, "i2ptunnel.config");
        Properties i2ptunnelProps = new Properties();
        try {
            DataHelper.loadProps(i2ptunnelProps, i2ptunnelConfig);
        } catch (IOException ioe) {
            _log.error("Cannot open " + i2ptunnelConfig.getAbsolutePath() + ' ' + ioe);
            throw new IllegalArgumentException("Cannot open " + i2ptunnelConfig.getAbsolutePath() + ' ' + ioe);
        }
        if (i2ptunnelProps.getProperty("tunnel.0.option.i2cp.leaseSetEncType") == null)
            i2ptunnelProps.setProperty("tunnel.0.option.i2cp.leaseSetEncType", "4,0");
        TunnelController tun = new TunnelController(i2ptunnelProps, "tunnel.0.");
        if (dest != null) {
            // start in foreground so we can get the destination
            tun.startTunnel();
            List msgs = tun.clearMessages();
            for (Object s : msgs) {
                 _log.logAlways(Log.INFO, "NOTICE: ZzzOT Tunnel message: " + s);
            }
        } else {
            tun.startTunnelBackground();
        }
        _tunnel = tun;
    }

    private void startJetty(File pluginDir, Destination dest) {
        if (_server != null)
            throw new IllegalArgumentException("Jetty already running!");
        migrateJettyXML(pluginDir);
        File tmpdir = new File(_context.getTempDir().getAbsolutePath(), "/zzzot-work");
        tmpdir.mkdir();
        File jettyXml = new File(pluginDir, "jetty.xml");
        try {
            Resource.setDefaultUseCaches(false);
            XmlConfiguration xmlc = new XmlConfiguration(jettyXml.toURI().toURL());
            Server serv = (Server) xmlc.configure();
            //HttpContext[] hcs = serv.getContexts();
            //for (int i = 0; i < hcs.length; i++)
            //     hcs[i].setTempDirectory(tmpdir);
            serv.start();
            _server = serv;
        } catch (Throwable t) {
            _log.error("ZzzOT jetty start failed", t);
            throw new IllegalArgumentException("Jetty start failed " + t);
        }
        if (dest != null)
            launchHelp(pluginDir, dest);
    }

    private void stop() {
        stopI2PTunnel();
        stopJetty();
        if (_udp != null)
            _udp.stop();
        _zzzot.stop();
    }

    private void stopI2PTunnel() {
        if (_tunnel == null)
            return;
        try {
            // destroyTunnel() not available until 0.9.17
            if (VersionComparator.comp(CoreVersion.VERSION, "0.9.17") >= 0) {
                try {
                    _tunnel.destroyTunnel();
                } catch (Throwable t) {
                    _tunnel.stopTunnel();
                }
            } else {
                _tunnel.stopTunnel();
            }
        } catch (Throwable t) {
            _log.error("ZzzOT tunnel stop failed", t);
            throw new IllegalArgumentException("Tunnel stop failed " + t);
        }
        _tunnel = null;
    }

    private void stopJetty() {
        if (_server == null)
            return;
        try {
            _server.stop();
        } catch (Throwable t) {
            _log.error("ZzzOT jetty stop failed", t);
            throw new IllegalArgumentException("Jetty stop failed " + t);
        }
        _server = null;
    }

    /**
     *  Migate the jetty configuration files.
     *  Save old jetty.xml if moving from jetty 5 to jetty 6
     */
    private void migrateJettyXML(File pluginDir) {
        // contexts dir does not exist in Jetty 5
        File file = new File(pluginDir, "contexts");
        file.mkdir();
        file = new File(pluginDir, "etc");
        file.mkdir();
        file = new File(pluginDir, "jetty.xml");
        if (!shouldMigrate(file))
            return;
        for (int i = 0; i < xmlFiles.length; i++) {
            backupAndMigrateFile(pluginDir, xmlFiles[i]);
        }
    }

    /**
     *  @return should we copy over all the files, based on the contents of this one
     *  @since 0.10 (Jetty 7)
     */
    private static boolean shouldMigrate(File f) {
        String xml = FileUtil.readTextFile(f.getAbsolutePath(), 400, true);
        if (xml == null)
            return true;
        return xml.contains("class=\"org.eclipse.jetty.server.nio.SelectChannelConnector\"");
    }

    /**
     *  Backup a file and migrate new XML
     *  @return success
     *  @since Jetty 7
     */
    private boolean backupAndMigrateFile(File toDir, String filename) {
        File to = new File(toDir, filename);
        boolean rv = backupFile(to);
        boolean rv2 = migrateJettyFile(toDir, filename);
        return rv && rv2;
    }

    /**
     *  Backup a file
     *  @return success
     *  @since Jetty 7
     */
    private static boolean backupFile(File from) {
        if (!from.exists())
            return true;
        File to = new File(from.getAbsolutePath() + BACKUP_SUFFIX);
        if (to.exists())
            to = new File(to.getAbsolutePath() + "." + System.currentTimeMillis());
        boolean rv = FileUtil.copy(from, to, false, true);
        if (rv)
            System.err.println("Backed up file " + from + " to " + to);
        else
            System.err.println("WARNING: Failed to back up file " + from + " to " + to);
        return rv;
    }

    /**
     *  Migrate a single jetty config file, replacing $PLUGIN as we copy it.
     */
    private boolean migrateJettyFile(File pluginDir, String name) {
        File templateDir = new File(pluginDir, "templates");
        File fileTmpl = new File(templateDir, name);
        File outFile = new File(pluginDir, name);
        FileOutputStream os = null;
        try {
            String props = FileUtil.readTextFile(fileTmpl.getAbsolutePath(), 600, true);
            if (props == null)
                throw new IOException(fileTmpl.getAbsolutePath() + " open failed");
            props = props.replace("$PLUGIN", pluginDir.getAbsolutePath());
            os = new FileOutputStream(outFile);
            os.write(props.getBytes("UTF-8"));
            return true;
        } catch (IOException ioe) {
            _log.error(outFile + " migrate failed", ioe);
            return false;
        } finally {
            if (os != null) try { os.close(); } catch (IOException ioe) {}
        }
    }

    /** put the directory, base32, and base64 info in the help.html file and launch a browser window to display it */
    private void launchHelp(File pluginDir, Destination dest) {
        File fileTmpl = new File(pluginDir, "templates/help.html");
        File outFile = new File(pluginDir, "eepsite/docroot/help.html");
        File index_in = new File(pluginDir, "templates/index.html");
        File index_out = new File(pluginDir, "eepsite/docroot/index.html");
        String b32 = Base32.encode(dest.calculateHash().getData()) + ".b32.i2p";
        String b64 = dest.toBase64();
        try {
            // help.html
            String html = FileUtil.readTextFile(fileTmpl.getAbsolutePath(), 500, true);
            if (html == null)
                throw new IOException(fileTmpl.getAbsolutePath() + " open failed");
            // replace $HOME in path
            String home = System.getProperty("user.home");
            String pdir = pluginDir.getAbsolutePath();
            if (pdir.startsWith(home)) {
                pdir = "$HOME" + pdir.substring(home.length());
                // only warn about username in help if we haven't replaced it with $HOME
                html = html.replace("<p class=\"warn\" id=\"docroot\">", "<p id=\"docroot\">");
                html = html.replace("<br><span class=\"emphasis\"><b>You should probably move it outside of the document root " +
                                    "before you announce your eepsite as it may contain your username.</b></span>", "");
            }
            html = html.replace("$PLUGIN", pdir);
            html = html.replace("$B32", b32);
            html = html.replace("$B64", b64);
            html = html.replace("$VERSION", VERSION);
            String bdir = _context.getBaseDir().getAbsolutePath();
            if (bdir.startsWith(home))
                bdir = "$HOME" + bdir.substring(home.length());
            html = html.replace("$I2P", bdir);
            FileOutputStream os = new FileOutputStream(outFile);
            os.write(html.getBytes("UTF-8"));
            os.close();
            // index.html
            String html2 = FileUtil.readTextFile(index_in.getAbsolutePath(), 50, true);
            if (html2 == null)
                throw new IOException(fileTmpl.getAbsolutePath() + " open failed");
            html2 = html2.replace("$B32", b32);
            FileOutputStream os2 = new FileOutputStream(index_out);
            os2.write(html2.getBytes("UTF-8"));
            os2.close();
            Thread t = new I2PAppThread(new Launcher(), "ZzzOTHelp", true);
            t.start();
        } catch (IOException ioe) {
            _log.error("ZzzOT help launch failed", ioe);
        }
    }

    private static class Launcher implements Runnable {
        public void run() {
            UrlLauncher.main(new String[] { "http://127.0.0.1:7662/help.html" } );
        }
    }

    /////// ClientApp methods

    /** @since 0.12.0 */
    public synchronized void startup() {
        if (_mgr != null) {
            // this is really ugly, but thru 0.9.16,
            // stopping a ClientApp plugin with $PLUGIN in the args fails,
            // and it tries to start a second one instead.
            // Find the first one and stop it.
            ClientApp z = _mgr.getRegisteredApp(NAME);
            if (z != null) {
                if (VersionComparator.comp(CoreVersion.VERSION, "0.9.17") < 0) {
                    ZzzOTController ctrlr = (ZzzOTController) z;
                    _log.warn("Got start when another zzzot running, stopping him instead");
                    ctrlr.shutdown(null);
                } else {
                    _log.error("ZzzOT already running");
                }
                changeState(START_FAILED);
                return;
            }
        }
        if (_state != STOPPED && _state != INITIALIZED && _state != START_FAILED) {
            _log.error("Start while state = " + _state);
            return;
        }
        changeState(STARTING);
        try {
            start(_args);
            changeState(RUNNING);
            if (_mgr != null)
                _mgr.register(this);
        } catch (Exception e) {
            changeState(START_FAILED, "Start failed", e);
        }
    }

    /** @since 0.12.0 */
    public synchronized void shutdown(String[] args) {
        if (_state == STOPPED)
            return;
        changeState(STOPPING);
        if (_mgr != null)
            _mgr.unregister(this);
        stop();
        changeState(STOPPED);
    }

    /** @since 0.12.0 */
    public ClientAppState getState() {
        return _state;
    }

    /** @since 0.12.0 */
    public String getName() {
        return NAME;
    }

    /** @since 0.12.0 */
    public String getDisplayName() {
        return NAME;
    }

    /////// end ClientApp methods

    /** @since 0.17.0 */
    public static String getSiteName() {
        return _sitename;
    }

    /** @since 0.17.0 */
    public static String getVersion() {
        return VERSION;
    }

    /** @since 0.17.0 */
    public static boolean shouldShowFooter() {
        return _showfooter;
    }

    /** @since 0.17.0 */
    public static String footerText() {
        return _footertext;
    }

    /** @since 0.19.0 */
    public static boolean allowFullScrape() {
        return _fullScrape;
    }

    /** @since 0.12.0 */
    private synchronized void changeState(ClientAppState state) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, null, null);
    }

    /** @since 0.12.0 */
    private synchronized void changeState(ClientAppState state, String msg, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, msg, e);
    }
}
