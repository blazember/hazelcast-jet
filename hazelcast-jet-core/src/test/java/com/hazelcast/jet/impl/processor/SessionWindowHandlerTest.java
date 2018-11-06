/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.processor;

import com.hazelcast.jet.aggregate.AggregateOperations;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.Watermark;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import com.hazelcast.jet.datamodel.WindowResult;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.jet.function.DistributedToLongFunction;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.Repeat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.core.test.TestSupport.SAME_ITEMS_ANY_ORDER;
import static com.hazelcast.jet.core.test.TestSupport.verifyProcessor;
import static com.hazelcast.jet.function.DistributedFunctions.entryKey;
import static java.util.Arrays.asList;
import static java.util.Collections.shuffle;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
public class SessionWindowHandlerTest {

    private static final int SESSION_TIMEOUT = 10;
    private DistributedSupplier<Processor> supplier;
    private CustomWindowP lastSuppliedProcessor;
    private SessionWindowHandler lastSuppliedHandler;

    @Before
    public void before() {
        supplier = () -> {
            lastSuppliedHandler = new SessionWindowHandler<>(
                    SESSION_TIMEOUT,
                    AggregateOperations.counting(),
                    WindowResult::new
            );
            return lastSuppliedProcessor = new CustomWindowP<>(
                    lastSuppliedHandler,
                    singletonList((DistributedToLongFunction<Entry<Object, Long>>) Entry::getValue),
                    singletonList(entryKey())
            );
        };
    }

    @After
    public void after() {
        // Check against memory leaks
        assertTrue("keyToWindows not empty", lastSuppliedProcessor.states.isEmpty());
        assertTrue("deadlineToKeys not empty", lastSuppliedHandler.deadlineToKeys.isEmpty());
    }

    @Test
    public void when_orderedEventsWithOneKey() {
        List<Object> events = eventsWithKey("a");
        assertCorrectness(events);
    }

    @Test
    @Repeat(10)
    public void when_disorderedEventsWithOneKey() {
        List<Object> events = eventsWithKey("a");
        shuffle(events);
        assertCorrectness(events);
    }

    @Test
    public void when_orderedEventsWithThreeKeys() {
        List<Object> events = new ArrayList<>();
        events.addAll(eventsWithKey("a"));
        events.addAll(eventsWithKey("b"));
        events.addAll(eventsWithKey("c"));
        assertCorrectness(events);
    }

    @Test
    @Repeat(10)
    public void when_disorderedEVentsWithThreeKeys() {
        List<Object> events = new ArrayList<>();
        events.addAll(eventsWithKey("a"));
        events.addAll(eventsWithKey("b"));
        events.addAll(eventsWithKey("c"));
        shuffle(events);
        assertCorrectness(events);
    }

    @Test
    public void when_batchProcessing_then_flushEverything() {
        List<Object> inbox = new ArrayList<>();

        // Given
        inbox.addAll(eventsWithKey("a"));
        // This watermark will cause the first session to be emitted, but not the second.
        // The second session will be emitted in complete()
        inbox.add(new Watermark(25));

        verifyProcessor(supplier)
                .input(inbox)
                .expectOutput(asList(
                        new WindowResult(1, 22, "a", 3),
                        new Watermark(25),
                        new WindowResult(30, 50, "a", 3)));
    }

    @Test
    public void when_lateEvent_then_dropped() {
        verifyProcessor(supplier)
                .input(asList(
                        new Watermark(20),
                        entry("key", 19L)
                ))
                .expectOutput(singletonList(
                        new Watermark(20)
                ));
    }

    private void assertCorrectness(List<Object> events) {
        List<Object> expectedOutput = events.stream()
                                               .map(e -> ((Entry<String, Long>) e).getKey())
                                               .flatMap(SessionWindowHandlerTest::expectedSessions)
                                               .distinct()
                                               .collect(toList());
        events.add(new Watermark(100));
        expectedOutput.add(new Watermark(100));

        try {
            verifyProcessor(supplier)
                    .outputChecker(SAME_ITEMS_ANY_ORDER)
                    .input(events)
                    .expectOutput(expectedOutput);
        } catch (AssertionError e) {
            System.err.println("Tested with events: " + events);
            throw e;
        }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) {
            SessionWindowHandlerTest test = new SessionWindowHandlerTest();
            test.before();
            test.runBench();
        }
    }

    @SuppressWarnings("checkstyle:emptystatement")
    private void runBench() throws Exception {
        Random rnd = ThreadLocalRandom.current();
        long start = System.nanoTime();
        long eventCount = 40_000_000;
        long keyCount = 2000;
        long eventsPerKey = eventCount / keyCount;
        int spread = 4000;
        int timestampStep = 20;
        int wmLag = 2000;
        long wmInterval = 100;
        System.out.format("keyCount %,d eventsPerKey %,d wmInterval %,d%n", keyCount, eventsPerKey, wmInterval);
        TestOutbox outbox = new TestOutbox(1024);
        supplier.get(); // called for side-effect of assigning to lastSuppliedProcessor
        lastSuppliedProcessor.init(outbox, new TestProcessorContext());

        for (long idx = 0; idx < eventsPerKey; idx++) {
            long timestampBase = idx * timestampStep;
            for (long key = (timestampBase / SESSION_TIMEOUT) % 2; key < keyCount; key += 2) {
                while (!lastSuppliedProcessor.tryProcess(0, entry(key, timestampBase + rnd.nextInt(spread)))) { }
                while (!lastSuppliedProcessor.tryProcess(0, entry(key, timestampBase + rnd.nextInt(spread)))) { }
            }
            if (idx % wmInterval == 0) {
                long wm = timestampBase - wmLag;
                int winCount = 0;
                while (!lastSuppliedProcessor.tryProcessWatermark(new Watermark(wm))) {
                    while (outbox.queue(0).poll() != null) {
                        winCount++;
                    }
                }
                while (outbox.queue(0).poll() != null) {
                    winCount++;
                }
            }
        }
        long took = System.nanoTime() - start;
        System.out.format("%nThroughput %,3d events/second%n", SECONDS.toNanos(1) * eventCount / took);
    }

    private static List<Object> eventsWithKey(String key) {
        return new ArrayList<>(asList(
                // session 1: [12..22]
                entry(key, 1L),
                entry(key, 6L),
                entry(key, 12L),

                // session 2: [30..50]
                entry(key, 30L),
                entry(key, 35L),
                entry(key, 40L)
        ));
    }

    private static Stream<WindowResult<String, Long>> expectedSessions(String key) {
        return Stream.of(
                new WindowResult<>(1, 22, key, 3L),
                new WindowResult<>(30, 50, key, 3L)
        );
    }
}