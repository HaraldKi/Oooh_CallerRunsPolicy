package threadpool;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimpleThreadPool implements Executor {
  private final long keepAliveMillis;
  private final int maxThreads;
  private final BlockingQueue<Runnable> queue;
  private final Set<Thread> threads = new HashSet<>();
  private final AtomicBoolean isShutdown = new AtomicBoolean(false);
  /*-******************************************************************/
  public static final class Builder {
    private long keepAliveMillis = 1000;
    private int maxThreads = 4;
    private int queueSize = 4;

    public Builder setQueueSize(int queueSize) {
      this.queueSize = queueSize;
      return this;
    }

    public Builder setKeepAlive(int duration, TimeUnit u) {
      this.keepAliveMillis = u.toMillis(duration);
      return this;
    }

    public Builder setMaxThreads(int count) {
      if (count<1) {
        throw new IllegalArgumentException("number of threads is "+count
                                            +" but must be >0");
      }
      this.maxThreads = count;
      return this;
    }

    public SimpleThreadPool build() {
      return new SimpleThreadPool(maxThreads, queueSize, keepAliveMillis);
    }
  }
  /*-******************************************************************/
  private SimpleThreadPool(int maxThreads, int qSize, long keepAliveMillis) {
    this.keepAliveMillis = keepAliveMillis;
    this.queue = new LinkedBlockingQueue<>(qSize);
    this.maxThreads = maxThreads;
    addWorkerThread();
  }

  private synchronized void addWorkerThread() {
    if (threads.size()>=maxThreads) {
      return;
    }

    Worker w = new Worker(threads.isEmpty());
    Thread t = new Thread(w, "sane"+threads.size());
    threads.add(t);
    t.start();
  }

  public synchronized void removeWorkerThread(Thread t) {
    threads.remove(t);
  }

  @Override
  public void execute(Runnable cmd) {
    if (isShutdown.get()) {
      throw new RejectedExecutionException(this.getClass().getName()+
                                           " already shut down");
    }
    if (cmd==null) {
      throw new NullPointerException("given command may not be null");
    }

    try {
      queue.put(cmd);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RejectedExecutionException("received interrupt while trying to"
        + "queue the command", e);
    }
  }

  /**
   * prevent scheduling of further tasks and interrupt all threads, which will
   * likely preempt the tasks being executed and terminate the threads.
   *
   * Does not wait for tasks and/or threads to terminate.
   *
   * All calls to this method after the first will return an empty list for the
   * outstanding tasks.
   * 
   * @return list of tasks that never commenced execution
   */
  public List<Runnable> shutdownNow() {
    if (isShutdown.getAndSet(true)) {
      return Collections.emptyList();
    }
    List<Runnable> result = new LinkedList<>();
    result.addAll(queue);

    for (Thread t : threads) {
      t.interrupt();
    }
    return result;
  }
  /*-******************************************************************/
  private class Worker implements Runnable {
    private final boolean primary;
    private long lastDone = Long.MAX_VALUE;
    public Worker(boolean primary) {
      this.primary = primary;
    }

    @Override
    public void run() {
      while (true) {
        Runnable r = null;
        if (!queue.isEmpty()) {
          addWorkerThread();
        }
        try {
          r = queue.poll(keepAliveMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          removeWorkerThread(Thread.currentThread());
          return;
        }
        if (r!=null) {
          long delta = System.currentTimeMillis()-lastDone;
          if (delta>10) {
            System.out.println("since lastDone: "+delta);
          }
          r.run();
          lastDone = System.currentTimeMillis();
          continue;
        }
        if (!primary || isShutdown.get()) {
          removeWorkerThread(Thread.currentThread());
          return;
        }
      }
    }
  }
}
