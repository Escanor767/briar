package org.briarproject.bramble.plugin.bluetooth;

import org.briarproject.bramble.api.io.TimeoutMonitor;
import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.bramble.api.plugin.Backoff;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.LocalDevice;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.StringUtils.isValidMac;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class JavaBluetoothPlugin extends BluetoothPlugin<StreamConnectionNotifier> {

	private static final Logger LOG =
			getLogger(JavaBluetoothPlugin.class.getName());

	// Non-null if the plugin started successfully
	private volatile LocalDevice localDevice = null;

	JavaBluetoothPlugin(BluetoothConnectionLimiter connectionManager,
			TimeoutMonitor timeoutMonitor, Executor ioExecutor,
			SecureRandom secureRandom, Backoff backoff,
			PluginCallback callback, int maxLatency, int maxIdleTime) {
		super(connectionManager, timeoutMonitor, ioExecutor, secureRandom,
				backoff, callback, maxLatency, maxIdleTime);
	}

	@Override
	void initialiseAdapter() throws IOException {
		try {
			localDevice = LocalDevice.getLocalDevice();
		} catch (UnsatisfiedLinkError | BluetoothStateException e) {
			throw new IOException(e);
		}
	}

	@Override
	boolean isAdapterEnabled() {
		return localDevice != null && LocalDevice.isPowerOn();
	}

	@Override
	void enableAdapter() {
		// Nothing we can do on this platform
		LOG.info("Could not enable Bluetooth");
	}

	@Override
	void disableAdapterIfEnabledByUs() {
		// We didn't enable it so we don't need to disable it
	}

	@Override
	void setEnabledByUs() {
		// Irrelevant on this platform
	}

	@Nullable
	@Override
	String getBluetoothAddress() {
		return localDevice.getBluetoothAddress();
	}

	@Override
	StreamConnectionNotifier openServerSocket(String uuid) throws IOException {
		String url = makeUrl("localhost", uuid);
		return (StreamConnectionNotifier) Connector.open(url);
	}

	@Override
	void tryToClose(@Nullable StreamConnectionNotifier ss) {
		try {
			if (ss != null) ss.close();
		} catch (IOException e) {
			logException(LOG, WARNING, e);
		}
	}

	@Override
	DuplexTransportConnection acceptConnection(StreamConnectionNotifier ss)
			throws IOException {
		return wrapSocket(ss.acceptAndOpen());
	}

	@Override
	boolean isValidAddress(String address) {
		return isValidMac(address);
	}

	@Override
	DuplexTransportConnection connectTo(String address, String uuid)
			throws IOException {
		String url = makeUrl(address, uuid);
		return wrapSocket((StreamConnection) Connector.open(url));
	}

	@Override
	@Nullable
	DuplexTransportConnection discoverAndConnect(String uuid) {
		return null; // TODO
	}

	private String makeUrl(String address, String uuid) {
		return "btspp://" + address + ":" + uuid + ";name=RFCOMM";
	}

	private DuplexTransportConnection wrapSocket(StreamConnection s)
			throws IOException {
		return new JavaBluetoothTransportConnection(this, connectionLimiter,
				timeoutMonitor, s);
	}
}
