/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flume.channel.file;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.flume.ChannelException;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurables;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.sink.LoggerSink;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import java.util.HashSet;
import java.util.Iterator;

import static org.apache.flume.channel.file.TestUtils.*;

public class TestFileChannel {

  private static final Logger LOG = LoggerFactory
          .getLogger(TestFileChannel.class);
  private FileChannel channel;
  private File baseDir;
  private File checkpointDir;
  private File[] dataDirs;
  private String dataDir;

  @Before
  public void setup() {
    baseDir = Files.createTempDir();
    checkpointDir = new File(baseDir, "chkpt");
    Assert.assertTrue(checkpointDir.mkdirs() || checkpointDir.isDirectory());
    dataDirs = new File[3];
    dataDir = "";
    for (int i = 0; i < dataDirs.length; i++) {
      dataDirs[i] = new File(baseDir, "data" + (i+1));
      Assert.assertTrue(dataDirs[i].mkdirs() || dataDirs[i].isDirectory());
      dataDir += dataDirs[i].getAbsolutePath() + ",";
    }
    dataDir = dataDir.substring(0, dataDir.length() - 1);
    channel = createFileChannel();
  }
  @After
  public void teardown() {
    if(channel != null && channel.isOpen()) {
      channel.stop();
    }
    FileUtils.deleteQuietly(baseDir);
  }
  private Context createContext() {
    return createContext(new HashMap<String, String>());
  }
  private Context createContext(Map<String, String> overrides) {
    Context context = new Context();
    context.put(FileChannelConfiguration.CHECKPOINT_DIR,
            checkpointDir.getAbsolutePath());
    context.put(FileChannelConfiguration.DATA_DIRS, dataDir);
    context.put(FileChannelConfiguration.CAPACITY, String.valueOf(10000));
    // Set checkpoint for 5 seconds otherwise test will run out of memory
    context.put(FileChannelConfiguration.CHECKPOINT_INTERVAL, "5000");
    context.putAll(overrides);
    return context;
  }
  private FileChannel createFileChannel() {
    return createFileChannel(new HashMap<String, String>());
  }
  private FileChannel createFileChannel(Map<String, String> overrides) {
    FileChannel channel = new FileChannel();
    channel.setName("FileChannel-" + UUID.randomUUID());
    Context context = createContext(overrides);
    Configurables.configure(channel, context);
    return channel;
  }
  @Test
  public void testFailAfterTakeBeforeCommit() throws Throwable {
    final FileChannel channel = createFileChannel();
    channel.start();
    final Set<String> eventSet =
            putEvents(channel, "testTakeFailBeforeCommit", 5, 5);
    Transaction tx = channel.getTransaction();
    takeWithoutCommit(channel, tx, 2);
    //Simulate multiple sources, so separate thread - txns are thread local,
    //so a new txn wont be created here unless it is in a different thread.
    Executors.newSingleThreadExecutor().submit(new Runnable() {
      @Override
      public void run() {
        Transaction tx = channel.getTransaction();
        takeWithoutCommit(channel, tx, 3);
      }
    }).get();
    forceCheckpoint(channel);
    channel.stop();
    //Simulate a sink, so separate thread.
    try {
      Executors.newSingleThreadExecutor().submit(new Runnable() {
        @Override
        public void run() {
          FileChannel channel = createFileChannel();
          channel.start();
          Set<String> output = null;
          try {
            output = takeEvents(channel, 5);
          } catch (Exception e) {
            Throwables.propagate(e);
          }
          compareInputAndOut(eventSet, output);
          channel.stop();
        }
      }).get();
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }

  @Test
  public void testFailAfterPutCheckpointCommit() throws Throwable {
    final Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.CHECKPOINT_INTERVAL, "10000");
    final FileChannel channel = createFileChannel(overrides);
    channel.start();
    Transaction tx = channel.getTransaction();
    final Set<String> input = putWithoutCommit(channel, tx, "failAfterPut", 3);
    //Simulate multiple sources, so separate thread - txns are thread local,
    //so a new txn wont be created here unless it is in a different thread.
    final CountDownLatch latch = new CountDownLatch(1);
    Executors.newSingleThreadExecutor().submit(
            new Runnable() {
              @Override
              public void run() {
                Transaction tx = channel.getTransaction();
                input.addAll(putWithoutCommit(channel, tx, "failAfterPut", 3));
                try {
                  latch.await();
                  tx.commit();
                } catch (InterruptedException e) {
                  tx.rollback();
                  Throwables.propagate(e);
                } finally {
                  tx.close();
                }
              }
            });
    forceCheckpoint(channel);
    tx.commit();
    tx.close();
    latch.countDown();
    Thread.sleep(2000);
    channel.stop();

    final Set<String> out = Sets.newHashSet();
    //Simulate a sink, so separate thread.
    try {
      Executors.newSingleThreadExecutor().submit(new Runnable() {
        @Override
        public void run() {
          try {
            FileChannel channel = createFileChannel();
            channel.start();
            out.addAll(takeEvents(channel, 6));
            channel.stop();
          } catch (Exception ex) {
            Throwables.propagate(ex);
          }
        }
      }).get();
    } catch (ExecutionException e) {
      throw e.getCause();
    }

  }

