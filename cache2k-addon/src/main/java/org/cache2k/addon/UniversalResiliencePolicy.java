package org.cache2k.addon;

/*
 * #%L
 * cache2k addon
 * %%
 * Copyright (C) 2000 - 2020 headissue GmbH, Munich
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

import org.cache2k.Cache2kBuilder;
import org.cache2k.CacheEntry;
import org.cache2k.config.Cache2kConfig;
import org.cache2k.config.CacheBuildContext;
import org.cache2k.config.CustomizationSupplierWithConfig;
import org.cache2k.io.LoadExceptionInfo;
import org.cache2k.io.ResiliencePolicy;

import java.time.Duration;
import java.util.Random;

import static org.cache2k.config.Cache2kConfig.ETERNAL_DURATION;
import static org.cache2k.config.Cache2kConfig.UNSET_LONG;

/**
 * Resilience policy which implements a exponential back off and randomization
 * of the retry intervals.
 *
 * @author Jens Wilke
 */
public class UniversalResiliencePolicy<K, V> implements ResiliencePolicy<K, V> {

  public static final Supplier SUPPLIER = new Supplier();
  public static final <K, V> Supplier<K, V> supplier() { return SUPPLIER; }
  public static final <K, V> Supplier<K, V> enable(Cache2kBuilder<K, V> builder) {
    builder.config().setResiliencePolicy(supplier());
    return supplier();
  }

  private static final UniversalResilienceConfig EMPTY =
    new UniversalResilienceConfig();

  /**
   * We use a common random instance. Since this is only called for an exception
   * we do not bother for contention.
   */
  static final Random SHARED_RANDOM = new Random();

  static final int RETRY_PERCENT_OF_RESILIENCE_DURATION = 10;
  static final int MIN_RETRY_INTERVAL = 0;

  private double multiplier = 1.5;
  private double randomization = 0.5;

  private long resilienceDuration;
  private long maxRetryInterval;
  private long retryInterval;
  private boolean suppressExceptions;

  /**
   * Construct a resilience policy with multiplier 1.5 and randomization 0.5.
   */
  public UniversalResiliencePolicy(Cache2kConfig<K, V> cfgRoot) {
    this(cfgRoot.getSections().getSection(UniversalResilienceConfig.class),
      cfgRoot.getExpireAfterWrite());
  }

  public UniversalResiliencePolicy(UniversalResilienceConfig cfg, Duration expireAfterWrite) {
    if (cfg == null) { cfg = EMPTY; }
    suppressExceptions = cfg.isSuppressExceptions();
    resilienceDuration = toMillis(cfg.getResilienceDuration());
    maxRetryInterval = toMillis(cfg.getMaxRetryInterval());
    retryInterval = toMillis(cfg.getRetryInterval());
    if (resilienceDuration == UNSET_LONG) {
      if (expireAfterWrite == ETERNAL_DURATION) {
        resilienceDuration = 0;
      } else {
        if (expireAfterWrite != null) {
          resilienceDuration = expireAfterWrite.toMillis();
        } else {
          resilienceDuration = UNSET_LONG;
        }
      }
    } else {
      if (maxRetryInterval == UNSET_LONG) {
        maxRetryInterval = resilienceDuration;
      }
    }
    if (maxRetryInterval == UNSET_LONG && retryInterval == UNSET_LONG) {
      maxRetryInterval = resilienceDuration;
    }
    if (retryInterval == UNSET_LONG) {
      retryInterval = resilienceDuration * RETRY_PERCENT_OF_RESILIENCE_DURATION / 100;
      retryInterval = Math.min(retryInterval, maxRetryInterval);
      retryInterval = Math.max(MIN_RETRY_INTERVAL, retryInterval);
    }
    if (retryInterval > maxRetryInterval) {
      maxRetryInterval = retryInterval;
    }
    if (maxRetryInterval > resilienceDuration && resilienceDuration != 0) {
      resilienceDuration = maxRetryInterval;
    }
    if (resilienceDuration != UNSET_LONG && !suppressExceptions) {
      throw new IllegalArgumentException(
        "exception suppression disabled " +
        "but resilience duration set");
    }
    if (!suppressExceptions) {
      resilienceDuration = 0;
    }
  }

  static long toMillis(Duration d) {
    if (d == null) {
      return Cache2kConfig.UNSET_LONG;
    }
    return d.toMillis();
  }

  public double getMultiplier() {
    return multiplier;
  }

  public void setMultiplier(double multiplier) {
    this.multiplier = multiplier;
  }

  public double getRandomization() {
    return randomization;
  }

  public void setRandomization(double randomization) {
    this.randomization = randomization;
  }

  public long getResilienceDuration() { return resilienceDuration; }

  public long getMaxRetryInterval() { return maxRetryInterval; }

  public long getRetryInterval() { return retryInterval; }

  @Override
  public long suppressExceptionUntil(K key,
                                     LoadExceptionInfo<K> loadExceptionInfo,
                                     CacheEntry<K, V> cachedContent) {
    if (resilienceDuration == 0 || resilienceDuration == Long.MAX_VALUE) {
      return resilienceDuration;
    }
    long maxSuppressUntil = loadExceptionInfo.getSinceTime() + resilienceDuration;
    long deltaMs = calculateRetryDelta(loadExceptionInfo);
    return Math.min(loadExceptionInfo.getLoadTime() + deltaMs, maxSuppressUntil);
  }

  private long calculateRetryDelta(LoadExceptionInfo loadExceptionInfo) {
    long delta = (long)
      (retryInterval * Math.pow(multiplier, loadExceptionInfo.getRetryCount()));
    delta += SHARED_RANDOM.nextDouble() * randomization * delta;
    return Math.min(delta, maxRetryInterval);
  }

  @Override
  public long retryLoadAfter(K key, LoadExceptionInfo<K> loadExceptionInfo) {
    if (retryInterval == 0 || retryInterval == Long.MAX_VALUE) {
      return retryInterval;
    }
    return loadExceptionInfo.getLoadTime() + calculateRetryDelta(loadExceptionInfo);
  }

  public static class Supplier<K, V>
    implements CustomizationSupplierWithConfig
      <K, V, ResiliencePolicy<K, V>, UniversalResilienceConfig, UniversalResilienceConfig.Builder> {
    @Override
    public ResiliencePolicy<K, V> supply(CacheBuildContext buildContext) {
      UniversalResiliencePolicy<K, V> policy =
        new UniversalResiliencePolicy<>(buildContext.getConfiguration());
      if (policy.getRetryInterval() == 0 && policy.getResilienceDuration() == 0) {
        return ResiliencePolicy.disabledPolicy();
      }
      return policy;
    }
    @Override
    public Class<UniversalResilienceConfig> getConfigClass() {
      return UniversalResilienceConfig.class;
    }
  }

}