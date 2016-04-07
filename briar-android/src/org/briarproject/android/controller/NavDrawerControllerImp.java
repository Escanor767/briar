package org.briarproject.android.controller;

import android.app.Activity;

import org.briarproject.android.api.ReferenceManager;
import org.briarproject.api.TransportId;
import org.briarproject.api.db.DbException;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.TransportDisabledEvent;
import org.briarproject.api.event.TransportEnabledEvent;
import org.briarproject.api.identity.IdentityManager;
import org.briarproject.api.identity.LocalAuthor;
import org.briarproject.api.plugins.Plugin;
import org.briarproject.api.plugins.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class NavDrawerControllerImp extends BriarControllerImp
		implements NavDrawerController, EventListener {

	private static final Logger LOG =
			Logger.getLogger(NavDrawerControllerImp.class.getName());

	@Inject
	protected ReferenceManager referenceManager;
	@Inject
	protected volatile IdentityManager identityManager;
	@Inject
	protected PluginManager pluginManager;
	@Inject
	protected volatile EventBus eventBus;
	@Inject
	protected Activity activity;

	private List<Plugin> transports = new ArrayList<Plugin>();

	private TransportStateListener transportStateListener;

	@Inject
	public NavDrawerControllerImp() {

	}

	@Override
	public void onActivityCreate() {
		super.onActivityCreate();
		initializeTransports();
	}

	@Override
	public void onActivityResume() {
		super.onActivityResume();
		eventBus.addListener(this);
	}

	@Override
	public void onActivityPause() {
		super.onActivityPause();
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof TransportEnabledEvent) {
			TransportId id = ((TransportEnabledEvent) e).getTransportId();
			if (LOG.isLoggable(INFO)) {
				LOG.info("TransportEnabledEvent: " + id.getString());
			}
			transportStateUpdate(id, true);
		} else if (e instanceof TransportDisabledEvent) {
			TransportId id = ((TransportDisabledEvent) e).getTransportId();
			if (LOG.isLoggable(INFO)) {
				LOG.info("TransportDisabledEvent: " + id.getString());
			}
			transportStateUpdate(id, false);
		}
	}

	private void transportStateUpdate(final TransportId id, final boolean enabled) {
		activity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (transportStateListener != null) {
					transportStateListener.stateUpdate(id, enabled);
				}
			}
		});
	}

	private void initializeTransports() {
		transports.clear();
		transports.add(pluginManager.getPlugin(new TransportId("tor")));
		transports.add(pluginManager.getPlugin(new TransportId("bt")));
		transports.add(pluginManager.getPlugin(new TransportId("lan")));
	}

	@Override
	public void setTransportListener(TransportStateListener transportListener) {
		this.transportStateListener = transportListener;
	}

	@Override
	public boolean transportRunning(TransportId transportId) {
		for (Plugin transport : transports) {
			if (transport.getId().equals(transportId)) {
				return transport.isRunning();
			}
		}
		return false;
	}

	@Override
	public void storeLocalAuthor(final LocalAuthor author,
			final ResultHandler<Void, DbException> resultHandler) {
		runOnDbThread(new Runnable() {
			public void run() {
				try {
					long now = System.currentTimeMillis();
					identityManager.addLocalAuthor(author);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Storing author took " + duration + " ms");
					activity.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							resultHandler.onResult(null);
						}
					});
				} catch (final DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);

					activity.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							resultHandler.onException(e);
						}
					});
				}
			}
		});
	}

	@Override
	public LocalAuthor removeAuthorHandle(long handle) {
		return referenceManager.removeReference(handle,
				LocalAuthor.class);
	}
}
