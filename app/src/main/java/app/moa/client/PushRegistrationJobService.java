package app.moa.client;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Platform-owned network lifetime for registration and token renewal; job extras contain no secrets. */
public final class PushRegistrationJobService extends JobService {
    static final int JOB_ID = 7301;
    private JobParameters running;
    private long runGeneration;

    static void schedule(Context context, long delayMillis) {
        Context app = context.getApplicationContext();
        if (!PushRelayClient.registrationWorkNeeded(app)) return;
        JobScheduler scheduler = app.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        try {
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, new ComponentName(app, PushRegistrationJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
            long delay = Math.max(delayMillis, PushRelayClient.registrationDelayMillis(app));
            if (delay > 0) builder.setMinimumLatency(Math.min(86_400_000, delay));
            scheduler.schedule(builder.build());
        } catch (RuntimeException unavailable) { /* Persisted registration resumes when the app next opens. */ }
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getApplicationContext().getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!PushRelayClient.registrationWorkNeeded(this)) return false;
        running = params;
        long expected = ++runGeneration;
        PushRelayClient.runRegistrationJob(this, (retry, delay) -> {
            if (expected != runGeneration || running != params) return;
            running = null;
            jobFinished(params, retry && PushRelayClient.registrationWorkNeeded(this));
            if (!retry && delay > 0) schedule(this, delay);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (running == params) { running = null; runGeneration++; }
        return PushRelayClient.registrationWorkNeeded(this);
    }

    @Override public void onDestroy() { running = null; runGeneration++; super.onDestroy(); }
}
