package net.microfalx.threadpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static net.microfalx.threadpool.ThreadPoolUtils.requireNonNull;

class Dispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(Dispatcher.class);

    private static final long STALE_THREAD_INTERVAL = 5_000_000_000L;
    private static final AtomicInteger SCHEDULER_THREAD_COUNTER = new AtomicInteger();

    private static final Dispatcher instance = new Dispatcher();
    private volatile ThreadImpl thread;

    private final Collection<ThreadPoolImpl> threadPools = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Object awaitTasks = new Object();

    static Dispatcher getInstance() {
        return instance;
    }

    Dispatcher() {
        initialize();
    }

    void shutdown() {
        for (ThreadPoolImpl threadPool : threadPools) {
            if (!threadPool.isShutdown()) threadPool.shutdown();
        }
        threadPools.clear();
    }

    void register(ThreadPoolImpl threadPool) {
        requireNonNull(threadPool);

        threadPools.add(threadPool);
    }

    void unregister(ThreadPoolImpl threadPool) {
        requireNonNull(threadPool);

        threadPools.remove(threadPool);
    }

    Collection<ThreadPool> getThreadPools() {
        return Collections.unmodifiableCollection(threadPools);
    }

    void wakeUp(ThreadPoolImpl threadPool) {
        synchronized (awaitTasks) {
            awaitTasks.notifyAll();
        }
        if (System.nanoTime() - thread.lastIteration > STALE_THREAD_INTERVAL) {
            createThread();
        }
    }

    private void initialize() {
        createThread();
    }

    private void createThread() {
        lock.lock();
        try {
            if (thread != null) thread.stopped = true;
            thread = new ThreadImpl();
            thread.start();
        } finally {
            lock.unlock();
        }
    }

    class ThreadImpl extends Thread {

        private volatile long lastIteration;
        private volatile boolean stopped;

        ThreadImpl() {
            setName("MicroFalx Dispatcher " + SCHEDULER_THREAD_COUNTER.incrementAndGet());
            setDaemon(true);
        }

        private boolean handleTasks() {
            boolean hasTasks = false;
            for (ThreadPoolImpl threadPool : threadPools) {
                try {
                    hasTasks |= threadPool.handleTasks();
                } catch (Exception e) {
                    LOGGER.error("Failed to handle next task for " + threadPool, e);
                }
            }
            return hasTasks;
        }

        @SuppressWarnings("ResultOfMethodCallIgnored")
        private void loop() throws InterruptedException {
            while (!stopped) {
                lastIteration = System.nanoTime();
                boolean hasTasks = handleTasks();
                if (!hasTasks) {
                    synchronized (awaitTasks) {
                        awaitTasks.wait(10);
                    }
                }

            }
        }

        @Override
        public void run() {
            try {
                loop();
            } catch (InterruptedException e) {
                // do nothing, another thread will wake up
            } catch (Throwable e) {
                LOGGER.warn("Dispatcher thread stopped, create new thread", e);
            }
        }
    }
}
