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

    private ClientAppState _state = UNINITIALIZED;

    private static final String NAME = "ZzzOT";
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
        // SeedlessAnnouncer.announce(_tunnel);
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
        TunnelController tun = new TunnelController(i2ptunnelProps, "tunnel.0.");
        // start in foreground so we can get the destination
        //tun.startTunnelBackground();
        tun.startTunnel();
        if (dest != null) {
            List msgs = tun.clearMessages();
            for (Object s : msgs) {
                 _log.error("NOTICE: ZzzOT Tunnel message: " + s);
            }
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
     *  Migate a single jetty config file, replacing $PLUGIN as we copy it.
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
        String b32 = Base32.encode(dest.calculateHash().getData()) + ".b32.i2p";
        String b64 = dest.toBase64();
        try {
            String html = FileUtil.readTextFile(fileTmpl.getAbsolutePath(), 100, true);
            if (html == null)
                throw new IOException(fileTmpl.getAbsolutePath() + " open failed");
            html = html.replace("$PLUGIN", pluginDir.getAbsolutePath());
            html = html.replace("$B32", b32);
            html = html.replace("$B64", b64);
            FileOutputStream os = new FileOutputStream(outFile);
            os.write(html.getBytes("UTF-8"));
            os.close();
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
