package org.briarproject.bramble.plugin.tcp;

import org.briarproject.bramble.PoliteExecutor;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.bramble.api.plugin.ConnectionHandler;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.bramble.api.rendezvous.RendezvousEndpoint;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.event.SettingsUpdatedEvent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static java.net.NetworkInterface.getNetworkInterfaces;
import static java.util.Collections.emptyList;
import static java.util.Collections.list;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.api.plugin.Plugin.State.DISABLED;
import static org.briarproject.bramble.api.plugin.Plugin.State.INACTIVE;
import static org.briarproject.bramble.api.plugin.TcpConstants.PREF_TCP_ENABLE;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.PrivacyUtils.scrubSocketAddress;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
abstract class TcpPlugin implements DuplexPlugin, EventListener {

	private static final Logger LOG = getLogger(TcpPlugin.class.getName());

	private static final Pattern DOTTED_QUAD =
			Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

	protected final Executor ioExecutor, bindExecutor;
	protected final PluginCallback callback;
	protected final int maxLatency, maxIdleTime, pollingInterval, socketTimeout;
	protected final AtomicBoolean used = new AtomicBoolean(false);
	protected final PluginState state = new PluginState();

	/**
	 * Returns zero or more socket addresses on which the plugin should listen,
	 * in order of preference. At most one of the addresses will be bound.
	 */
	protected abstract List<InetSocketAddress> getLocalSocketAddresses();

	/**
	 * Adds the address on which the plugin is listening to the transport
	 * properties.
	 */
	protected abstract void setLocalSocketAddress(InetSocketAddress a);

	/**
	 * Returns zero or more socket addresses for connecting to a contact with
	 * the given transport properties.
	 */
	protected abstract List<InetSocketAddress> getRemoteSocketAddresses(
			TransportProperties p);

