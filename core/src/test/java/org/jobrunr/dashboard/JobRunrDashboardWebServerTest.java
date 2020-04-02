package org.jobrunr.dashboard;

import org.jobrunr.dashboard.server.http.client.TeenyHttpClient;
import org.jobrunr.jobs.Job;
import org.jobrunr.storage.BackgroundJobServerStatus;
import org.jobrunr.storage.SimpleStorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static java.util.UUID.randomUUID;
import static org.jobrunr.JobRunrAssertions.assertThat;
import static org.jobrunr.jobs.JobTestBuilder.aFailedJobWithRetries;
import static org.jobrunr.jobs.JobTestBuilder.anEnqueuedJob;

abstract class JobRunrDashboardWebServerTest {

    private SimpleStorageProvider storageProvider;

    private JobRunrDashboardWebServer dashboardWebServer;
    private TeenyHttpClient http;

    @BeforeEach
    public void setUpWebServer() {
        storageProvider = new SimpleStorageProvider();

        dashboardWebServer = new JobRunrDashboardWebServer(storageProvider, getJsonMapper());

        http = new TeenyHttpClient("http://localhost:8000");
    }

    public abstract JsonMapper getJsonMapper();

    @AfterEach
    public void stopWebServer() {
        dashboardWebServer.stop();
    }

    @Test
    public void testGetJobById_ForEnqueuedJob() {
        final Job job = anEnqueuedJob().build();
        final Job savedJob = storageProvider.save(job);

        HttpResponse<String> getResponse = http.get("/api/jobs/%s", savedJob.getId());
        assertThat(getResponse).hasStatusCode(200);
    }

    @Test
    public void testGetJobById_ForFailedJob() {
        final Job job = aFailedJobWithRetries().build();
        final Job savedJob = storageProvider.save(job);

        HttpResponse<String> getResponse = http.get("/api/jobs/%s", savedJob.getId());
        assertThat(getResponse)
                .hasStatusCode(200)
                .hasSameJsonBodyAsResource("/dashboard/api/getJobById_ForFailedJob.json");
    }

    @Test
    public void testDeleteJob() {
        final Job job = aFailedJobWithRetries().build();
        final Job savedJob = storageProvider.save(job);

        HttpResponse<String> deleteResponse = http.delete("/api/jobs/%s", savedJob.getId());
        assertThat(deleteResponse).hasStatusCode(204);

        HttpResponse<String> getResponse = http.get("/api/jobs/%s", savedJob.getId());
        assertThat(getResponse).hasStatusCode(404);
    }

    @Test
    public void testGetJobById_JobNotFoundReturns404() {
        HttpResponse<String> getResponse = http.get("/api/jobs/%s", randomUUID());
        assertThat(getResponse).hasStatusCode(404);
    }

    @Test
    public void testFindJobsByState() {
        storageProvider.save(anEnqueuedJob().build());

        HttpResponse<String> getResponse = http.get("/api/jobs/default/ENQUEUED");
        assertThat(getResponse)
                .hasStatusCode(200)
                .hasSameJsonBodyAsResource("/dashboard/api/findJobsByState.json");
    }

    @Test
    public void testGetBackgroundJobServers() {
        storageProvider.announceBackgroundJobServer(new BackgroundJobServerStatus(15, 10));

        HttpResponse<String> getResponse = http.get("/api/servers");
        assertThat(getResponse)
                .hasStatusCode(200)
                .hasSameJsonBodyAsResource("/dashboard/api/getBackgroundJobServers.json");
    }
}