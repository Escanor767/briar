package org.briarproject.bramble.plugin.tcp;

import android.content.Context;

import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexPluginFactory;

import java.util.concurrent.Executor;

import javax.annotation.concurrent.Immutable;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.ID;

@Immutable
@NotNullByDefault
public class AndroidLanTcpPluginFactory implements DuplexPluginFactory {

	private static final int MAX_LATENCY = (int) SECONDS.toMillis(30);
	private static final int MAX_IDLE_TIME = (int) SECONDS.toMillis(30);
	private static final int POLLING_INTERVAL = (int) MINUTES.toMillis(1);

	private final Executor ioExecutor;
	private final EventBus eventBus;
	private final Context appContext;

	public AndroidLanTcpPluginFactory(Executor ioExecutor, EventBus eventBus,
			Context appContext) {
		this.ioExecutor = ioExecutor;
		this.eventBus = eventBus;
		this.appContext = appContext;
	}

	@Override
	public TransportId getId() {
		return ID;
	}

	@Override
	public int getMaxLatency() {
		return MAX_LATENCY;
	}

	@Override
	public DuplexPlugin createPlugin(PluginCallback callback) {
		AndroidLanTcpPlugin plugin = new AndroidLanTcpPlugin(ioExecutor,
				appContext, callback, MAX_LATENCY, MAX_IDLE_TIME,
				POLLING_INTERVAL);
		eventBus.addListener(plugin);
		return plugin;
	}
}
