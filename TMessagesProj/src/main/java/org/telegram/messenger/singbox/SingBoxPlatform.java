package org.telegram.messenger.singbox;

import androidx.annotation.Nullable;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

/**
 * Minimal {@link PlatformInterface} for the SOCKS5-only, no-VPN mode.
 *
 * <p>Only the inbound side is ours (loopback SOCKS5), so TUN, shell, bridge, Tailscale and
 * notification hooks are never exercised and are stubbed out. Written against sing-box
 * {@code v1.14.4}; if the pinned libbox version is changed, re-check the method list in
 * {@code experimental/libbox/platform.go} of that tag.</p>
 */
public final class SingBoxPlatform implements PlatformInterface {

    private static final NetworkInterfaceIterator EMPTY_INTERFACES = new NetworkInterfaceIterator() {
        @Override
        public NetworkInterface next() {
            return null;
        }

        @Override
        public boolean hasNext() {
            return false;
        }
    };

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return false;
    }

    @Override
    public void autoDetectInterfaceControl(int fd) {
    }

    @Override
    public int openTun(TunOptions options) {
        throw new UnsupportedOperationException("tun inbound is not supported in the Telegram build");
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort,
                                               String destinationAddress, int destinationPort) {
        return null;
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        return EMPTY_INTERFACES;
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Nullable
    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public void clearDNSCache() {
    }

    @Override
    public void sendNotification(Notification notification) {
    }

    @Override
    public void cancelNotification(String identifier, int typeID) {
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public void registerMyInterface(String name) {
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public void checkPlatformShell() {
    }

    @Nullable
    @Override
    public ShellSession openShellSession(PlatformUser user, String command, StringIterator environ,
                                         String term, int rows, int cols) {
        return null;
    }

    @Nullable
    @Override
    public PlatformUser lookupUser(String username) {
        return null;
    }

    @Override
    public String lookupSFTPServer() {
        return "";
    }

    @Override
    public String readSystemSSHHostKey() {
        return "";
    }

    @Override
    public String tailscaleHostname() {
        return "";
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Nullable
    @Override
    public BridgeSession createBridge(BridgeOptions options) {
        return null;
    }
}