  @Test
  public void testRestartLogReplayV1() throws Exception {
    doTestRestart(true, false, false);
  }
  @Test
  public void testRestartLogReplayV2() throws Exception {
    doTestRestart(false, false, false);
  }

  @Test
  public void testFastReplayV1() throws Exception {
    doTestRestart(true, true, true);
  }

  @Test
  public void testFastReplayV2() throws Exception {
    doTestRestart(false, true, true);
  }
  public void doTestRestart(boolean useLogReplayV1,
          boolean forceCheckpoint, boolean deleteCheckpoint) throws Exception {
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.USE_LOG_REPLAY_V1,
            String.valueOf(useLogReplayV1));
    overrides.put(
            FileChannelConfiguration.USE_FAST_REPLAY,
            String.valueOf(deleteCheckpoint));
    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> in = Sets.newHashSet();
    try {
      while(true) {
        in.addAll(putEvents(channel, "restart", 1, 1));
      }
    } catch (ChannelException e) {
      Assert.assertEquals("Cannot acquire capacity. [channel="
          +channel.getName()+"]", e.getMessage());
    }
    if (forceCheckpoint) {
      forceCheckpoint(channel);
    }
    channel.stop();
    if(deleteCheckpoint) {
      File checkpoint = new File(checkpointDir, "checkpoint");
      checkpoint.delete();
    }
    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> out = takeEvents(channel, 1, Integer.MAX_VALUE);
    compareInputAndOut(in, out);
  }
  @Test
  public void testReconfigure() throws Exception {
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> in = Sets.newHashSet();
    try {
      while(true) {
        in.addAll(putEvents(channel, "reconfig", 1, 1));
      }
    } catch (ChannelException e) {
      Assert.assertEquals("Cannot acquire capacity. [channel="
          +channel.getName()+"]", e.getMessage());
    }
    Configurables.configure(channel, createContext());
    Set<String> out = takeEvents(channel, 1, Integer.MAX_VALUE);
    compareInputAndOut(in, out);
  }
  @Test
  public void testPut() throws Exception {
    channel.start();
    Assert.assertTrue(channel.isOpen());
    // should find no items
    int found = takeEvents(channel, 1, 5).size();
    Assert.assertEquals(0, found);
    Set<String> expected = Sets.newHashSet();
    expected.addAll(putEvents(channel, "unbatched", 1, 5));
    expected.addAll(putEvents(channel, "batched", 5, 5));
    Set<String> actual = takeEvents(channel, 1);
    compareInputAndOut(expected, actual);
  }
  @Test
  public void testRollbackAfterNoPutTake() throws Exception {
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Transaction transaction;
    transaction = channel.getTransaction();
    transaction.begin();
    transaction.rollback();
    transaction.close();

    // ensure we can reopen log with no error
    channel.stop();
    channel = createFileChannel();
    channel.start();
    Assert.assertTrue(channel.isOpen());
    transaction = channel.getTransaction();
    transaction.begin();
    Assert.assertNull(channel.take());
    transaction.commit();
    transaction.close();
  }
  @Test
  public void testCommitAfterNoPutTake() throws Exception {
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Transaction transaction;
    transaction = channel.getTransaction();
    transaction.begin();
    transaction.commit();
    transaction.close();

    // ensure we can reopen log with no error
    channel.stop();
    channel = createFileChannel();
    channel.start();
    Assert.assertTrue(channel.isOpen());
    transaction = channel.getTransaction();
    transaction.begin();
    Assert.assertNull(channel.take());
    transaction.commit();
    transaction.close();
  }
  @Test
  public void testCapacity() throws Exception {
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.CAPACITY, String.valueOf(5));
    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    try {
      putEvents(channel, "fillup", 1, Integer.MAX_VALUE);
      Assert.fail();
    } catch (ChannelException e) {
      Assert.assertEquals("Cannot acquire capacity. [channel="+channel.getName()+"]",
              e.getMessage());
    }
    // take an event, roll it back, and
    // then make sure a put fails
    Transaction transaction;
    transaction = channel.getTransaction();
    transaction.begin();
    Event event = channel.take();
    Assert.assertNotNull(event);
    transaction.rollback();
    transaction.close();
    // ensure the take the didn't change the state of the capacity
    try {
      putEvents(channel, "capacity", 1, 1);
      Assert.fail();
    } catch (ChannelException e) {
      Assert.assertEquals("Cannot acquire capacity. [channel="+channel.getName()+"]",
              e.getMessage());
    }
    // ensure we the events back
    Assert.assertEquals(5, takeEvents(channel, 1, 5).size());
  }
  /**
   * This test is here to make sure we can replay a full queue
   * when we have a PUT with a lower txid than the take which
   * made that PUT possible. Here we fill up the queue so
   * puts will block. Start the put (which assigns a txid)
   * and while it's blocking initiate a take. That will
   * allow the PUT to take place but at a lower txid
   * than the take and additionally with pre-FLUME-1432 with
   * the same timestamp. After FLUME-1432 the PUT will have a
   * lower txid but a higher write order id and we can see
   * which event occurred first.
   */
  @Test
  public void testRaceFoundInFLUME1432() throws Exception {
    // the idea here is we will fill up the channel
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.KEEP_ALIVE, String.valueOf(10L));
    overrides.put(FileChannelConfiguration.CAPACITY, String.valueOf(10));
    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    try {
      putEvents(channel, "fillup", 1, Integer.MAX_VALUE);
      Assert.fail();
    } catch (ChannelException e) {
      Assert.assertEquals("Cannot acquire capacity. [channel="+channel.getName()+"]",
              e.getMessage());
    }
    // then do a put which will block but it will be assigned a tx id
    Future<String> put = Executors.newSingleThreadExecutor()
            .submit(new Callable<String>() {
      @Override
      public String call() throws Exception {
        Set<String> result = putEvents(channel, "blocked-put", 1, 1);
        Assert.assertTrue(result.toString(), result.size() == 1);
        Iterator<String> iter = result.iterator();
        return iter.next();
      }
    });
    Thread.sleep(1000L); // ensure the put has started and is blocked
    // after which we do a take, will have a tx id after the put
    Set<String> result = takeEvents(channel, 1, 1);
    Assert.assertTrue(result.toString(), result.size() == 1);
    String putmsg = put.get();
    Assert.assertNotNull(putmsg);
    String takemsg = result.iterator().next();
    Assert.assertNotNull(takemsg);
    LOG.info("Got: put " + putmsg + ", take " + takemsg);
    channel.stop();
    channel = createFileChannel(overrides);
    // now when we replay, the transaction the put will be ordered
    // before the take when we used the txid as an order of operations
    channel.start();
    Assert.assertTrue(channel.isOpen());
  }
  @Test
  public void testRollbackSimulatedCrash() throws Exception {
    channel.start();
    Assert.assertTrue(channel.isOpen());
    int numEvents = 50;
    Set<String> in = putEvents(channel, "rollback", 1, numEvents);

    Transaction transaction;
    // put an item we will rollback
    transaction = channel.getTransaction();
    transaction.begin();
    channel.put(EventBuilder.withBody("rolled back".getBytes(Charsets.UTF_8)));
    transaction.rollback();
    transaction.close();

    // simulate crash
    channel.stop();
    channel = createFileChannel();
    channel.start();
    Assert.assertTrue(channel.isOpen());

    // we should not get the rolled back item
    Set<String> out = takeEvents(channel, 1, numEvents);
    compareInputAndOut(in, out);
  }
  @Test
  public void testRollbackSimulatedCrashWithSink() throws Exception {
    channel.start();
    Assert.assertTrue(channel.isOpen());
    int numEvents = 100;

    LoggerSink sink = new LoggerSink();
    sink.setChannel(channel);
    // sink will leave one item
    CountingSinkRunner runner = new CountingSinkRunner(sink, numEvents - 1);
    runner.start();
    putEvents(channel, "rollback", 10, numEvents);

    Transaction transaction;
    // put an item we will rollback
    transaction = channel.getTransaction();
    transaction.begin();
    byte[] bytes = "rolled back".getBytes(Charsets.UTF_8);
    channel.put(EventBuilder.withBody(bytes));
    transaction.rollback();
    transaction.close();

    while(runner.isAlive()) {
      Thread.sleep(10L);
    }
    Assert.assertEquals(numEvents - 1, runner.getCount());
    for(Exception ex : runner.getErrors()) {
      LOG.warn("Sink had error", ex);
    }
    Assert.assertEquals(Collections.EMPTY_LIST, runner.getErrors());

    // simulate crash
    channel.stop();
    channel = createFileChannel();
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> out = takeEvents(channel, 1, 1);
    Assert.assertEquals(1, out.size());
    String s = out.iterator().next();
    Assert.assertTrue(s, s.startsWith("rollback-90-9"));
  }
  @Test
  public void testThreaded() throws IOException, InterruptedException {
    channel.start();
    Assert.assertTrue(channel.isOpen());
    int numThreads = 10;
    final CountDownLatch producerStopLatch = new CountDownLatch(numThreads);
    final CountDownLatch consumerStopLatch = new CountDownLatch(numThreads);
    final List<Exception> errors = Collections
            .synchronizedList(new ArrayList<Exception>());
    final Set<String> expected = Collections.synchronizedSet(
            new HashSet<String>());
    final Set<String> actual = Collections.synchronizedSet(
            new HashSet<String>());
    for (int i = 0; i < numThreads; i++) {
      final int id = i;
      Thread t = new Thread() {
        @Override
        public void run() {
          try {
            if (id % 2 == 0) {
              expected.addAll(putEvents(channel, Integer.toString(id), 1, 5));
            } else {
              expected.addAll(putEvents(channel, Integer.toString(id), 5, 5));
            }
            LOG.info("Completed some puts " + expected.size());
          } catch (Exception e) {
            LOG.error("Error doing puts", e);
            errors.add(e);
          } finally {
            producerStopLatch.countDown();
          }
        }
      };
      t.setDaemon(true);
      t.start();
    }
    for (int i = 0; i < numThreads; i++) {
      final int id = i;
      Thread t = new Thread() {
        @Override
        public void run() {
          try {
            while(!producerStopLatch.await(1, TimeUnit.SECONDS) ||
                expected.size() > actual.size()) {
              if (id % 2 == 0) {
                actual.addAll(takeEvents(channel, 1, Integer.MAX_VALUE));
              } else {
                actual.addAll(takeEvents(channel, 5, Integer.MAX_VALUE));
              }
            }
            if(actual.isEmpty()) {
              LOG.error("Found nothing!");
            } else {
              LOG.info("Completed some takes " + actual.size());
            }
          } catch (Exception e) {
            LOG.error("Error doing takes", e);
            errors.add(e);
          } finally {
            consumerStopLatch.countDown();
          }
        }
      };
      t.setDaemon(true);
      t.start();
    }
    Assert.assertTrue("Timed out waiting for producers",
            producerStopLatch.await(30, TimeUnit.SECONDS));
    Assert.assertTrue("Timed out waiting for consumer",
            consumerStopLatch.await(30, TimeUnit.SECONDS));
    Assert.assertEquals(Collections.EMPTY_LIST, errors);
    compareInputAndOut(expected, actual);
  }
  @Test
  public void testLocking() throws IOException {
    channel.start();
    Assert.assertTrue(channel.isOpen());
    FileChannel fileChannel = createFileChannel();
    fileChannel.start();
    Assert.assertTrue(!fileChannel.isOpen());
  }
  /**
   * This is regression test with files generated by a file channel
   * with the FLUME-1432 patch.
   */
  @Test
  public void testFileFormatV2postFLUME1432()
          throws Exception {
    copyDecompressed("fileformat-v2-checkpoint.gz",
            new File(checkpointDir, "checkpoint"));
    for (int i = 0; i < dataDirs.length; i++) {
      int fileIndex = i + 1;
      copyDecompressed("fileformat-v2-log-"+fileIndex+".gz",
              new File(dataDirs[i], "log-" + fileIndex));
    }
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.CAPACITY, String.valueOf(10));
    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> events = takeEvents(channel, 1);
    Set<String> expected = new HashSet<String>();
    expected.addAll(Arrays.asList(
            (new String[]{
              "2684", "2685", "2686", "2687", "2688", "2689", "2690", "2691"
            })));
    compareInputAndOut(expected, events);

  }
  /**
   * This is a regression test with files generated by a file channel
   * without the FLUME-1432 patch.
   */
  @Test
  public void testFileFormatV2PreFLUME1432LogReplayV1()
          throws Exception {
    doTestFileFormatV2PreFLUME1432(true);
  }
  @Test
  public void testFileFormatV2PreFLUME1432LogReplayV2()
          throws Exception {
    doTestFileFormatV2PreFLUME1432(false);
  }
  public void doTestFileFormatV2PreFLUME1432(boolean useLogReplayV1)
          throws Exception {
    copyDecompressed("fileformat-v2-pre-FLUME-1432-checkpoint.gz",
            new File(checkpointDir, "checkpoint"));
    for (int i = 0; i < dataDirs.length; i++) {
      int fileIndex = i + 1;
      copyDecompressed("fileformat-v2-pre-FLUME-1432-log-" + fileIndex + ".gz",
              new File(dataDirs[i], "log-" + fileIndex));
    }
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.CAPACITY, String.valueOf(10000));
    overrides.put(FileChannelConfiguration.USE_LOG_REPLAY_V1,
            String.valueOf(useLogReplayV1));
    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> events = takeEvents(channel, 1);
    Assert.assertEquals(50, events.size());
  }

  /**
   * Test contributed by Brock Noland during code review.
   * @throws Exception
   */
  @Test
  public void testTakeTransactionCrossingCheckpoint() throws Exception {
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.CHECKPOINT_INTERVAL, "10000");
    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> in = Sets.newHashSet();
    try {
      while (true) {
        in.addAll(putEvents(channel, "restart", 1, 1));
      }
    } catch (ChannelException e) {
      Assert.assertEquals("Cannot acquire capacity. [channel="
              + channel.getName() + "]", e.getMessage());
    }
    Set<String> out = Sets.newHashSet();
    // now take one item off the channel
    Transaction tx = channel.getTransaction();
    out.addAll(takeWithoutCommit(channel, tx, 1));
    // sleep so a checkpoint occurs. take is before
    // and commit is after the checkpoint
    forceCheckpoint(channel);
    tx.commit();
    tx.close();
    channel.stop();
    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    // we should not geet the item we took of the queue above
    Set<String> out2 = takeEvents(channel, 1, Integer.MAX_VALUE);
    channel.stop();
    in.removeAll(out);
    compareInputAndOut(in, out2);
  }

  @Test
  public void testPutForceCheckpointCommitReplay() throws Exception{
    Set<String> set = Sets.newHashSet();
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.CAPACITY, String.valueOf(2));
    overrides.put(FileChannelConfiguration.CHECKPOINT_INTERVAL, "10000");
    FileChannel channel = createFileChannel(overrides);
    channel.start();
    //Force a checkpoint by committing a transaction
    Transaction tx = channel.getTransaction();
    Set<String> in = putWithoutCommit(channel, tx, "putWithoutCommit", 1);
    forceCheckpoint(channel);
    tx.commit();
    tx.close();
    channel.stop();

    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> out = takeEvents(channel, 1);
    compareInputAndOut(in, out);
    channel.stop();

  }

  @Test
  public void testPutCheckpointCommitCheckpointReplay() throws Exception {
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.CAPACITY, String.valueOf(2));
    overrides.put(FileChannelConfiguration.CHECKPOINT_INTERVAL, "10000");
    FileChannel channel = createFileChannel(overrides);
    channel.start();
    //Force a checkpoint by committing a transaction
    Transaction tx = channel.getTransaction();
    Set<String> in = putWithoutCommit(channel, tx, "doubleCheckpoint", 1);
    forceCheckpoint(channel);
    tx.commit();
    tx.close();
    forceCheckpoint(channel);
    channel.stop();

    channel = createFileChannel(overrides);
    channel.start();
    Assert.assertTrue(channel.isOpen());
    Set<String> out = takeEvents(channel, 5);
    compareInputAndOut(in, out);
    channel.stop();
  }

  @Test
  public void testReferenceCounts() throws Exception {
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put(FileChannelConfiguration.CHECKPOINT_INTERVAL, "10000");
    overrides.put(FileChannelConfiguration.MAX_FILE_SIZE, "20");
    final FileChannel channel = createFileChannel(overrides);
    channel.start();
    Set<String> in = putEvents(channel, "testing-reference-counting", 1, 15);
    Transaction tx = channel.getTransaction();
    takeWithoutCommit(channel, tx, 10);
    forceCheckpoint(channel);
    tx.rollback();
    //Since we did not commit the original transaction. now we should get 15
    //events back.
    final Set<String> takenEvents = Sets.newHashSet();
    Executors.newSingleThreadExecutor().submit(new Runnable() {
      @Override
      public void run() {
        try {
          takenEvents.addAll(takeEvents(channel, 15));
        } catch (Exception ex) {
          Throwables.propagate(ex);
        }
      }
    }).get();
    Assert.assertEquals(15, takenEvents.size());
  }


  private static void copyDecompressed(String resource, File output)
          throws IOException {
    URL input =  Resources.getResource(resource);
    long copied = ByteStreams.copy(new GZIPInputStream(input.openStream()),
            new FileOutputStream(output));
    LOG.info("Copied " + copied + " bytes from " + input + " to " + output);
  }

}
