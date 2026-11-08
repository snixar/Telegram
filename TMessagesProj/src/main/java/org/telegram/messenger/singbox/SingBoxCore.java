package org.telegram.messenger.singbox;

import android.content.Context;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SetupOptions;
import io.nekohasekai.libbox.SystemProxyStatus;

/**
 * In-process sing-box runtime.
 *
 * <p>Telegram's native layer (tgnet) is pointed at {@code 127.0.0.1:<port>} and speaks SOCKS5;
 * sing-box accepts that connection and forwards it through whatever outbound the user configured.
 * The core lives inside the Telegram process, so it is kept alive by Telegram's own foreground
 * service and needs no {@code VpnService} and no separate app.</p>
 *
 * <p>Only arm64 is bundled (see {@code Tools/build-singbox-aar.sh}); on other ABIs the feature
 * reports itself as unavailable instead of crashing.</p>
 */
public final class SingBoxCore implements CommandServerHandler {

    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING = 1;
    private static final int STATE_RUNNING = 2;
    private static final int STATE_FAILED = 3;

    private static final int MAX_ATTEMPTS = 3;

    private static volatile SingBoxCore instance;
    private static volatile boolean supportedChecked;
    private static volatile boolean supported;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "sing-box");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final Object lock = new Object();

    private volatile CommandServer commandServer;
    private volatile int localPort;
    private volatile String config;
    private volatile int state = STATE_IDLE;
    private volatile boolean setupDone;
    private volatile int attempt;
    private volatile int generation;
    private volatile String lastError;

    private SingBoxCore() {
    }

    public static SingBoxCore getInstance() {
        SingBoxCore local = instance;
        if (local == null) {
            synchronized (SingBoxCore.class) {
                local = instance;
                if (local == null) {
                    local = new SingBoxCore();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static boolean isSupported() {
        if (!supportedChecked) {
            boolean arm64 = false;
            for (String abi : Build.SUPPORTED_ABIS) {
                if ("arm64-v8a".equals(abi)) {
                    arm64 = true;
                    break;
                }
            }
            supported = arm64 && Build.VERSION.SDK_INT >= 23;
            supportedChecked = true;
        }
        return supported;
    }

    /**
     * Ensures the core is running with {@code json} and returns the local SOCKS5 port, or {@code 0}
     * when the feature is unavailable. The port is allocated before the core binds, so callers may
     * point tgnet at it immediately; connections simply retry until sing-box is listening.
     */
    public static int ensureRunning(String json) {
        return getInstance().ensureRunningInternal(json);
    }

    public static void stop() {
        getInstance().stopInternal();
    }

    public static int getLocalPort() {
        return getInstance().localPort;
    }

    /** @return the port only when the core is already running with exactly this config. */
    public static int getActivePort(String json) {
        final SingBoxCore core = getInstance();
        if (json != null && json.equals(core.config) && core.state == STATE_RUNNING) {
            return core.localPort;
        }
        return 0;
    }

    public static boolean isRunning() {
        return getInstance().state == STATE_RUNNING;
    }

    public static boolean isFailed() {
        return getInstance().state == STATE_FAILED;
    }

    /** Last sing-box error, or {@code null} when the core is healthy. */
    public static String getLastError() {
        return getInstance().lastError;
    }

    /** Called when the default network comes back; restarts a core that failed earlier. */
    public static void onNetworkAvailable() {
        getInstance().onNetworkAvailableInternal();
    }

    private int ensureRunningInternal(String json) {
        if (json == null || json.isEmpty() || !isSupported()) {
            return 0;
        }
        synchronized (lock) {
            if ((state == STATE_RUNNING || state == STATE_STARTING) && json.equals(config)) {
                return localPort;
            }
            if (state == STATE_FAILED && json.equals(config)) {
                // Do not hammer a config that already failed. Returning 0 makes the caller fall
                // back to a direct connection instead of pointing tgnet at a dead local port.
                return 0;
            }
            if (localPort == 0) {
                localPort = allocatePort();
                if (localPort == 0) {
                    FileLog.e("sing-box: no free local port");
                    return 0;
                }
            }
            config = json;
            attempt = 0;
            state = STATE_STARTING;
            final int startGeneration = ++generation;
            final String startConfig = json;
            final int startPort = localPort;
            executor.execute(() -> startBlocking(startConfig, startPort, startGeneration));
            return localPort;
        }
    }

    private void stopInternal() {
        synchronized (lock) {
            generation++;
            state = STATE_IDLE;
            config = null;
            attempt = 0;
            lastError = null;
        }
        final CommandServer server = commandServer;
        commandServer = null;
        if (server != null) {
            executor.execute(() -> {
                try {
                    server.closeService();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                try {
                    server.close();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }
    }

    private void onNetworkAvailableInternal() {
        if (!isSupported() || state != STATE_FAILED || config == null) {
            return;
        }
        synchronized (lock) {
            state = STATE_IDLE;
        }
        ensureRunningInternal(config);
    }

    private void startBlocking(String startConfig, int startPort, int startGeneration) {
        while (attempt < MAX_ATTEMPTS) {
            if (startGeneration != generation) {
                return;
            }
            attempt++;
            try {
                if (state == STATE_IDLE || config == null || !startConfig.equals(config)) {
                    return;
                }
                if (!setupDone) {
                    Libbox.setup(buildSetupOptions());
                    setupDone = true;
                }
                if (commandServer == null) {
                    commandServer = new CommandServer(this, new SingBoxPlatform());
                    commandServer.start();
                }
                commandServer.startOrReloadService(SingBoxConfig.prepare(startConfig, startPort), new OverrideOptions());
                if (startGeneration != generation) {
                    return;
                }
                final boolean recovering = lastError != null;
                lastError = null;
                state = STATE_RUNNING;
                FileLog.d("sing-box: running on 127.0.0.1:" + startPort + ", attempt " + attempt);
                if (recovering) {
                    restoreProxy();
                }
                return;
            } catch (Throwable t) {
                FileLog.e("sing-box: start attempt " + attempt + " failed", t);
                if (attempt >= MAX_ATTEMPTS) {
                    lastError = t.getMessage() != null ? t.getMessage() : t.toString();
                    state = STATE_FAILED;
                    // Never leave tgnet pointed at a dead loopback port: fall back to a direct
                    // connection until the user fixes the configuration.
                    fallbackToDirect();
                    return;
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void fallbackToDirect() {
        try {
            ConnectionsManager.resetProxyToDirect();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void restoreProxy() {
        try {
            final String json = config;
            if (json == null) {
                return;
            }
            final Context context = ApplicationLoader.applicationContext;
            if (!context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                    .getBoolean("proxy_enabled", false)) {
                return;
            }
            final SingBoxConfig.Summary summary = SingBoxConfig.summarizeOrNull(json);
            final ProxySettings settings = ProxySettings.builder()
                    .setType(ProxySettings.Type.SINGBOX)
                    .setAddress(summary != null && summary.server != null ? summary.server : "")
                    .setPort(summary != null ? summary.port : 0)
                    .setSecret(json)
                    .build();
            ConnectionsManager.setProxySettings(true, settings);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private SetupOptions buildSetupOptions() {
        final Context context = ApplicationLoader.applicationContext;
        final SetupOptions options = new SetupOptions();
        options.basePath = context.getFilesDir().getAbsolutePath();
        final File working = new File(context.getFilesDir(), "singbox");
        //noinspection ResultOfMethodCallIgnored
        working.mkdirs();
        options.workingPath = working.getAbsolutePath();
        options.tempPath = context.getCacheDir().getAbsolutePath();
        options.logMaxLines = 500;
        options.debug = false;
        options.crashReportSource = "Telegram";
        options.appVersion = BuildVars.BUILD_VERSION_STRING;
        options.appMarketingVersion = BuildVars.BUILD_VERSION_STRING;
        options.platformMetadata = "{\"os\":\"Android " + Build.VERSION.RELEASE + "\",\"sdk\":" + Build.VERSION.SDK_INT
                + ",\"manufacturer\":\"" + Build.MANUFACTURER + "\",\"model\":\"" + Build.MODEL + "\"}";
        return options;
    }

    private static int allocatePort() {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            return socket.getLocalPort();
        } catch (IOException e) {
            FileLog.e(e);
            return 0;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // region CommandServerHandler

    @Override
    public void serviceStop() {
        synchronized (lock) {
            generation++;
            state = STATE_IDLE;
            config = null;
        }
    }

    @Override
    public void serviceReload() {
        final String reloadConfig = config;
        final int reloadPort = localPort;
        if (reloadConfig != null && reloadPort != 0) {
            attempt = 0;
            final int reloadGeneration = generation;
            executor.execute(() -> startBlocking(reloadConfig, reloadPort, reloadGeneration));
        }
    }

    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        return null;
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) {
    }

    @Override
    public void triggerNativeCrash() {
    }

    @Override
    public void writeDebugMessage(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("sing-box: " + message);
        }
    }

    @Override
    public int connectSSHAgent() {
        return -1;
    }

    // endregion
}
