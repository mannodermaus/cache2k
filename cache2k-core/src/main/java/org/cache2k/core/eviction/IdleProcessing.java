package org.cache2k.core.eviction;

/*
 * #%L
 * cache2k core implementation
 * %%
 * Copyright (C) 2000 - 2021 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.core.api.InternalCacheCloseContext;
import org.cache2k.core.api.NeedsClose;
import org.cache2k.operation.Scheduler;
import org.cache2k.operation.TimeReference;

import java.util.concurrent.TimeUnit;

/**
 * Scans for idle entries. This uses a scheduler to wakeup in regular intervals and adds
 * additional scans in the eviction and eventually evicts the entry if it had no access since
 * the last scan round. The duration of the scan round is controlled via
 * {@link org.cache2k.Cache2kBuilder#idleScanTime(long, TimeUnit)} If there is normal eviction
 * activity in the cache, e.g. when a capacity limit is configured, only the difference of
 * scans is executed to achieve the configured scan time to cover all cached entries.
 * If eviction scans are more than needed for a the scan round time, the processing stops
 * and waits for a full round duration.
 *
 * @author Jens Wilke
 */
public class IdleProcessing implements NeedsClose {

  private static final long IDLE = -1;

  private final long roundTicks;
  private final Scheduler scheduler;
  private final TimeReference clock;
  private final Eviction eviction;
  /** Only for toString output */
  private long lastWakeupTicks;
  private long roundStartTicks = IDLE;
  /** Scan count at round start */
  private long roundStartScans;
  /** Size at round start. */
  private long scansPerRound;
  private long wakeupInterval;
  /** Entries evicted via idle eviction. */
  private long evictedCount = 0;
  private long lastScanCount = 0;
  private long roundStartCount = 0;
  private long roundCompleteCount = 0;
  private long roundAbortCount = 0;

  public IdleProcessing(TimeReference clock, Scheduler scheduler,
                        Eviction eviction, long roundTicks) {
    this.scheduler = scheduler;
    this.clock = clock;
    this.eviction = eviction;
    this.roundTicks = roundTicks;
    synchronized (this) {
      scheduleIdleWakeup(clock.millis(), eviction.getMetrics());
    }
  }

  public void scanWakeup() {
    int extraScan;
    synchronized (this) {
      long now = clock.millis();
      lastWakeupTicks = now;
      EvictionMetrics metrics = eviction.getMetrics();
      long expectedScans =
        scansPerRound * (now - roundStartTicks) / roundTicks +
          roundStartScans - metrics.getIdleNonEvictDrainCount();
      long remainingScans = roundStartScans + scansPerRound - expectedScans;
      extraScan = (int) (expectedScans - metrics.getScanCount());
      if (extraScan < -remainingScans) { scheduleIdleWakeup(now, metrics); return; }
      if (extraScan <= 0) { scheduleNextWakeup(now, wakeupInterval); return; }
      if (now >= roundStartTicks + roundTicks) {
        startNewScanRound(now, metrics);
      } else {
        scheduleNextWakeup(now, wakeupInterval);
      }
    }
    long count = eviction.evictIdleEntries(extraScan);
    synchronized (this) { evictedCount += count; }
  }

  static final int MAX_SCAN_PER_WAKEUP = 50;
  static final int PRECISION_SIZE_THRESHOLD = 100;

  static long calculateWakeupTicks(long roundTicks, long scansPerRound) {
    if (scansPerRound <= PRECISION_SIZE_THRESHOLD) {
      return roundTicks / scansPerRound;
    }
    long ticksThroughput = roundTicks / PRECISION_SIZE_THRESHOLD;
    long ticksLimited = roundTicks / (scansPerRound / MAX_SCAN_PER_WAKEUP);
    return Math.min(ticksThroughput, ticksLimited);
  }

  /**
   *
   */
  private void idleWakeup() {
    synchronized (this) {
      long now = clock.millis();
      EvictionMetrics metrics = eviction.getMetrics();
      long size = metrics.getSize();
      long scansSinceLastWakeup = metrics.getScanCount() - lastScanCount;
      boolean enoughScanActivity = scansSinceLastWakeup >= size;
      boolean empty = size == 0;
      if (empty || enoughScanActivity) {
        scheduleIdleWakeup(now, metrics);
        return;
      }
      startNewScanRound(now, metrics);
    }
  }

  private void startNewScanRound(long now, EvictionMetrics metrics) {
    if (roundStartTicks != IDLE) { roundCompleteCount++; }
    long size = metrics.getSize();
    scansPerRound = size;
    roundStartCount++;
    roundStartTicks = now;
    roundStartScans = eviction.startNewIdleScanRound();
    wakeupInterval = clock.toMillis(calculateWakeupTicks(roundTicks, scansPerRound));
    scheduleNextWakeup(now, wakeupInterval);
  }

  private void scheduleIdleWakeup(long now, EvictionMetrics metrics) {
    if (roundStartTicks != IDLE) { roundAbortCount++; }
    roundStartTicks = IDLE;
    lastScanCount = metrics.getScanCount();
    scheduler.schedule(this::idleWakeup, roundTicks + now);
  }

  private void scheduleNextWakeup(long now, long deltaMillis) {
    scheduler.schedule(this::scanWakeup, deltaMillis + now);
  }

  @Override
  public synchronized void close(InternalCacheCloseContext closeContext) {
    closeContext.closeCustomization(scheduler, "scheduler for idle processing");
  }

  private int getIdleScanPercent() {
    return (int) ((lastWakeupTicks - roundStartTicks) * 100 / roundTicks);
  }

  @Override
  public synchronized String toString() {
    return
      "idleScanRoundStarted=" + roundStartCount + ", " +
      "idleScanRoundCompleted=" + roundCompleteCount + ", " +
      "idleScanRoundAbort=" + roundAbortCount + ", " +
      "idleEvicted=" + evictedCount +
     (roundStartTicks == IDLE ? "" : ", idleScanPercent=" + (getIdleScanPercent()));
  }

}
