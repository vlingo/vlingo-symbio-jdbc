// Copyright © 2012-2020 VLINGO LABS. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.symbio.store.journal.jdbc;

import com.google.gson.Gson;
import io.vlingo.actors.Logger;
import io.vlingo.common.Failure;
import io.vlingo.common.Outcome;
import io.vlingo.common.Success;
import io.vlingo.common.identity.IdentityGenerator;
import io.vlingo.symbio.BaseEntry;
import io.vlingo.symbio.Entry;
import io.vlingo.symbio.State;
import io.vlingo.symbio.State.TextState;
import io.vlingo.symbio.store.Result;
import io.vlingo.symbio.store.StorageException;
import io.vlingo.symbio.store.common.jdbc.Configuration;
import io.vlingo.symbio.store.common.jdbc.DatabaseType;
import io.vlingo.symbio.store.dispatch.Dispatchable;
import io.vlingo.symbio.store.dispatch.Dispatcher;
import io.vlingo.symbio.store.dispatch.DispatcherControl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class JDBCJournalBatchWriter implements JDBCJournalWriter {
	private final Configuration configuration;
	private final Connection connection;
	private final JDBCQueries queries;
	private final List<Dispatcher<Dispatchable<Entry<String>, TextState>>> dispatchers;
	private final DispatcherControl dispatcherControl;
	private final Gson gson;
	private final IdentityGenerator dispatchablesIdentityGenerator;
	private final BatchEntries batchEntries;

	private Logger logger;

	public JDBCJournalBatchWriter(Configuration configuration, List<Dispatcher<Dispatchable<Entry<String>, TextState>>> dispatchers,
									DispatcherControl dispatcherControl, int maxBatchEntries) throws Exception {
		this.configuration = configuration;
		this.connection = configuration.connection;
		this.dispatchers = dispatchers;
		this.dispatcherControl = dispatcherControl;
		this.gson = new Gson();
		this.dispatchablesIdentityGenerator = new IdentityGenerator.RandomIdentityGenerator();
		this.batchEntries = new BatchEntries(maxBatchEntries);

		this.connection.setAutoCommit(false);
		this.queries = JDBCQueries.queriesFor(this.connection);
	}

	@Override
	public void appendEntry(String streamName, int streamVersion, Entry<String> entry, Optional<TextState> snapshotState,
							Consumer<Outcome<StorageException, Result>> postAppendAction) {
		batchEntries.addEntry(new SingleBatchEntry(streamName, streamVersion, entry, snapshotState, postAppendAction));
		if (batchEntries.capacityExceeded()) {
			flush();
		}
	}

	@Override
	public void appendEntries(String streamName, int fromStreamVersion, List<Entry<String>> entries, Optional<TextState> snapshotState,
							  Consumer<Outcome<StorageException, Result>> postAppendAction) {
		batchEntries.addEntry(new MultiBatchEntry(streamName, fromStreamVersion, entries, snapshotState, postAppendAction));
		if (batchEntries.capacityExceeded()) {
			flush();
		}
	}

	@Override
	public void flush() {
		if (batchEntries.size() > 0) {
			insertEntries();
			insertSnapshots();
			List<Dispatchable<Entry<String>, TextState>> dispatchables = insertDispatchables();
			doCommit();

			dispatch(dispatchables);
			batchEntries.completedWith(Success.of(Result.Success));
			batchEntries.clear();
		}
	}

	@Override
	public void stop() {
		// flush batched entries if any
		flush();

		if (dispatcherControl != null) {
			dispatcherControl.stop();
		}

		try {
			queries.close();
		} catch (SQLException e) {
			// ignore
		}
	}

	@Override
	public void setLogger(Logger logger) {
		this.logger = logger;
	}

	private String buildDispatchId(String streamName, int streamVersion) {
		return streamName + ":" + streamVersion + ":" + dispatchablesIdentityGenerator.generate().toString();
	}

	private List<Dispatchable<Entry<String>, TextState>> insertDispatchables() {
		List<Dispatchable<Entry<String>, TextState>> dispatchables = new ArrayList<>();
		String databaseType = configuration.databaseType.toString();
		LocalDateTime now = LocalDateTime.now();
		PreparedStatement insertDispatchable = null;

		try {
			for (AbstractBatchEntry batchEntry : batchEntries.entries) {
				final String id = buildDispatchId(batchEntry.streamName, batchEntry.streamVersion);
				final Dispatchable<Entry<String>, TextState> dispatchable = new Dispatchable<>(id, now, batchEntry.snapshotState.orElse(null), batchEntry.entries());
				final String encodedEntries = dispatchable.hasEntries() ?
						dispatchable.entries().stream()
								.map(Entry::id)
								.collect(Collectors.joining(JDBCDispatcherControlDelegate.DISPATCHEABLE_ENTRIES_DELIMITER)) : "";

				dispatchables.add(dispatchable);

				if (dispatchable.state().isPresent()) {
					final State<String> state = dispatchable.typedState();

					insertDispatchable = queries.prepareInsertDispatchableQuery(
									id,
									configuration.originatorId,
									state.id,
									state.data,
									state.dataVersion,
									state.type,
									state.typeVersion,
									gson.toJson(state.metadata),
									encodedEntries)._1;
				} else {
					insertDispatchable = queries.prepareInsertDispatchableQuery(
									id,
									configuration.originatorId,
									null,
									null,
									0,
									null,
									0,
									null,
									encodedEntries)._1;
				}

				insertDispatchable.addBatch();
			}

			if (insertDispatchable == null) {
				return new ArrayList<>();
			}

			final int[] countList = insertDispatchable.executeBatch();
			if (Arrays.stream(countList).anyMatch(id -> id == -1L)) {
				final String message = "vlingo-symbio-jdbc:journal-" + databaseType + ": Batch dispatchables write failed to insert row.";
				logger.error(message);
				throw new IllegalStateException(message);
			}

			return dispatchables;
		} catch (Exception e) {
			batchEntries.completedWith(Failure.of(new StorageException(Result.Failure, e.getMessage(), e)));
			logger.error("vlingo-symbio-jdbc:journal-" + databaseType + ": Failed to batch insert dispatchables.", e);
			throw new IllegalStateException(e);
		} finally {
			if (insertDispatchable != null) {
				try {
					insertDispatchable.clearBatch();
				} catch (SQLException e) {
					errorOccurred(e, "vlingo-symbio-jdbc:journal-" + databaseType + ": Failed to clean dispatchables batch.");
				}
			}
		}
	}

	private void dispatch(final List<Dispatchable<Entry<String>, TextState>> dispatchables) {
		if (dispatchers != null) {
			// dispatch only if insert successful
			this.dispatchers.forEach(dispatcher -> dispatchables.forEach(dispatchable -> dispatcher.dispatch(dispatchable)));
		}
	}

	private void insertEntries() {
		final DatabaseType databaseType = configuration.databaseType;
		List<InsertEntry> insertEntries = batchEntries.collectEntries();
		PreparedStatement insertStatement = null;

		try {
			for (InsertEntry insertEntry : insertEntries) {
				insertStatement = queries.prepareInsertEntryQuery(
						insertEntry.streamName,
						insertEntry.streamVersion,
						insertEntry.entry.entryData(),
						insertEntry.entry.typeName(),
						insertEntry.entry.typeVersion(),
						gson.toJson(insertEntry.entry.metadata()))._1;

				insertStatement.addBatch();
			}

			if (insertStatement == null) {
				return;
			}

			final int[] countList = insertStatement.executeBatch();
			ResultSet resultSet = insertStatement.getGeneratedKeys();
			for (int i = 0; resultSet.next(); i++) {
				if (countList[i] == -1L) {
					final String message = "vlingo-symbio-jdbc:journal-" + databaseType + "Batch write failed to insert row.";
					logger.error(message);
					throw new IllegalStateException(message);
				}

				long id = resultSet.getLong(1);
				((BaseEntry<String>) insertEntries.get(i).entry).__internal__setId(String.valueOf(id));
			}
		} catch (final SQLException e) {
			batchEntries.completedWith(Failure.of(new StorageException(Result.Failure, e.getMessage(), e)));
			logger.error("vlingo-symbio-jdbc:journal-" + databaseType +": Failed to batch insert entries.", e);
			throw new IllegalStateException(e);
		} finally {
			if (insertStatement != null) {
				try {
					insertStatement.clearBatch();
				} catch (SQLException e) {
					errorOccurred(e, "vlingo-symbio-jdbc:journal-" + databaseType + ": Failed to clean entries batch.");
				}
			}
		}
	}

	private void insertSnapshots() {
		DatabaseType databaseType = configuration.databaseType;
		PreparedStatement insertStatement = null;

		try {
			for (AbstractBatchEntry batchEntry : batchEntries.entries) {
				if (batchEntry.snapshotState.isPresent()) {
					insertStatement = queries.prepareInsertSnapshotQuery(
							batchEntry.streamName,
							batchEntry.streamVersion,
							batchEntry.snapshotState.get().data,
							batchEntry.snapshotState.get().dataVersion,
							batchEntry.snapshotState.get().type,
							batchEntry.snapshotState.get().typeVersion,
							gson.toJson(batchEntry.snapshotState.get().metadata))._1;
					insertStatement.addBatch();
				}
			}

			if (insertStatement == null) {
				return;
			}

			final int[] countList = insertStatement.executeBatch();
			if (Arrays.stream(countList).anyMatch(id -> id == -1L)) {
				final String message = "vlingo-symbio-jdbc:journal-" + databaseType + ": Journal batch snapshots write failed to insert row.";
				logger.error(message);
				throw new IllegalStateException(message);
			}
		} catch (Exception e) {
			errorOccurred(e, "vlingo-symbio-jdbc:journal-" + databaseType + ": Journal batch snapshots write failed.");
		} finally {
			if (insertStatement != null) {
				try {
					insertStatement.clearBatch();
				} catch (SQLException e) {
					errorOccurred(e, "vlingo-symbio-jdbc:journal-" + databaseType + ": Failed to clean snapshots batch.");
				}
			}
		}
	}

	private void doCommit() {
		try {
			configuration.connection.commit();
		} catch (final SQLException e) {
			errorOccurred(e, "vlingo-symbio-jdbc:journal-" + configuration.databaseType + ": Could not complete transaction");
		}
	}

	private void errorOccurred(Exception e, String message) {
		batchEntries.completedWith(Failure.of(new StorageException(Result.Failure, e.getMessage(), e)));
		logger.error(message, e);
		throw new IllegalArgumentException(message);
	}

	static class InsertEntry {
		final String streamName;
		final int streamVersion;
		final Entry<String> entry;

		InsertEntry(String streamName, int streamVersion, Entry<String> entry) {
			this.streamName = streamName;
			this.streamVersion = streamVersion;
			this.entry = entry;
		}
	}

	static class BatchEntries {
		private final List<AbstractBatchEntry> entries;
		private int size;

		private final int maxCapacity;

		BatchEntries(int maxCapacity) {
			if (maxCapacity <= 0) {
				throw new IllegalArgumentException("Illegal capacity: " + maxCapacity);
			}

			this.entries = new ArrayList<>(maxCapacity);
			this.size = 0;
			this.maxCapacity = maxCapacity;
		}

		void addEntry(AbstractBatchEntry entry) {
			entries.add(entry);
			size += entry.size();
		}

		boolean capacityExceeded() {
			return size >= maxCapacity;
		}

		void completedWith(Outcome<StorageException, Result> outcome) {
			entries.forEach(e -> e.completedWith(outcome));
		}

		void clear() {
			entries.clear();
			size = 0;
		}

		List<InsertEntry> collectEntries() {
			return entries.stream()
					.flatMap(e -> e.insertEntries().stream())
					.collect(Collectors.toList());

		}

		int size() {
			return size;
		}

	}

	abstract static class AbstractBatchEntry {
		final String streamName;
		final int streamVersion;
		final Optional<TextState> snapshotState;
		final Consumer<Outcome<StorageException, Result>> postAppendAction;

		abstract int size();
		abstract List<InsertEntry> insertEntries();
		abstract List<Entry<String>> entries();

		AbstractBatchEntry(String streamName, int streamVersion, Optional<TextState> snapshotState,
						   Consumer<Outcome<StorageException, Result>> postAppendAction) {
			this.streamName = streamName;
			this.streamVersion = streamVersion;
			this.snapshotState = snapshotState;
			this.postAppendAction = postAppendAction;
		}

		void completedWith(Outcome<StorageException, Result> outcome) {
			postAppendAction.accept(outcome);
		}

		Optional<TextState> snapshotState() {
			return snapshotState;
		}
	}

	static class SingleBatchEntry extends AbstractBatchEntry {
		final Entry<String> entry;

		SingleBatchEntry(String streamName, int streamVersion, Entry<String> entry, Optional<TextState> snapshotState,
						 Consumer<Outcome<StorageException, Result>> postAppendAction) {
			super(streamName, streamVersion, snapshotState, postAppendAction);
			this.entry = entry;
		}

		@Override
		List<Entry<String>> entries() {
			return Collections.singletonList(entry);
		}

		@Override
		List<InsertEntry> insertEntries() {
			return Collections.singletonList(new InsertEntry(streamName, streamVersion, entry));
		}

		@Override
		int size() {
			return 1;
		}
	}

	static class MultiBatchEntry extends AbstractBatchEntry {
		final List<Entry<String>> entries;

		MultiBatchEntry(String streamName, int fromStreamVersion, List<Entry<String>> entries, Optional<TextState> snapshotState,
						Consumer<Outcome<StorageException, Result>> postAppendAction) {
			super(streamName, fromStreamVersion, snapshotState, postAppendAction);
			this.entries = entries;
		}

		@Override
		List<Entry<String>> entries() {
			return entries;
		}

		@Override
		List<InsertEntry> insertEntries() {
			List<InsertEntry> batchEntries = new ArrayList<>();
			int currentVersion = streamVersion;

			for (Entry<String> entry : entries) {
				batchEntries.add(new InsertEntry(streamName, currentVersion++, entry));
			}

			return batchEntries;
		}

		@Override
		int size() {
			return entries.size();
		}
	}
}
