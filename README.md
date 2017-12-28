# Oooh_CallerRunsPolicy
Small demonstration code to show that Java's ThreadPoolExecutor CallerRunsPolicy can be bad for performance

## Background

When tasks are submitted to a [ThreadPoolExecutor](https://docs.oracle.com/javase/9/docs/api/java/util/concurrent/ThreadPoolExecutor.html) that cannot currently handle more tasks, a [RejectionPolicy](https://docs.oracle.com/javase/9/docs/api/java/util/concurrent/RejectedExecutionHandler.html) is invoked to decide what to do. Only one of them, the [CallerRunsPolicy](https://docs.oracle.com/javase/9/docs/api/java/util/concurrent/ThreadPoolExecutor.CallerRunsPolicy.html) does not throw away or abort a task. The CallerRunsPolicy lets the calling thread (the publisher) run the task to be submitted itself instead of really adding it to the task queue.

If all tasks take roughly the same amount of time to finish, this is a good strategy to (a) throttle the frequency of incoming tasks without dropping tasks and (b) not let the producer thread idle.

But lets look at a well tuned system, finishing as many tasks as possible per time. It has the following characteristics:

1. All CPUs are running red hot at 100%. Otherwise the CPU idle time could be used to process more tasks, obviously.

2. There should always be slight pressure on the thread pool, meaning whenever a task is finished, at least one more should be ready for processing.

This, however, means that the producer, on the average, should be faster than all consumers combined. As a result, the producer will eventually fill the queue, independent on how long it is. Long queues just take longer to fill up, but under the assumption of an infinite stream of tasks produced, it definitely will fill up. Consequently there is no point in having a long queue. In fact a queue length equal to the number of tasks should suffice, because if it is kept filled by the producer, there is always one task ready for each thread.

Now consider a small number of long running tasks randomly spread between the short running tasks. According to the above reasoning, the producer will always hit a full queue. But instead of just waiting for the next slot to become free, CallerRunsPolicy uses the producer thread to run the long running task. The short queue will meanwhile drain and the consumer threads start idling.

How to get out of this:

### Increase Queue Size

Increasing the queue size will help. The length need to make sure that it cannot be drained for the time a long running task typically takes. Assume there are 4 CPUs, long running tasks take 600ms and slow running ones 20ms. If the producer is using one CPU to run the long running task, 3 CPUs can take care of tasks in the queue and they will finish up 600ms/20/3=10 tasks in the meantime. This is the average. Running times will not be exact and the distribution of long running tasks will not be uniform. To be sure CPUs do not idle, the queue length must be sufficiently oversized.

### Producer Blocking

But why this hassle and the guesswork on queue length without even having a guarantee. By just blocking the producer until a slot opens in the queue, consumers can run at full tilt all the time.

## Software Provided

The two classes provided are the SimpleThreadPool and a unit test that tries to demonstrate the difference between CallerRunsPolicy and just letting the producer block. The SimpleThreadPool is not a complete ExecutorService but just an Executor (hence "Simple"). To run the example, just execute the unit test in your favorite IDE (I use eclipse, others should work similar I assume).

Experiment with the parameters set at the top of the unit test to see how queue size, average run times and percentage of slow tasks influence the outcomes.


