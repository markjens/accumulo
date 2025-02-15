/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.test.replication;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.clientImpl.ReplicationOperationsImpl;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.ReplicationSection;
import org.apache.accumulo.core.protobuf.ProtobufUtil;
import org.apache.accumulo.core.replication.ReplicationSchema.StatusSection;
import org.apache.accumulo.core.replication.ReplicationTable;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.securityImpl.thrift.TCredentials;
import org.apache.accumulo.core.tabletserver.log.LogEntry;
import org.apache.accumulo.core.trace.thrift.TInfo;
import org.apache.accumulo.manager.Manager;
import org.apache.accumulo.manager.ManagerClientServiceHandler;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.replication.proto.Replication.Status;
import org.apache.accumulo.test.functional.ConfigurableMacBase;
import org.apache.hadoop.io.Text;
import org.apache.thrift.TException;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Ignore("Replication ITs are not stable and not currently maintained")
@Deprecated
public class ReplicationOperationsImplIT extends ConfigurableMacBase {
  private static final Logger log = LoggerFactory.getLogger(ReplicationOperationsImplIT.class);

  private AccumuloClient client;
  private ServerContext context;

  @Before
  public void configureInstance() throws Exception {
    client = Accumulo.newClient().from(getClientProperties()).build();
    context = getServerContext();
    ReplicationTable.setOnline(client);
    client.securityOperations().grantTablePermission(client.whoami(), MetadataTable.NAME,
        TablePermission.WRITE);
    client.securityOperations().grantTablePermission(client.whoami(), ReplicationTable.NAME,
        TablePermission.READ);
    client.securityOperations().grantTablePermission(client.whoami(), ReplicationTable.NAME,
        TablePermission.WRITE);
  }

