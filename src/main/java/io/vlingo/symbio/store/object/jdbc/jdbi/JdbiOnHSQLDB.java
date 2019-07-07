// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.symbio.store.object.jdbc.jdbi;

import com.google.gson.reflect.TypeToken;
import io.vlingo.actors.World;
import io.vlingo.common.serialization.JsonSerialization;
import io.vlingo.symbio.BaseEntry.TextEntry;
import io.vlingo.symbio.Entry;
import io.vlingo.symbio.Metadata;
import io.vlingo.symbio.State;
import io.vlingo.symbio.store.common.jdbc.Configuration;
import io.vlingo.symbio.store.dispatch.Dispatchable;
import io.vlingo.symbio.store.dispatch.Dispatcher;
import io.vlingo.symbio.store.object.ObjectStore;
import io.vlingo.symbio.store.object.PersistentEntry;
import io.vlingo.symbio.store.object.PersistentObjectMapper;
import io.vlingo.symbio.store.object.QueryExpression;
import io.vlingo.symbio.store.object.jdbc.JDBCObjectStoreActor;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.SqlStatement;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

public class JdbiOnHSQLDB {
  public final Configuration configuration;
  public final Handle handle;
  private ObjectStore objectStore;

  public static JdbiOnHSQLDB openUsing(final Configuration configuration) {
    return new JdbiOnHSQLDB(configuration);
  }

  public void createTextEntryJournalTable() {
    handle.execute("CREATE TABLE ENTRY_JOURNAL (E_ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, E_TYPE VARCHAR(1024), E_TYPE_VERSION INTEGER, E_DATA VARCHAR(8000), E_METADATA_VALUE VARCHAR(8000) NULL, E_METADATA_OP VARCHAR(128) NULL)");
  }

  public EntryMapper entryMapper() {
    return new EntryMapper();
  }

  public ObjectStore objectStore(final World world, final Dispatcher<Dispatchable<TextEntry, State.TextState>> dispatcher) {
    if (objectStore == null) {
      final JdbiObjectStoreDelegate delegate = new JdbiObjectStoreDelegate(configuration, unconfirmedDispatchablesQueryExpression(), world.defaultLogger());
      objectStore = world.actorFor(ObjectStore.class, JDBCObjectStoreActor.class, delegate, dispatcher);
      objectStore.registerMapper(textEntryPersistentObjectMapper());
      objectStore.registerMapper(dispatchableMapping());
    }

    return objectStore;
  }

  public PersistentObjectMapper textEntryPersistentObjectMapper() {
    final PersistentObjectMapper persistentObjectMapper =
            PersistentObjectMapper.with(
                    Entry.class,
                    JdbiPersistMapper.with(
                            "INSERT INTO ENTRY_JOURNAL(E_TYPE, E_TYPE_VERSION, E_DATA, E_METADATA_VALUE, E_METADATA_OP) "
                             + "VALUES (:entry.type, :entry.typeVersion, :entry.entryData, :entry.metadata.value, :entry.metadata.operation)",
                            "(unused)",
                            (update,object) -> update.bindMethods(object)),
                    entryMapper());

    return persistentObjectMapper;
  }


  private JdbiOnHSQLDB(final Configuration configuration) {
    this.configuration = configuration;
    this.handle = Jdbi.open(configuration.connection);
  }

  @SuppressWarnings("rawtypes")
  public static class EntryMapper implements RowMapper<Entry> {
    @Override
    public Entry map(final ResultSet rs, final StatementContext ctx) throws SQLException {
      final TextEntry entry =
              new TextEntry(
                      Long.toString(rs.getLong("E_ID")),
                      Entry.typed(rs.getString("E_TYPE")),
                      rs.getInt("E_TYPE_VERSION"),
                      rs.getString("E_DATA"),
                      Metadata.with(rs.getString("E_METADATA_VALUE"), rs.getString("E_METADATA_OP")));

      return new PersistentEntry(entry);
    }
  }

