package org.cache2k.core.timing;

/*
 * #%L
 * cache2k implementation
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

/**
 * @author Jens Wilke
 */
public abstract class TimerStructure {

  /**
   * Insert task.
   *
   * @return this is the earliest, update scheduler
   */
  public abstract boolean schedule(SimpleTimerTask task, long time);

  public abstract void cancel(SimpleTimerTask t);

  public abstract void cancel();

  /**
   * Execute tasks
   *
   * @return next execution time for scheduler, or 0
   */
  public abstract SimpleTimerTask removeNextToRun(long time);

  public abstract long nextRun();

}