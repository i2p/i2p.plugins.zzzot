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

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.apps.systray.UrlLauncher;

import org.eclipse.jetty.server.Server;
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
public class ZzzOTController {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(ZzzOTController.class);
    private static Server _server;
    private static TunnelController _tunnel;
    private static ZzzOT _zzzot;
    private static Object _lock = new Object();

    private static final String BACKUP_SUFFIX = ".jetty6";
    private static final String[] xmlFiles = {
        "jetty.xml", "contexts/base-context.xml", "contexts/cgi-context.xml",
        "etc/realm.properties", "etc/webdefault.xml" };

    public static void main(String args[]) {
        if (args.length != 3 || (!"-d".equals(args[0])))
            throw new IllegalArgumentException("Usage: PluginController -d $PLUGIN [start|stop]");
        if ("start".equals(args[2]))
            start(args);
        else if ("stop".equals(args[2]))
            stop();
        else
            throw new IllegalArgumentException("Usage: PluginController -d $PLUGIN [start|stop]");
    }

    public static Torrents getTorrents() {
        synchronized(_lock) {
            if (_zzzot == null)
                _zzzot = new ZzzOT();
        }
        return _zzzot.getTorrents();
    }

    private static void start(String args[]) {
        File pluginDir = new File(args[1]);
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
        // SeedlessAnnouncer.announce(_tunnel);
    }


    private static void startI2PTunnel(File pluginDir, Destination dest) {
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

    private static void startJetty(File pluginDir, Destination dest) {
        if (_server != null)
            throw new IllegalArgumentException("Jetty already running!");
        migrateJettyXML(pluginDir);
        I2PAppContext context = I2PAppContext.getGlobalContext();
        File tmpdir = new File(context.getTempDir().getAbsolutePath(), "/zzzot-work");
        tmpdir.mkdir();
        File jettyXml = new File(pluginDir, "jetty.xml");
        try {
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

    private static void stop() {
        stopI2PTunnel();
        stopJetty();
        if (_zzzot != null)
            _zzzot.stop();
    }

    private static void stopI2PTunnel() {
        if (_tunnel == null)
            return;
        try {
            _tunnel.stopTunnel();
        } catch (Throwable t) {
            _log.error("ZzzOT tunnel stop failed", t);
            throw new IllegalArgumentException("Tunnel stop failed " + t);
        }
        _tunnel = null;
    }

    private static void stopJetty() {
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
    private static void migrateJettyXML(File pluginDir) {
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
        String xml = FileUtil.readTextFile(f.getAbsolutePath(), 100, true);
        if (xml == null)
            return true;
        return xml.contains("class=\"org.mortbay.jetty.Server\"");
    }

    /**
     *  Backup a file and migrate new XML
     *  @return success
     *  @since Jetty 7
     */
    private static boolean backupAndMigrateFile(File toDir, String filename) {
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
    private static boolean migrateJettyFile(File pluginDir, String name) {
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
    private static void launchHelp(File pluginDir, Destination dest) {
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
}