  /**
   * Spoof out the Manager so we can call the implementation without starting a full instance.
   */
  private ReplicationOperationsImpl getReplicationOperations() {
    Manager manager = EasyMock.createMock(Manager.class);
    EasyMock.expect(manager.getContext()).andReturn(context).anyTimes();
    EasyMock.replay(manager);

    final ManagerClientServiceHandler mcsh = new ManagerClientServiceHandler(manager) {
      @Override
      protected TableId getTableId(ClientContext context, String tableName) {
        try {
          return TableId.of(client.tableOperations().tableIdMap().get(tableName));
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };

    ClientContext context = (ClientContext) client;
    return new ReplicationOperationsImpl(context) {
      @Override
      protected boolean getManagerDrain(final TInfo tinfo, final TCredentials rpcCreds,
          final String tableName, final Set<String> wals) {
        try {
          return mcsh.drainReplicationTable(tinfo, rpcCreds, tableName, wals);
        } catch (TException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  @Test
  public void waitsUntilEntriesAreReplicated() throws Exception {
    client.tableOperations().create("foo");
    TableId tableId = TableId.of(client.tableOperations().tableIdMap().get("foo"));

    String file1 = "/accumulo/wals/tserver+port/" + UUID.randomUUID(),
        file2 = "/accumulo/wals/tserver+port/" + UUID.randomUUID();
    Status stat = Status.newBuilder().setBegin(0).setEnd(10000).setInfiniteEnd(false)
        .setClosed(false).build();

    BatchWriter bw = ReplicationTable.getBatchWriter(client);

    Mutation m = new Mutation(file1);
    StatusSection.add(m, tableId, ProtobufUtil.toValue(stat));
    bw.addMutation(m);

    m = new Mutation(file2);
    StatusSection.add(m, tableId, ProtobufUtil.toValue(stat));
    bw.addMutation(m);

    bw.close();

    bw = client.createBatchWriter(MetadataTable.NAME);
    m = new Mutation(ReplicationSection.getRowPrefix() + file1);
    m.put(ReplicationSection.COLF, new Text(tableId.canonical()), ProtobufUtil.toValue(stat));

    bw.addMutation(m);

    m = new Mutation(ReplicationSection.getRowPrefix() + file2);
    m.put(ReplicationSection.COLF, new Text(tableId.canonical()), ProtobufUtil.toValue(stat));

    bw.close();

    final AtomicBoolean done = new AtomicBoolean(false);
    final AtomicBoolean exception = new AtomicBoolean(false);
    final ReplicationOperationsImpl roi = getReplicationOperations();
    Thread t = new Thread(() -> {
      try {
        roi.drain("foo");
      } catch (Exception e) {
        log.error("Got error", e);
        exception.set(true);
      }
      done.set(true);
    });

    t.start();

    // With the records, we shouldn't be drained
    assertFalse(done.get());

    bw = client.createBatchWriter(MetadataTable.NAME);
    m = new Mutation(ReplicationSection.getRowPrefix() + file1);
    m.putDelete(ReplicationSection.COLF, new Text(tableId.canonical()));
    bw.addMutation(m);
    bw.flush();

    assertFalse(done.get());

    m = new Mutation(ReplicationSection.getRowPrefix() + file2);
    m.putDelete(ReplicationSection.COLF, new Text(tableId.canonical()));
    bw.addMutation(m);
    bw.flush();
    bw.close();

    // Removing metadata entries doesn't change anything
    assertFalse(done.get());

    // Remove the replication entries too
    bw = ReplicationTable.getBatchWriter(client);
    m = new Mutation(file1);
    m.putDelete(StatusSection.NAME, new Text(tableId.canonical()));
    bw.addMutation(m);
    bw.flush();

    assertFalse(done.get());

    m = new Mutation(file2);
    m.putDelete(StatusSection.NAME, new Text(tableId.canonical()));
    bw.addMutation(m);
    bw.flush();

    try {
      t.join(5000);
    } catch (InterruptedException e) {
      fail("ReplicationOperations.drain did not complete");
    }

    // After both metadata and replication
    assertTrue("Drain never finished", done.get());
    assertFalse("Saw unexpectetd exception", exception.get());
  }

  @Test
  public void unrelatedReplicationRecordsDontBlockDrain() throws Exception {
    client.tableOperations().create("foo");
    client.tableOperations().create("bar");

    TableId tableId1 = TableId.of(client.tableOperations().tableIdMap().get("foo"));
    TableId tableId2 = TableId.of(client.tableOperations().tableIdMap().get("bar"));

    String file1 = "/accumulo/wals/tserver+port/" + UUID.randomUUID(),
        file2 = "/accumulo/wals/tserver+port/" + UUID.randomUUID();
    Status stat = Status.newBuilder().setBegin(0).setEnd(10000).setInfiniteEnd(false)
        .setClosed(false).build();

    BatchWriter bw = ReplicationTable.getBatchWriter(client);

    Mutation m = new Mutation(file1);
    StatusSection.add(m, tableId1, ProtobufUtil.toValue(stat));
    bw.addMutation(m);

    m = new Mutation(file2);
    StatusSection.add(m, tableId2, ProtobufUtil.toValue(stat));
    bw.addMutation(m);

    bw.close();

    bw = client.createBatchWriter(MetadataTable.NAME);
    m = new Mutation(ReplicationSection.getRowPrefix() + file1);
    m.put(ReplicationSection.COLF, new Text(tableId1.canonical()), ProtobufUtil.toValue(stat));

    bw.addMutation(m);

    m = new Mutation(ReplicationSection.getRowPrefix() + file2);
    m.put(ReplicationSection.COLF, new Text(tableId2.canonical()), ProtobufUtil.toValue(stat));

    bw.close();

    final AtomicBoolean done = new AtomicBoolean(false);
    final AtomicBoolean exception = new AtomicBoolean(false);

    final ReplicationOperationsImpl roi = getReplicationOperations();

    Thread t = new Thread(() -> {
      try {
        roi.drain("foo");
      } catch (Exception e) {
        log.error("Got error", e);
        exception.set(true);
      }
      done.set(true);
    });

    t.start();

    // With the records, we shouldn't be drained
    assertFalse(done.get());

    bw = client.createBatchWriter(MetadataTable.NAME);
    m = new Mutation(ReplicationSection.getRowPrefix() + file1);
    m.putDelete(ReplicationSection.COLF, new Text(tableId1.canonical()));
    bw.addMutation(m);
    bw.flush();

    // Removing metadata entries doesn't change anything
    assertFalse(done.get());

    // Remove the replication entries too
    bw = ReplicationTable.getBatchWriter(client);
    m = new Mutation(file1);
    m.putDelete(StatusSection.NAME, new Text(tableId1.canonical()));
    bw.addMutation(m);
    bw.flush();
    bw.close();

    try {
      t.join(5000);
    } catch (InterruptedException e) {
      fail("ReplicationOperations.drain did not complete");
    }

    // After both metadata and replication
    assertTrue("Drain never completed", done.get());
    assertFalse("Saw unexpected exception", exception.get());
  }

  @Test
  public void inprogressReplicationRecordsBlockExecution() throws Exception {
    client.tableOperations().create("foo");

    TableId tableId1 = TableId.of(client.tableOperations().tableIdMap().get("foo"));

    String file1 = "/accumulo/wals/tserver+port/" + UUID.randomUUID();
    Status stat = Status.newBuilder().setBegin(0).setEnd(10000).setInfiniteEnd(false)
        .setClosed(false).build();

    BatchWriter bw = ReplicationTable.getBatchWriter(client);

    Mutation m = new Mutation(file1);
    StatusSection.add(m, tableId1, ProtobufUtil.toValue(stat));
    bw.addMutation(m);
    bw.close();

    LogEntry logEntry =
        new LogEntry(new KeyExtent(tableId1, null, null), System.currentTimeMillis(), file1);

    bw = client.createBatchWriter(MetadataTable.NAME);
    m = new Mutation(ReplicationSection.getRowPrefix() + file1);
    m.put(ReplicationSection.COLF, new Text(tableId1.canonical()), ProtobufUtil.toValue(stat));
    bw.addMutation(m);

    m = new Mutation(logEntry.getRow());
    m.put(logEntry.getColumnFamily(), logEntry.getColumnQualifier(), logEntry.getValue());
    bw.addMutation(m);

    bw.close();

    final AtomicBoolean done = new AtomicBoolean(false);
    final AtomicBoolean exception = new AtomicBoolean(false);
    final ReplicationOperationsImpl roi = getReplicationOperations();
    Thread t = new Thread(() -> {
      try {
        roi.drain("foo");
      } catch (Exception e) {
        log.error("Got error", e);
        exception.set(true);
      }
      done.set(true);
    });

    t.start();

    // With the records, we shouldn't be drained
    assertFalse(done.get());

    Status newStatus = Status.newBuilder().setBegin(1000).setEnd(2000).setInfiniteEnd(false)
        .setClosed(true).build();
    bw = client.createBatchWriter(MetadataTable.NAME);
    m = new Mutation(ReplicationSection.getRowPrefix() + file1);
    m.put(ReplicationSection.COLF, new Text(tableId1.canonical()), ProtobufUtil.toValue(newStatus));
    bw.addMutation(m);
    bw.flush();

    // Removing metadata entries doesn't change anything
    assertFalse(done.get());

    // Remove the replication entries too
    bw = ReplicationTable.getBatchWriter(client);
    m = new Mutation(file1);
    m.put(StatusSection.NAME, new Text(tableId1.canonical()), ProtobufUtil.toValue(newStatus));
    bw.addMutation(m);
    bw.flush();

    try {
      t.join(5000);
    } catch (InterruptedException e) {
      fail("ReplicationOperations.drain did not complete");
    }

    // New records, but not fully replicated ones don't cause it to complete
    assertFalse("Drain somehow finished", done.get());
    assertFalse("Saw unexpected exception", exception.get());
  }

  @Test
  public void laterCreatedLogsDontBlockExecution() throws Exception {
    client.tableOperations().create("foo");

    TableId tableId1 = TableId.of(client.tableOperations().tableIdMap().get("foo"));

    String file1 = "/accumulo/wals/tserver+port/" + UUID.randomUUID();
    Status stat = Status.newBuilder().setBegin(0).setEnd(10000).setInfiniteEnd(false)
        .setClosed(false).build();

    BatchWriter bw = ReplicationTable.getBatchWriter(client);
    Mutation m = new Mutation(file1);
    StatusSection.add(m, tableId1, ProtobufUtil.toValue(stat));
    bw.addMutation(m);
    bw.close();

    bw = client.createBatchWriter(MetadataTable.NAME);
    m = new Mutation(ReplicationSection.getRowPrefix() + file1);
    m.put(ReplicationSection.COLF, new Text(tableId1.canonical()), ProtobufUtil.toValue(stat));
    bw.addMutation(m);

    bw.close();

    log.info("Reading metadata first time");
    try (var scanner = client.createScanner(MetadataTable.NAME, Authorizations.EMPTY)) {
      for (Entry<Key,Value> e : scanner) {
        log.info("{}", e.getKey());
      }
    }

    final AtomicBoolean done = new AtomicBoolean(false);
    final AtomicBoolean exception = new AtomicBoolean(false);
    final ReplicationOperationsImpl roi = getReplicationOperations();
    Thread t = new Thread(() -> {
      try {
        roi.drain("foo");
      } catch (Exception e) {
        log.error("Got error", e);
        exception.set(true);
      }
      done.set(true);
    });

    t.start();

    // We need to wait long enough for the table to read once
    Thread.sleep(2000);

    // Write another file, but also delete the old files
    bw = client.createBatchWriter(MetadataTable.NAME);
    m = new Mutation(
        ReplicationSection.getRowPrefix() + "/accumulo/wals/tserver+port/" + UUID.randomUUID());
    m.put(ReplicationSection.COLF, new Text(tableId1.canonical()), ProtobufUtil.toValue(stat));
    bw.addMutation(m);
    m = new Mutation(ReplicationSection.getRowPrefix() + file1);
    m.putDelete(ReplicationSection.COLF, new Text(tableId1.canonical()));
    bw.addMutation(m);
    bw.close();

    log.info("Reading metadata second time");
    try (var scanner = client.createScanner(MetadataTable.NAME, Authorizations.EMPTY)) {
      for (Entry<Key,Value> e : scanner) {
        log.info("{}", e.getKey());
      }
    }

    bw = ReplicationTable.getBatchWriter(client);
    m = new Mutation(file1);
    m.putDelete(StatusSection.NAME, new Text(tableId1.canonical()));
    bw.addMutation(m);
    bw.close();

    try {
      t.join(5000);
    } catch (InterruptedException e) {
      fail("ReplicationOperations.drain did not complete");
    }

    // We should pass immediately because we aren't waiting on both files to be deleted (just the
    // one that we did)
    assertTrue("Drain didn't finish", done.get());
  }
}