	/**
	 * Returns true if connections to the given address can be attempted.
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	protected abstract boolean isConnectable(InetSocketAddress remote);

	TcpPlugin(Executor ioExecutor, PluginCallback callback, int maxLatency,
			int maxIdleTime, int pollingInterval) {
		this.ioExecutor = ioExecutor;
		this.callback = callback;
		this.maxLatency = maxLatency;
		this.maxIdleTime = maxIdleTime;
		this.pollingInterval = pollingInterval;
		if (maxIdleTime > Integer.MAX_VALUE / 2)
			socketTimeout = Integer.MAX_VALUE;
		else socketTimeout = maxIdleTime * 2;
		// Don't execute more than one bind operation at a time
		bindExecutor = new PoliteExecutor("TcpPlugin", ioExecutor, 1);
	}

	@Override
	public int getMaxLatency() {
		return maxLatency;
	}

	@Override
	public int getMaxIdleTime() {
		return maxIdleTime;
	}

	@Override
	public void start() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		Settings settings = callback.getSettings();
		state.setStarted(settings.getBoolean(PREF_TCP_ENABLE, false));
		bind();
	}

	protected void bind() {
		bindExecutor.execute(() -> {
			if (getState() != INACTIVE) return;
			ServerSocket ss = null;
			for (InetSocketAddress addr : getLocalSocketAddresses()) {
				try {
					ss = new ServerSocket();
					ss.bind(addr);
					break;
				} catch (IOException e) {
					if (LOG.isLoggable(INFO))
						LOG.info("Failed to bind " + scrubSocketAddress(addr));
					tryToClose(ss, LOG, WARNING);
				}
			}
			if (ss == null) {
				LOG.info("Could not bind server socket");
				return;
			}
			if (!state.setServerSocket(ss)) {
				LOG.info("Closing redundant server socket");
				tryToClose(ss, LOG, WARNING);
				return;
			}
			InetSocketAddress local =
					(InetSocketAddress) ss.getLocalSocketAddress();
			setLocalSocketAddress(local);
			if (LOG.isLoggable(INFO))
				LOG.info("Listening on " + scrubSocketAddress(local));
			acceptContactConnections(ss);
		});
	}

	String getIpPortString(InetSocketAddress a) {
		String addr = a.getAddress().getHostAddress();
		int percent = addr.indexOf('%');
		if (percent != -1) addr = addr.substring(0, percent);
		return addr + ":" + a.getPort();
	}

	private void acceptContactConnections(ServerSocket ss) {
		while (true) {
			Socket s;
			try {
				s = ss.accept();
				s.setSoTimeout(socketTimeout);
			} catch (IOException e) {
				// This is expected when the server socket is closed
				LOG.info("Server socket closed");
				state.clearServerSocket(ss);
				return;
			}
			if (LOG.isLoggable(INFO)) {
				LOG.info("Connection from " +
						scrubSocketAddress(s.getRemoteSocketAddress()));
			}
			callback.handleConnection(new TcpTransportConnection(this, s));
		}
	}

	@Override
	public void stop() {
		ServerSocket ss = state.setStopped();
		tryToClose(ss, LOG, WARNING);
	}

	@Override
	public State getState() {
		return state.getState();
	}

	@Override
	public int getReasonDisabled() {
		return state.getReasonDisabled();
	}

	@Override
	public boolean shouldPoll() {
		return true;
	}

	@Override
	public int getPollingInterval() {
		return pollingInterval;
	}

	@Override
	public void poll(Collection<Pair<TransportProperties, ConnectionHandler>>
			properties) {
		if (getState() != ACTIVE) return;
		for (Pair<TransportProperties, ConnectionHandler> p : properties) {
			connect(p.getFirst(), p.getSecond());
		}
	}

	private void connect(TransportProperties p, ConnectionHandler h) {
		ioExecutor.execute(() -> {
			DuplexTransportConnection d = createConnection(p);
			if (d != null) h.handleConnection(d);
		});
	}

	@Override
	public DuplexTransportConnection createConnection(TransportProperties p) {
		ServerSocket ss = state.getServerSocket();
		if (ss == null) return null;
		for (InetSocketAddress remote : getRemoteSocketAddresses(p)) {
			if (!isConnectable(remote)) {
				if (LOG.isLoggable(INFO)) {
					LOG.info(scrubSocketAddress(remote) +
							" is not connectable from " +
							scrubSocketAddress(ss.getLocalSocketAddress()));
				}
				continue;
			}
			try {
				if (LOG.isLoggable(INFO))
					LOG.info("Connecting to " + scrubSocketAddress(remote));
				Socket s = createSocket();
				s.bind(new InetSocketAddress(ss.getInetAddress(), 0));
				s.connect(remote);
				s.setSoTimeout(socketTimeout);
				if (LOG.isLoggable(INFO))
					LOG.info("Connected to " + scrubSocketAddress(remote));
				return new TcpTransportConnection(this, s);
			} catch (IOException e) {
				if (LOG.isLoggable(INFO))
					LOG.info("Could not connect to " +
							scrubSocketAddress(remote));
			}
		}
		return null;
	}

	protected Socket createSocket() throws IOException {
		return new Socket();
	}

	@Nullable
	InetSocketAddress parseSocketAddress(String ipPort) {
		if (isNullOrEmpty(ipPort)) return null;
		String[] split = ipPort.split(":");
		if (split.length != 2) return null;
		String addr = split[0], port = split[1];
		// Ensure getByName() won't perform a DNS lookup
		if (!DOTTED_QUAD.matcher(addr).matches()) return null;
		try {
			InetAddress a = InetAddress.getByName(addr);
			int p = Integer.parseInt(port);
			return new InetSocketAddress(a, p);
		} catch (UnknownHostException e) {
			if (LOG.isLoggable(WARNING))
				// not scrubbing to enable us to find the problem
				LOG.warning("Invalid address: " + addr);
			return null;
		} catch (NumberFormatException e) {
			if (LOG.isLoggable(WARNING))
				LOG.warning("Invalid port: " + port);
			return null;
		}
	}

	@Override
	public boolean supportsKeyAgreement() {
		return false;
	}

	@Override
	public KeyAgreementListener createKeyAgreementListener(byte[] commitment) {
		throw new UnsupportedOperationException();
	}

	@Override
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] commitment, BdfList descriptor) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean supportsRendezvous() {
		return false;
	}

	@Override
	public RendezvousEndpoint createRendezvousEndpoint(KeyMaterialSource k,
			boolean alice, ConnectionHandler incoming) {
		throw new UnsupportedOperationException();
	}

	Collection<InetAddress> getLocalIpAddresses() {
		try {
			Enumeration<NetworkInterface> ifaces = getNetworkInterfaces();
			if (ifaces == null) return emptyList();
			List<InetAddress> addrs = new ArrayList<>();
			for (NetworkInterface iface : list(ifaces))
				addrs.addAll(list(iface.getInetAddresses()));
			return addrs;
		} catch (SocketException e) {
			logException(LOG, WARNING, e);
			return emptyList();
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			if (s.getNamespace().equals(getId().getString()))
				ioExecutor.execute(() -> onSettingsUpdated(s.getSettings()));
		}
	}

	@IoExecutor
	private void onSettingsUpdated(Settings settings) {
		boolean enabledByUser = settings.getBoolean(PREF_TCP_ENABLE, false);
		ServerSocket ss = state.setEnabledByUser(enabledByUser);
		State s = getState();
		if (ss != null) {
			LOG.info("Disabled by user, closing server socket");
			tryToClose(ss, LOG, WARNING);
		} else if (s == INACTIVE) {
			LOG.info("Enabled by user, opening server socket");
			bind();
		}
	}

	@ThreadSafe
	@NotNullByDefault
	protected class PluginState {

		@GuardedBy("this")
		private boolean started = false, stopped = false, enabledByUser = false;

		@GuardedBy("this")
		@Nullable
		private ServerSocket serverSocket = null;

		synchronized void setStarted(boolean enabledByUser) {
			started = true;
			this.enabledByUser = enabledByUser;
			callback.pluginStateChanged(getState());
		}

		@Nullable
		synchronized ServerSocket setStopped() {
			stopped = true;
			ServerSocket ss = serverSocket;
			serverSocket = null;
			callback.pluginStateChanged(getState());
			return ss;
		}

		@Nullable
		synchronized ServerSocket setEnabledByUser(boolean enabledByUser) {
			this.enabledByUser = enabledByUser;
			ServerSocket ss = null;
			if (!enabledByUser) {
				ss = serverSocket;
				serverSocket = null;
			}
			callback.pluginStateChanged(getState());
			return ss;
		}

		@Nullable
		synchronized ServerSocket getServerSocket() {
			return serverSocket;
		}

		synchronized boolean setServerSocket(ServerSocket ss) {
			if (stopped || serverSocket != null) return false;
			serverSocket = ss;
			callback.pluginStateChanged(getState());
			return true;
		}

		synchronized void clearServerSocket(ServerSocket ss) {
			if (serverSocket == ss) serverSocket = null;
			callback.pluginStateChanged(getState());
		}

		synchronized State getState() {
			if (!started || stopped || !enabledByUser) return DISABLED;
			return serverSocket == null ? INACTIVE : ACTIVE;
		}

		synchronized int getReasonDisabled() {
			if (!started || stopped) return REASON_STARTING_STOPPING;
			return enabledByUser ? -1 : REASON_USER;
		}
	}
}
