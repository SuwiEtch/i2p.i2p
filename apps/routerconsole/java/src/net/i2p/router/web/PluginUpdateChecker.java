package net.i2p.router.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PartialEepGet;
import net.i2p.util.VersionComparator;

/**
 * Download and install a plugin.
 * A plugin is a standard .sud file with a 40-byte signature,
 * a 16-byte version, and a .zip file.
 * Unlike for router updates, we need not have the public key
 * for the signature in advance.
 *
 * The zip file must have a standard directory layout, with
 * a plugin.config file at the top level.
 * The config file contains properties for the package name, version,
 * signing public key, and other settings.
 * The zip file will typically contain a webapps/ or lib/ dir,
 * and a webapps.config and/or clients.config file.
 *
 * @since 0.7.12
 * @author zzz
 */
public class PluginUpdateChecker extends UpdateHandler {
    private static PluginUpdateCheckerRunner _pluginUpdateCheckerRunner;
    private String _appName;
    private String _oldVersion;
    private String _xpi2pURL;

    private static PluginUpdateChecker _instance;
    public static final synchronized PluginUpdateChecker getInstance(RouterContext ctx) { 
        if (_instance != null)
            return _instance;
        _instance = new PluginUpdateChecker(ctx);
        return _instance;
    }

    private PluginUpdateChecker(RouterContext ctx) {
        super(ctx);
    }
    
    public void update(String appName) {
        // don't block waiting for the other one to finish
        if ("true".equals(System.getProperty(PROP_UPDATE_IN_PROGRESS))) {
            _log.error("Update already running");
            return;
        }
        synchronized (UpdateHandler.class) {
            Properties props = PluginStarter.pluginProperties(_context, appName);
            String oldVersion = props.getProperty("version");
            String xpi2pURL = props.getProperty("updateURL");
            if (oldVersion == null || xpi2pURL == null) {
                updateStatus("<b>" + _("Cannot check, plugin {0} is not installed", appName) + "</b>");
                return;
            }

            if (_pluginUpdateCheckerRunner == null)
                _pluginUpdateCheckerRunner = new PluginUpdateCheckerRunner();
            if (_pluginUpdateCheckerRunner.isRunning())
                return;
            _xpi2pURL = xpi2pURL;
            _appName = appName;
            _oldVersion = oldVersion;
            System.setProperty(PROP_UPDATE_IN_PROGRESS, "true");
            I2PAppThread update = new I2PAppThread(_pluginUpdateCheckerRunner, "AppChecker", true);
            update.start();
        }
    }
    
    public boolean isRunning() {
        return _pluginUpdateCheckerRunner != null && _pluginUpdateCheckerRunner.isRunning();
    }
    
    @Override
    public boolean isDone() {
        // FIXME
        return false;
    }
    
    public class PluginUpdateCheckerRunner extends UpdateRunner implements Runnable, EepGet.StatusListener {
        ByteArrayOutputStream _baos;

        public PluginUpdateCheckerRunner() { 
            super();
            _baos = new ByteArrayOutputStream(TrustedUpdate.HEADER_BYTES);
        }

        @Override
        protected void update() {
            updateStatus("<b>" + _("Checking for update of plugin {0}", _appName) + "</b>");
            // use the same settings as for updater
            boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
            String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
            int proxyPort = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT, ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT);
            try {
                _get = new PartialEepGet(_context, proxyHost, proxyPort, _baos, _xpi2pURL, TrustedUpdate.HEADER_BYTES);
                _get.addStatusListener(PluginUpdateCheckerRunner.this);
                _get.fetch();
            } catch (Throwable t) {
                _log.error("Error checking update for plugin", t);
            }
        }
        
        @Override
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        }

        @Override
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            String newVersion = TrustedUpdate.getVersionString(new ByteArrayInputStream(_baos.toByteArray()));
            boolean newer = (new VersionComparator()).compare(newVersion, _oldVersion) > 0;
            if (newer)
                updateStatus("<b>" + _("New plugin version {0} is available", newVersion) + "</b>");
            else
                updateStatus("<b>" + _("No new version is available for plugin {0}", _appName) + "</b>");
        }

        @Override
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            File f = new File(_updateFile);
            f.delete();
            updateStatus("<b>" + _("Update check failed for plugin {0}", _appName) + "</b>");
        }
    }
}
    