  public void createDispatchableTable() {
    handle.execute("CREATE TABLE TBL_VLINGO_SYMBIO_DISPATCHABLES (\n" +
            "   D_ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY," +
            "   D_CREATED_AT TIMESTAMP NOT NULL," +
            "   D_ORIGINATOR_ID VARCHAR(32) NOT NULL," +
            "   D_DISPATCH_ID VARCHAR(128) NULL,\n" +
            "   D_STATE_ID VARCHAR(128) NULL, \n" +
            "   D_STATE_TYPE VARCHAR(256) NULL,\n" +
            "   D_STATE_TYPE_VERSION INT NULL,\n" +
            "   D_STATE_DATA CLOB NULL,\n" +
            "   D_STATE_DATA_VERSION INT NULL,\n" +
            "   D_STATE_METADATA CLOB NULL,\n" +
            "   D_ENTRIES CLOB NULL \n" +
            ");" );
    handle.execute("CREATE INDEX IDX_DISPATCHABLES_DISPATCH_ID \n" +
            "ON TBL_VLINGO_SYMBIO_DISPATCHABLES (D_DISPATCH_ID);");

    handle.execute("CREATE INDEX IDX_DISPATCHABLES_ORIGINATOR_ID \n" +
            "ON TBL_VLINGO_SYMBIO_DISPATCHABLES (D_ORIGINATOR_ID);");
  }

  private PersistentObjectMapper dispatchableMapping() {
    return PersistentObjectMapper.with(
            Dispatchable.class,
            JdbiPersistMapper.with(
                    "INSERT INTO TBL_VLINGO_SYMBIO_DISPATCHABLES(D_CREATED_AT, D_ORIGINATOR_ID, D_DISPATCH_ID, D_STATE_ID, D_STATE_TYPE, D_STATE_TYPE_VERSION,"
                            + " D_STATE_DATA, D_STATE_DATA_VERSION, D_STATE_METADATA, D_ENTRIES) "
                            + " VALUES ( :createdOn, :originatorId, :id, :state.id, :state.type, "
                            + " :state.typeVersion, :state.data, :state.dataVersion, :state.metadata, :entries)",
                    "DELETE FROM TBL_VLINGO_SYMBIO_DISPATCHABLES WHERE D_DISPATCH_ID = :id",
                    SqlStatement::bindMethods),
            new DispatchablesMapper());
  }

  private QueryExpression unconfirmedDispatchablesQueryExpression(){
    return new QueryExpression(
            Dispatchable.class,
            "SELECT * FROM TBL_VLINGO_SYMBIO_DISPATCHABLES WHERE D_ORIGINATOR_ID = '"+ this.configuration.originatorId +"' ORDER BY D_CREATED_AT ASC"
    );
  }

  @SuppressWarnings("rawtypes")
  private static class DispatchablesMapper implements RowMapper<Dispatchable<Entry<?>, State<?>>> {
    @Override
    public Dispatchable<Entry<?>, State<?>> map(final ResultSet rs, final StatementContext ctx) throws SQLException {
      final String dispatchId = rs.getString("D_DISPATCH_ID");
      final LocalDateTime createdAt = rs.getTimestamp("D_CREATED_AT").toLocalDateTime();
      final Class<?> stateType;
      try {
        stateType = Class.forName(rs.getString("D_STATE_TYPE"));
      } catch (final ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
      final int stateTypeVersion = rs.getInt("D_STATE_TYPE_VERSION");
      final int stateDataVersion = rs.getInt("D_STATE_DATA_VERSION");
      final String stateId = rs.getString("D_STATE_ID");
      final String stateData = rs.getString("D_STATE_DATA");
      final String metadataValue = rs.getString("D_STATE_METADATA");
      final Metadata metadata = JsonSerialization.deserialized(metadataValue, Metadata.class);

      final String entriesJson = rs.getString("D_ENTRIES");
      final List<Entry<?>> entries;
      if (entriesJson !=null && !entriesJson.isEmpty()){
        entries = JsonSerialization.deserializedList(entriesJson, new TypeToken<List<TextEntry>>(){}.getType());
      } else {
        entries = Collections.emptyList();
      }
      
      final State.TextState state = new State.TextState(stateId, stateType, stateTypeVersion, stateData, stateDataVersion, metadata);
      return new Dispatchable<>(dispatchId, createdAt, state, entries);
    }
  }
}
