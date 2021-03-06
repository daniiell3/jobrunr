package org.jobrunr.server;

import org.jobrunr.JobRunrException;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.filters.JobFilters;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.server.concurrent.ConcurrentJobModificationResolver;
import org.jobrunr.server.strategy.BasicWorkDistributionStrategy;
import org.jobrunr.server.strategy.WorkDistributionStrategy;
import org.jobrunr.storage.BackgroundJobServerStatus;
import org.jobrunr.storage.ConcurrentJobModificationException;
import org.jobrunr.storage.PageRequest;
import org.jobrunr.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.time.Duration.ofSeconds;
import static java.time.Instant.now;
import static org.jobrunr.JobRunrException.shouldNotHappenException;
import static org.jobrunr.jobs.states.StateName.PROCESSING;
import static org.jobrunr.jobs.states.StateName.SUCCEEDED;

public class JobZooKeeper implements Runnable {

    static final Logger LOGGER = LoggerFactory.getLogger(JobZooKeeper.class);

    private final BackgroundJobServer backgroundJobServer;
    private final StorageProvider storageProvider;
    private final JobFilters jobFilters;
    private final WorkDistributionStrategy workDistributionStrategy;
    private final ConcurrentJobModificationResolver concurrentJobModificationResolver;
    private final Map<Job, Thread> currentlyProcessedJobs;
    private final AtomicInteger exceptionCount;
    private final ReentrantLock reentrantLock;
    private final AtomicBoolean isMaster;

    public JobZooKeeper(BackgroundJobServer backgroundJobServer) {
        this.backgroundJobServer = backgroundJobServer;
        this.storageProvider = backgroundJobServer.getStorageProvider();
        this.jobFilters = backgroundJobServer.getJobFilters();
        this.workDistributionStrategy = createWorkDistributionStrategy();
        this.concurrentJobModificationResolver = createConcurrentJobModificationResolver();
        this.currentlyProcessedJobs = new ConcurrentHashMap<>();
        this.reentrantLock = new ReentrantLock();
        this.exceptionCount = new AtomicInteger();
        this.isMaster = new AtomicBoolean();
    }

    @Override
    public void run() {
        try {
            if (isNotInitialized()) return;

            if (canOnboardNewWork()) {
                if (isMaster()) {
                    runMasterTasks();
                }

                updateJobsThatAreBeingProcessed();
                checkForEnqueuedJobs();
            } else {
                updateJobsThatAreBeingProcessed();
            }
        } catch (Exception e) {
            if (exceptionCount.getAndIncrement() < 5) {
                LOGGER.warn(JobRunrException.SHOULD_NOT_HAPPEN_MESSAGE + " - Processing will continue.", e);
            } else {
                LOGGER.error("FATAL - JobRunr encountered too many processing exceptions. Shutting down.", shouldNotHappenException(e));
                backgroundJobServer.stop();
            }
        }
    }

    void runMasterTasks() {
        checkForRecurringJobs();
        checkForScheduledJobs();
        checkForOrphanedJobs();
        checkForSucceededJobsThanCanGoToDeletedState();
        checkForJobsThatCanBeDeleted();
    }

    public void stop() {
        this.isMaster.set(false);
    }

    public void setIsMaster(boolean isMaster) {
        this.isMaster.set(isMaster);
    }

    public boolean isMaster() {
        return this.isMaster.get();
    }

    private boolean isNotInitialized() {
        return isMaster == null;
    }

    boolean canOnboardNewWork() {
        return backgroundJobServerStatus().isRunning() && workDistributionStrategy.canOnboardNewWork();
    }

    void checkForRecurringJobs() {
        LOGGER.debug("Looking for recurring jobs... ");
        List<RecurringJob> enqueuedJobs = storageProvider.getRecurringJobs();
        processRecurringJobs(enqueuedJobs);
    }

    void checkForScheduledJobs() {
        LOGGER.debug("Looking for scheduled jobs... ");

        Supplier<List<Job>> scheduledJobsSupplier = () -> storageProvider.getScheduledJobs(now().plusSeconds(backgroundJobServerStatus().getPollIntervalInSeconds()), PageRequest.asc(0, 1000));
        processJobList(scheduledJobsSupplier, Job::enqueue);
    }

    void checkForOrphanedJobs() {
        LOGGER.debug("Looking for orphan jobs... ");
        final Instant updatedBefore = now().minus(ofSeconds(backgroundJobServer.getServerStatus().getPollIntervalInSeconds()).multipliedBy(4));
        Supplier<List<Job>> orphanedJobsSupplier = () -> storageProvider.getJobs(PROCESSING, updatedBefore, PageRequest.asc(0, 1000));
        processJobList(orphanedJobsSupplier, job -> job.failed("Orphaned job", new IllegalThreadStateException("Job was too long in PROCESSING state without being updated.")));
    }

