/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every thread Cobblestone runs off the server's own, by the kind of work it does.
 *
 * <ul>
 *   <li><b>Compute</b>: searches and region builds, which only use the processor and never block.
 *       Its work can be dropped at any moment, so {@link #close} interrupts it.
 *   <li><b>IO</b>: work that blocks on a disk or a database. It never shares a thread with a
 *       search, so a slow write cannot hold one up, and {@link #close} lets it finish.
 *   <li><b>Lanes</b>: named queues on the IO threads that run their work one task at a time, in the
 *       order it was given: what a single database connection or a file wants.
 *   <li><b>The timer</b>: one thread every delay waits on, which only hands tasks off, so a delay
 *       never holds a thread that could be working.
 * </ul>
 *
 * <p>Nothing here is tied to a game or a server. A platform starts one when it enables, hands out
 * what each part needs, and closes it last. Work given once it has closed is dropped and logged
 * rather than refused: a server's events do not all stop the moment a plugin disables, and the
 * thread that handles one is no place for an exception about Cobblestone shutting down.
 */
public final class Workers implements AutoCloseable {

  /** How many IO threads to run when the configuration leaves it to Cobblestone. */
  public static final int DEFAULT_IO_THREADS = 2;

  /** How long {@link #close} waits for IO work, writes most of all, to finish. */
  private static final Duration DRAIN = Duration.ofSeconds(10);

  private final CobblestoneLogger logger;
  private final ExecutorService compute;
  private final ExecutorService io;
  private final ScheduledThreadPoolExecutor timer;
  private final Duration drain;
  private final Executor computeAfterClose;
  private final Executor ioAfterClose;
  private volatile boolean closed;
  private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
  private final TimeSource time;
  private final SearchScheduler search;

  /**
   * Starts the threads, each pool as its work arrives.
   *
   * @param logger where work dropped after closing is reported
   * @param computeThreads how many compute threads, or 0 for {@link #defaultComputeThreads()}
   * @param ioThreads how many IO threads, or 0 for {@link #DEFAULT_IO_THREADS}
   */
  public Workers(CobblestoneLogger logger, int computeThreads, int ioThreads) {
    this(logger, computeThreads, ioThreads, DRAIN);
  }

  Workers(CobblestoneLogger logger, int computeThreads, int ioThreads, Duration drain) {
    this.logger = new ScopedCobblestoneLogger(logger, "Workers");
    this.drain = drain;
    // Below the server's own threads: this is work a search waits on, never on a tick.
    this.compute =
        Executors.newFixedThreadPool(
            computeThreads(computeThreads),
            daemons("cobblestone-compute", Thread.NORM_PRIORITY - 1));
    this.io =
        Executors.newFixedThreadPool(
            ioThreads(ioThreads), daemons("cobblestone-io", Thread.NORM_PRIORITY));
    this.timer =
        new ScheduledThreadPoolExecutor(1, daemons("cobblestone-timer", Thread.NORM_PRIORITY));
    this.timer.setRemoveOnCancelPolicy(true);
    this.computeAfterClose = droppingAfterClose(compute, "compute", false);
    this.ioAfterClose = droppingAfterClose(io, "the IO threads", true);
    TimeSource system = TimeSource.system(timer, computeAfterClose);
    this.time =
        new TimeSource() {
          @Override
          public long millis() {
            return system.millis();
          }

          @Override
          public void schedule(Runnable task, long delayMillis) {
            droppingAfterClose(delayed -> system.schedule(delayed, delayMillis), "delayed", false)
                .execute(task);
          }
        };
    this.search =
        new SearchScheduler() {
          @Override
          public Executor executor() {
            return computeAfterClose;
          }

          @Override
          public TimeSource time() {
            return time;
          }
        };
  }

  /**
   * Returns how many compute threads to run when the configuration leaves it to Cobblestone: half
   * the processors, and at least two.
   *
   * @return the thread count
   */
  public static int defaultComputeThreads() {
    return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
  }

  /**
   * Returns how many compute threads a configured count asks for: the count itself, or {@link
   * #defaultComputeThreads()} for zero or less.
   *
   * @param configured the configured count
   * @return the thread count
   */
  public static int computeThreads(int configured) {
    return configured > 0 ? configured : defaultComputeThreads();
  }

  /**
   * Returns how many IO threads a configured count asks for: the count itself, or {@link
   * #DEFAULT_IO_THREADS} for zero or less.
   *
   * @param configured the configured count
   * @return the thread count
   */
  public static int ioThreads(int configured) {
    return configured > 0 ? configured : DEFAULT_IO_THREADS;
  }

  /**
   * Returns the compute threads, for work that only uses the processor.
   *
   * @return the executor
   */
  public Executor compute() {
    return computeAfterClose;
  }

  /**
   * Returns the IO threads, for work that blocks and has no order to keep.
   *
   * @return the executor
   */
  public Executor io() {
    return ioAfterClose;
  }

  /**
   * Returns a lane on the IO threads: its work runs one task at a time, in the order it was given.
   * The same name returns the same lane.
   *
   * @param name the lane's name
   * @return the lane
   */
  public Executor lane(String name) {
    Lane lane = lanes.computeIfAbsent(name, key -> new Lane(io));
    return droppingAfterClose(lane, "the " + name + " lane", true);
  }

  /**
   * Returns the system clock, its delayed tasks waiting on the timer and running on the compute
   * threads.
   *
   * @return the time source
   */
  public TimeSource time() {
    return time;
  }

  /**
   * Returns where searches run: the compute threads and {@link #time()}.
   *
   * @return the search scheduler
   */
  public SearchScheduler search() {
    return search;
  }

  /**
   * Stops every thread: the timer and the compute threads at once, interrupting work in progress,
   * then the IO threads once the work already given to them, every lane's included, has finished or
   * ten seconds have passed. Work given after this is dropped, and logged.
   *
   * <p>If the wait runs out, the IO threads are interrupted and every lane drops what it has not
   * started: whoever closes this is about to close what that work writes to, so it must not run
   * after this returns. A task already underway cannot be stopped, only interrupted.
   */
  @Override
  public void close() {
    // First, so that every refusal from here on is known to be this and not a fault.
    closed = true;
    timer.shutdownNow();
    compute.shutdownNow();
    io.shutdown();
    try {
      if (!io.awaitTermination(drain.toNanos(), TimeUnit.NANOSECONDS)) {
        abandon();
      }
    } catch (InterruptedException interrupted) {
      abandon();
      Thread.currentThread().interrupt();
    }
  }

  private void abandon() {
    lanes.values().forEach(Lane::abandon);
    io.shutdownNow();
  }

  /**
   * Wraps an executor so that, once this is closed, what it refuses is dropped and logged instead.
   * Work a closing executor still accepts, such as a busy lane's, runs as usual.
   *
   * @param kind what the work is, for the log
   * @param lost whether dropping it loses something, such as a write, rather than work moot once
   *     nothing will use its result
   */
  private Executor droppingAfterClose(Executor target, String kind, boolean lost) {
    return task -> {
      try {
        target.execute(task);
      } catch (RejectedExecutionException refused) {
        if (!closed) {
          throw refused;
        }
        if (lost) {
          logger.warn("Dropped work for {} given after shutting down", kind);
        } else {
          logger.debug("Dropped {} work given after shutting down", kind);
        }
      }
    };
  }

  private static ThreadFactory daemons(String name, int priority) {
    AtomicInteger count = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
      thread.setDaemon(true);
      thread.setPriority(priority);
      return thread;
    };
  }

  /**
   * Work run one task at a time on a shared pool, in the order it was given.
   *
   * <p>A lane holds a pool thread only while it has work, and keeps it until the work runs out, so
   * a task given to a lane that is running never needs the pool again: once {@link #close} has
   * begun, a busy lane still finishes.
   */
  private static final class Lane implements Executor {

    private final Executor pool;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean running;
    private boolean abandoned;

    Lane(Executor pool) {
      this.pool = pool;
    }

    @Override
    public void execute(Runnable task) {
      synchronized (this) {
        if (abandoned) {
          throw new RejectedExecutionException("Lane abandoned on close");
        }
        queue.addLast(task);
        if (running) {
          return;
        }
        running = true;
      }
      try {
        pool.execute(this::drain);
      } catch (RejectedExecutionException refused) {
        synchronized (this) {
          queue.clear();
          running = false;
        }
        throw refused;
      }
    }

    /** Drops the work not yet started, and runs none given later. */
    synchronized void abandon() {
      abandoned = true;
      queue.clear();
    }

    private void drain() {
      while (true) {
        Runnable next;
        synchronized (this) {
          next = abandoned ? null : queue.pollFirst();
          if (next == null) {
            running = false;
            return;
          }
        }
        try {
          next.run();
        } catch (Throwable failure) {
          // One task failing must not stall the ones behind it. An Error is caught too: let out, it
          // would leave the lane marked running with no thread draining it, and its work stuck.
          Thread thread = Thread.currentThread();
          thread.getUncaughtExceptionHandler().uncaughtException(thread, failure);
        }
      }
    }
  }
}
