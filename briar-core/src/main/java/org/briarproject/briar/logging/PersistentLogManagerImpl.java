package org.briarproject.briar.logging;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.ShutdownManager;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.sync.Client;
import org.briarproject.bramble.api.system.Scheduler;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.api.transport.StreamWriterFactory;
import org.briarproject.briar.api.logging.PersistentLogManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.util.LogUtils.logException;

@ThreadSafe
@NotNullByDefault
class PersistentLogManagerImpl implements PersistentLogManager, Client {

	private static final Logger LOG =
			Logger.getLogger(PersistentLogManagerImpl.class.getName());

	private static final String LOG_FILE = "briar.log";
	private static final String OLD_LOG_FILE = "briar.log.old";
	private static final long FLUSH_INTERVAL_MS = MINUTES.toMillis(5);

	private final ScheduledExecutorService scheduler;
	private final Executor ioExecutor;
	private final ShutdownManager shutdownManager;
	private final DatabaseComponent db;
	private final StreamReaderFactory streamReaderFactory;
	private final StreamWriterFactory streamWriterFactory;
	private final Formatter formatter;
	private final SecretKey logKey;

	@Nullable
	private volatile SecretKey oldLogKey = null;

	@Inject
	PersistentLogManagerImpl(
			@Scheduler ScheduledExecutorService scheduler,
			@IoExecutor Executor ioExecutor,
			ShutdownManager shutdownManager,
			DatabaseComponent db,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			Formatter formatter,
			CryptoComponent crypto) {
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		this.shutdownManager = shutdownManager;
		this.db = db;
		this.streamReaderFactory = streamReaderFactory;
		this.streamWriterFactory = streamWriterFactory;
		this.formatter = formatter;
		logKey = crypto.generateSecretKey();
	}

	@Override
	public void createLocalState(Transaction txn) throws DbException {
		Settings s = db.getSettings(txn, LOG_SETTINGS_NAMESPACE);
		// Load the old log key, if any
		byte[] oldKeyBytes = s.getBytes(LOG_KEY_KEY);
		if (oldKeyBytes != null && oldKeyBytes.length == SecretKey.LENGTH) {
			LOG.info("Loaded old log key");
			oldLogKey = new SecretKey(oldKeyBytes);
		}
		// Store the current log key
		s.putBytes(LOG_KEY_KEY, logKey.getBytes());
		db.mergeSettings(txn, s, LOG_SETTINGS_NAMESPACE);
	}

	@Override
	public Handler createLogHandler(File dir) throws IOException {
		File logFile = new File(dir, LOG_FILE);
		File oldLogFile = new File(dir, OLD_LOG_FILE);
		if (oldLogFile.exists() && !oldLogFile.delete())
			LOG.warning("Failed to delete old log file");
		if (logFile.exists() && !logFile.renameTo(oldLogFile))
			LOG.warning("Failed to rename log file");
		try {
			OutputStream out = new FileOutputStream(logFile);
			StreamWriter writer =
					streamWriterFactory.createLogStreamWriter(out, logKey);
			StreamHandler handler =
					new StreamHandler(writer.getOutputStream(), formatter);
			// Flush the log periodically in case we're killed without getting
			// the chance to run shutdown hooks
			scheduler.scheduleWithFixedDelay(() ->
							ioExecutor.execute(handler::flush),
					FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, MILLISECONDS);
			// Flush the log and terminate the stream at shutdown
			shutdownManager.addShutdownHook(() -> {
				handler.flush();
				try {
					writer.sendEndOfStream();
				} catch (IOException e) {
					logException(LOG, WARNING, e);
				}
			});
			return handler;
		} catch (SecurityException e) {
			throw new IOException(e);
		}
	}

	@Override
	public Collection<String> getPersistedLog(File dir) throws IOException {
		SecretKey oldLogKey = this.oldLogKey;
		if (oldLogKey == null) {
			LOG.info("Old log key has not been loaded");
			return emptyList();
		}
		File oldLogFile = new File(dir, OLD_LOG_FILE);
		if (oldLogFile.exists()) {
			LOG.info("Reading old log file");
			List<String> lines = new ArrayList<>();
			try (InputStream in = new FileInputStream(oldLogFile)) {
				InputStream reader = streamReaderFactory
						.createLogStreamReader(in, oldLogKey);
				Scanner s = new Scanner(reader);
				while (s.hasNextLine()) lines.add(s.nextLine());
				s.close();
				return lines;
			}
		} else {
			LOG.info("Old log file does not exist");
			return emptyList();
		}
	}
}