    void checkForSucceededJobsThanCanGoToDeletedState() {
        LOGGER.debug("Looking for succeeded jobs that can go to the deleted state... ");
        AtomicInteger succeededJobsCounter = new AtomicInteger();

        final Instant updatedBefore = now().minus(36, ChronoUnit.HOURS);
        Supplier<List<Job>> succeededJobsSupplier = () -> storageProvider.getJobs(SUCCEEDED, updatedBefore, PageRequest.asc(0, 1000));
        processJobList(succeededJobsSupplier, job -> {
            succeededJobsCounter.incrementAndGet();
            job.delete();
        });

        if (succeededJobsCounter.get() > 0) {
            storageProvider.publishJobStatCounter(SUCCEEDED, succeededJobsCounter.get());
        }
    }

    void checkForJobsThatCanBeDeleted() {
        LOGGER.debug("Looking for deleted jobs that can be deleted permanently... ");
        storageProvider.deleteJobs(StateName.DELETED, now().minus(72, ChronoUnit.HOURS));
    }

    void updateJobsThatAreBeingProcessed() {
        LOGGER.debug("Updating currently processed jobs... ");
        processJobList(new ArrayList<>(currentlyProcessedJobs.keySet()), Job::updateProcessing);
    }

    void checkForEnqueuedJobs() {
        try {
            if (reentrantLock.tryLock()) {
                LOGGER.debug("Looking for enqueued jobs... ");
                final PageRequest workPageRequest = workDistributionStrategy.getWorkPageRequest();
                if (workPageRequest.getLimit() > 0) {
                    final List<Job> enqueuedJobs = storageProvider.getJobs(StateName.ENQUEUED, workPageRequest);
                    enqueuedJobs.forEach(backgroundJobServer::processJob);
                }
            }
        } finally {
            if (reentrantLock.isHeldByCurrentThread()) {
                reentrantLock.unlock();
            }
        }
    }

    void processRecurringJobs(List<RecurringJob> recurringJobs) {
        LOGGER.debug("Found {} recurring jobs", recurringJobs.size());
        recurringJobs.stream()
                .filter(this::isNotYetScheduled)
                .forEach(backgroundJobServer::scheduleJob);
    }

    boolean isNotYetScheduled(RecurringJob recurringJob) {
        if (storageProvider.exists(recurringJob.getJobDetails(), StateName.SCHEDULED)) return false;
        else if (storageProvider.exists(recurringJob.getJobDetails(), StateName.ENQUEUED)) return false;
        else if (storageProvider.exists(recurringJob.getJobDetails(), StateName.PROCESSING)) return false;
        else return true;
    }

    void processJobList(Supplier<List<Job>> jobListSupplier, Consumer<Job> jobConsumer) {
        List<Job> jobs = jobListSupplier.get();
        while (!jobs.isEmpty()) {
            processJobList(jobs, jobConsumer);
            jobs = jobListSupplier.get();
        }
    }

    void processJobList(List<Job> jobs, Consumer<Job> jobConsumer) {
        if (!jobs.isEmpty()) {
            try {
                jobs.forEach(jobConsumer);
                jobFilters.runOnStateElectionFilter(jobs);
                storageProvider.save(jobs);
                jobFilters.runOnStateAppliedFilters(jobs);
            } catch (ConcurrentJobModificationException e) {
                concurrentJobModificationResolver.resolve(e);
            }
        }
    }

    BackgroundJobServerStatus backgroundJobServerStatus() {
        return backgroundJobServer.getServerStatus();
    }

    public void startProcessing(Job job, Thread thread) {
        currentlyProcessedJobs.put(job, thread);
    }

    public void stopProcessing(Job job) {
        currentlyProcessedJobs.remove(job);
    }

    public int getWorkQueueSize() {
        return currentlyProcessedJobs.size();
    }

    public Thread getThreadProcessingJob(Job job) {
        return currentlyProcessedJobs.get(job);
    }

    public void notifyThreadIdle() {
        if (workDistributionStrategy.canOnboardNewWork()) {
            checkForEnqueuedJobs();
        }
    }

    BasicWorkDistributionStrategy createWorkDistributionStrategy() {
        return new BasicWorkDistributionStrategy(backgroundJobServer, this);
    }

    ConcurrentJobModificationResolver createConcurrentJobModificationResolver() {
        return new ConcurrentJobModificationResolver(storageProvider, this);
    }
}
