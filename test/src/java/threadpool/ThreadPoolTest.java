package threadpool;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * demonstrate that caller-runs-policy is not always a good idea
 * 
 * Just run this as a unit test. On my machine with 4 cores I get a speedup near
 * 4 (the number of threads) with the SimpleThreadPool, while the JDK ThreadPool
 * only gets to a speedup of around 3.
 */
public class ThreadPoolTest {
  private static final int NTHREADS = 4;
  private static final int QUICKTASK_MAX_MILLIS = 20;
  private static final int SLOW_TASK_MAX_MILLIS = 500;
  private static final int SLOW_TASK_PERCENT = 10;
  private static final int NTASKS = 2000;
  private static final int QSIZE = 4;
  
  @Test
  public void callerRunsTest() throws Exception {
    BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QSIZE);
    ThreadPoolExecutor tpe =
        new ThreadPoolExecutor(NTHREADS, NTHREADS, 2, TimeUnit.HOURS, queue,
                               new ThreadPoolExecutor.CallerRunsPolicy());
    runTasks(tpe);
    tpe.shutdownNow();
    
    SimpleThreadPool se = new SimpleThreadPool.Builder()
        .setMaxThreads(NTHREADS)
        .setQueueSize(QSIZE)
        .setKeepAlive(2, TimeUnit.HOURS)
        .build();
    runTasks(se);
  }
  
  private void runTasks(Executor tpe) throws Exception {
    AtomicLong delayTotal = new AtomicLong();
    Semaphore sem = new Semaphore(0);
    Producer producer = new Producer(tpe, NTASKS, sem, delayTotal);
    Thread producerThread = new Thread(producer);
    long start = System.currentTimeMillis();
    producerThread.start();
    sem.acquire(NTASKS);
    long end = System.currentTimeMillis();
    long delta = end-start;
    // correct for the last slow task typically sticking out at the end where no
    // parallelism is possible anymore.
    delta -= SLOW_TASK_MAX_MILLIS/2;
    double avg = delta/(double)NTASKS;
    double faster = delayTotal.get()-delta;
    double speedup = delayTotal.get()/(double)delta;
    System.out.println(tpe.getClass().getName()+": "+(end-start)
                       +"ms for "+avg+"ms avg"
                       + ", faster by="+faster+"ms, speedup="+speedup);
  }
  /*-******************************************************************/
  private static class Producer implements Runnable {
    private final Executor tpe;
    private final Random rand = new Random(2017_12_24);
    private final Task[] tasks;

    /**
     * creates producer for the given number of tasks to feed to the given
     * Executor. Tasks will report their end to the given Semaphore. Total wait
     * time when scheduling tasks is accumulated in delayTotal.
     * 
     * Tasks are pre-generated and only feed as quickly as possible into the
     * executor when run() is called.
     */
    public Producer(Executor tpe, int toGenerate,
                    Semaphore sem, AtomicLong delayTotal) {
      this.tpe = tpe;
      this.tasks = new Task[toGenerate];
      for (int i=0; i<tasks.length; i++) {
        int delay = randomDelay();
        delayTotal.addAndGet(delay);
        tasks[i] = new Task(sem, delay);
      }
    }
    
    @Override
    public void run() {
      for (Task task : tasks) {
        tpe.execute(task);
      }
    }

    private int randomDelay() {
      int delayType = rand.nextInt(100);
      if (delayType<SLOW_TASK_PERCENT) {
        return rand.nextInt(SLOW_TASK_MAX_MILLIS);
      }
      return rand.nextInt(QUICKTASK_MAX_MILLIS);
    }
  }
   /*-******************************************************************/
  private static class Task implements Runnable {
    private final long delay;
    private final Semaphore sem;

    /**
     * create task to take at least the given delay milliseconds and report
     * success to the given Semaphore.
     */
    public Task(Semaphore sem, long delay) {
      this.sem = sem;
      this.delay = delay;
    }

    @Override
    public void run() {
      try {
        Thread.sleep(delay);
        //System.out.println(Thread.currentThread()+" slept "+delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        e.printStackTrace();
      } finally {
        sem.release();
      }
    }

  }
}
