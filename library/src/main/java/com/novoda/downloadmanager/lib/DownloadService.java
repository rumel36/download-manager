/*
 * Copyright (C) 2008 The Android Open Source Project
 *
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
 */

package com.novoda.downloadmanager.lib;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.novoda.notils.logger.simple.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

/**
 * Performs background downloads as requested by applications that use
 * {DownloadManager}. Multiple start commands can be issued at this
 * service, and it will continue running until no downloads are being actively
 * processed. It may schedule alarms to resume downloads in future.
 * <p/>
 * Any database updates important enough to initiate tasks should always be
 * delivered through {Context#startService(Intent)}.
 */
public class DownloadService extends Service {
    // TODO: migrate WakeLock from individual DownloadThreads out into
    // DownloadReceiver to protect our entire workflow.

    private static final boolean DEBUG_LIFECYCLE = false;
    private final ContentLengthFetcher contentLengthFetcher = new ContentLengthFetcher();

    //    @VisibleForTesting
    SystemFacade systemFacade;

    private AlarmManager alarmManager;
    private StorageManager storageManager;

    /**
     * Observer to get notified when the content observer's data changes
     */
    private DownloadManagerContentObserver downloadManagerContentObserver;

    /**
     * Class to handle Notification Manager updates
     */
    private DownloadNotifier downloadNotifier;

    /**
     * The Service's view of the list of downloads, mapping download IDs to the corresponding info
     * object. This is kept independently from the content provider, and the Service only initiates
     * downloads based on this data, so that it can deal with situation where the data in the
     * content provider changes or disappears.
     */
//    @GuardedBy("downloads")
    private final Map<Long, DownloadInfo> downloads = new HashMap<>();

    private ExecutorService executor;

    private DownloadScanner downloadScanner;

    private HandlerThread updateThread;
    private Handler updateHandler;

    private volatile int lastStartId;
    private DownloadClientReadyChecker downloadClientReadyChecker;
    private ContentResolver resolver;

    /**
     * Receives notifications when the data in the content provider changes
     */
    private class DownloadManagerContentObserver extends ContentObserver {
        public DownloadManagerContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(final boolean selfChange) {
            enqueueUpdate();
        }
    }

    /**
     * Returns an IBinder instance when someone wants to connect to this
     * service. Binding to this service is not allowed.
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        throw new UnsupportedOperationException("Cannot bind to Download Manager Service");
    }

    /**
     * Initializes the service when it is first created
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.v("Service onCreate");

        if (systemFacade == null) {
            systemFacade = new RealSystemFacade(this);
        }

        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        storageManager = StorageManager.newInstance(this);

        updateThread = new HandlerThread("DownloadManager-UpdateThread");
        updateThread.start();
        updateHandler = new Handler(updateThread.getLooper(), mUpdateCallback);

        downloadScanner = new DownloadScanner(this);

        downloadClientReadyChecker = getDownloadClientReadyChecker();

        downloadNotifier = new DownloadNotifier(this, getNotificationImageRetriever(), getResources());
        downloadNotifier.cancelAll();

        downloadManagerContentObserver = new DownloadManagerContentObserver();
        getContentResolver().registerContentObserver(
                Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
                true, downloadManagerContentObserver);

        ConcurrentDownloadsLimitProvider concurrentDownloadsLimitProvider = ConcurrentDownloadsLimitProvider.newInstance(this);
        DownloadExecutorFactory factory = new DownloadExecutorFactory(concurrentDownloadsLimitProvider);
        executor = factory.createExecutor();
        resolver = getContentResolver();
    }

    private DownloadClientReadyChecker getDownloadClientReadyChecker() {
        if (getApplication() instanceof DownloadClientReadyChecker) {
            return (DownloadClientReadyChecker) getApplication();
        }
        return DownloadClientReadyChecker.READY;
    }

    private NotificationImageRetriever getNotificationImageRetriever() {
        if (getApplication() instanceof NotificationImageRetrieverFactory) {
            return ((NotificationImageRetrieverFactory) getApplication()).createNotificationImageRetriever();
        }
        return new OkHttpNotificationImageRetriever();
    }

    @Override
    public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
        int returnValue = super.onStartCommand(intent, flags, startId);
        Log.v("Service onStart");
        lastStartId = startId;
        enqueueUpdate();
        return returnValue;
    }

    @Override
    public void onDestroy() {
        shutDown();
        Log.v("Service onDestroy");
        super.onDestroy();
    }

    private void shutDown() {
        Log.d("Shutting down service");
        getContentResolver().unregisterContentObserver(downloadManagerContentObserver);
        downloadScanner.shutdown();
        updateThread.quit();
    }

    /**
     * Enqueue an {#updateLocked()} pass to occur in future.
     */
    private void enqueueUpdate() {
        updateHandler.removeMessages(MSG_UPDATE);
        updateHandler.obtainMessage(MSG_UPDATE, lastStartId, -1).sendToTarget();
    }

    /**
     * Enqueue an {#updateLocked()} pass to occur after delay, usually to
     * catch any finished operations that didn't trigger an update pass.
     */
    private void enqueueFinalUpdate() {
        updateHandler.removeMessages(MSG_FINAL_UPDATE);
        updateHandler.sendMessageDelayed(
                updateHandler.obtainMessage(MSG_FINAL_UPDATE, lastStartId, -1),
                5 * MINUTE_IN_MILLIS);
    }

    private static final int MSG_UPDATE = 1;
    private static final int MSG_FINAL_UPDATE = 2;

    private final Handler.Callback mUpdateCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            final int startId = msg.arg1;
            if (DEBUG_LIFECYCLE) {
                Log.v("Updating for startId " + startId);
            }

            // Since database is current source of truth, our "active" status
            // depends on database state. We always get one final update pass
            // once the real actions have finished and persisted their state.

            // TODO: switch to asking real tasks to derive active state
            // TODO: handle media scanner timeouts

            final boolean isActive;
            synchronized (downloads) {
                isActive = updateLocked();
            }

            if (msg.what == MSG_FINAL_UPDATE) {
                // Dump thread stacks belonging to pool
                for (Map.Entry<Thread, StackTraceElement[]> entry :
                        Thread.getAllStackTraces().entrySet()) {
                    if (entry.getKey().getName().startsWith("pool")) {
                        Log.d(entry.getKey() + ": " + Arrays.toString(entry.getValue()));
                    }
                }

                // Dump speed and update details
                downloadNotifier.dumpSpeeds();

                Log.wtf("Final update pass triggered, isActive=" + isActive, new IllegalStateException("someone didn't update correctly"));
            }

            if (isActive) {
                // Still doing useful work, keep service alive. These active
                // tasks will trigger another update pass when they're finished.

                // Enqueue delayed update pass to catch finished operations that
                // didn't trigger an update pass; these are bugs.
                enqueueFinalUpdate();

            } else {
                // No active tasks, and any pending update messages can be
                // ignored, since any updates important enough to initiate tasks
                // will always be delivered with a new startId.

                if (stopSelfResult(startId)) {
                    if (DEBUG_LIFECYCLE) {
                        Log.v("Nothing left; stopped");
                    }
                    shutDown();
                }
            }

            return true;
        }
    };

    /**
     * Update {#downloads} to match {DownloadProvider} state.
     * Depending on current download state it may enqueue {DownloadThread}
     * instances, request {DownloadScanner} scans, update user-visible
     * notifications, and/or schedule future actions with {AlarmManager}.
     * <p/>
     * Should only be called from {#updateThread} as after being
     * requested through {#enqueueUpdate()}.
     *
     * @return If there are active tasks being processed, as of the database
     * snapshot taken in this update.
     */
    private boolean updateLocked() {

        boolean isActive = false;
        Set<Long> staleDownloadIds = new HashSet<>(downloads.keySet());
        long nextRetryTimeMillis = Long.MAX_VALUE;
        long now = systemFacade.currentTimeMillis();

        Cursor downloadsCursor = resolver.query(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, null, null, null, null);
        try {
            DownloadInfo.Reader reader = new DownloadInfo.Reader(resolver, downloadsCursor);
            int idColumn = downloadsCursor.getColumnIndexOrThrow(Downloads.Impl._ID);
            while (downloadsCursor.moveToNext()) {
                long id = downloadsCursor.getLong(idColumn);
                staleDownloadIds.remove(id);

                DownloadInfo info = downloads.get(id);
                if (info == null) {
                    info = createNewDownloadInfo(reader);
                    downloads.put(info.mId, info);
                } else {
                    updateDownloadFromDatabase(reader, info);
                }

                if (info.mDeleted) {
                    deleteFileAndDatabaseRow(info);
                } else if (Downloads.Impl.isStatusCancelled(info.mStatus) || Downloads.Impl.isStatusError(info.mStatus)) {
                    deleteFileAndMediaReference(info);
                } else {
                    updateTotalBytesFor(info);
                    isActive = kickOffDownloadTaskIfReady(isActive, info);
                    isActive = kickOffMediaScanIfCompleted(isActive, info);
                }

                // Keep track of nearest next action
                nextRetryTimeMillis = Math.min(info.nextActionMillis(now), nextRetryTimeMillis);
            }
        } finally {
            downloadsCursor.close();
        }

        cleanUpStaleDownloadsThatDisappeared(staleDownloadIds, downloads);

        List<DownloadBatch> batches = fetchBatches(downloads.values());
        updateUserVisibleNotification(batches);

        // Set alarm when next action is in future. It's okay if the service
        // continues to run in meantime, since it will kick off an update pass.
        if (nextRetryTimeMillis > 0 && nextRetryTimeMillis < Long.MAX_VALUE) {
            Log.v("scheduling start in " + nextRetryTimeMillis + "ms");

            Intent intent = new Intent(Constants.ACTION_RETRY);
            intent.setClass(this, DownloadReceiver.class);
            alarmManager.set(AlarmManager.RTC_WAKEUP, now + nextRetryTimeMillis, PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_ONE_SHOT));
        }

        return isActive;
    }

    private List<DownloadBatch> fetchBatches(Collection<DownloadInfo> downloads) {
        List<DownloadBatch> batches = new ArrayList<>();
        Cursor batchesCursor = resolver.query(Downloads.Impl.BATCH_CONTENT_URI, null, null, null, null);
        batches.clear();
        try {
            int idColumn = batchesCursor.getColumnIndexOrThrow(Downloads.Impl.Batches._ID);
            int visibilityColumn = batchesCursor.getColumnIndexOrThrow(Downloads.Impl.Batches.COLUMN_VISIBILITY);
            while (batchesCursor.moveToNext()) {
                long id = batchesCursor.getLong(idColumn);

                String title = batchesCursor.getString(batchesCursor.getColumnIndexOrThrow(Downloads.Impl.Batches.COLUMN_TITLE));
                String description = batchesCursor.getString(batchesCursor.getColumnIndexOrThrow(Downloads.Impl.Batches.COLUMN_DESCRIPTION));
                String bigPictureUrl = batchesCursor.getString(batchesCursor.getColumnIndexOrThrow(Downloads.Impl.Batches.COLUMN_BIG_PICTURE));
                int status = batchesCursor.getInt(batchesCursor.getColumnIndexOrThrow(Downloads.Impl.Batches.COLUMN_STATUS));
                @NotificationVisibility.Value int visibility = batchesCursor.getInt(visibilityColumn);

                BatchInfo batchInfo = new BatchInfo(title, description, bigPictureUrl, visibility);

                List<DownloadInfo> batchDownloads = new ArrayList<>();
                for (DownloadInfo downloadInfo : downloads) {
                    if (downloadInfo.batchId == id) {
                        batchDownloads.add(downloadInfo);
                    }
                }
                batches.add(new DownloadBatch(id, batchInfo, batchDownloads, status));
            }
        } finally {
            batchesCursor.close();
        }
        return batches;
    }

    private void updateTotalBytesFor(DownloadInfo info) {
        if (info.mTotalBytes == -1) {
            ContentValues values = new ContentValues();
            info.mTotalBytes = contentLengthFetcher.fetchContentLengthFor(info);
            values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, info.mTotalBytes);
            resolver.update(info.getAllDownloadsUri(), values, null, null);
        }
    }

    private void deleteFileAndDatabaseRow(DownloadInfo info) {
        deleteFileAndMediaReference(info);
        resolver.delete(info.getAllDownloadsUri(), null, null);
    }

    private void deleteFileAndMediaReference(DownloadInfo info) {
        if (!TextUtils.isEmpty(info.mMediaProviderUri)) {
            resolver.delete(Uri.parse(info.mMediaProviderUri), null, null);
        }

        if (!TextUtils.isEmpty(info.mFileName)) {
            deleteFileIfExists(info.mFileName);
            ContentValues blankData = new ContentValues();
            blankData.put(Downloads.Impl._DATA, (String) null);
            resolver.update(info.getAllDownloadsUri(), blankData, null, null);
            info.mFileName = null;
        }
    }

    private boolean kickOffDownloadTaskIfReady(boolean isActive, DownloadInfo info) {
        CollatedDownloadInfo collatedDownloadInfo = CollatedDownloadInfo.collateInfo(downloads, info);
        boolean isReadyToDownload = info.isReadyToDownload(collatedDownloadInfo);
        boolean downloadIsActive = info.isActive();

        if (isReadyToDownload || downloadIsActive) {
            isActive |= info.startDownloadIfNotActive(executor);
        }
        return isActive;
    }

    private boolean kickOffMediaScanIfCompleted(boolean isActive, DownloadInfo info) {
        final boolean activeScan = info.startScanIfReady(downloadScanner);
        isActive |= activeScan;
        return isActive;
    }

    private void cleanUpStaleDownloadsThatDisappeared(Set<Long> staleIds, Map<Long, DownloadInfo> downloads) {
        for (Long id : staleIds) {
            deleteDownloadLocked(id, downloads);
        }
    }

    private void updateUserVisibleNotification(List<DownloadBatch> batches) {
        downloadNotifier.updateWith(batches);
    }

    /**
     * Keeps a local copy of the info about a download, and initiates the
     * download if appropriate.
     */
    private DownloadInfo createNewDownloadInfo(DownloadInfo.Reader reader) {
        DownloadInfo info = reader.newDownloadInfo(this, systemFacade, storageManager, downloadNotifier, downloadClientReadyChecker);
        Log.v("processing inserted download " + info.mId);
        return info;
    }

    /**
     * Updates the local copy of the info about a download.
     */
    private void updateDownloadFromDatabase(DownloadInfo.Reader reader, DownloadInfo info) {
        reader.updateFromDatabase(info);
        Log.v("processing updated download " + info.mId + ", status: " + info.mStatus);
    }

    /**
     * Removes the local copy of the info about a download.
     */
    private void deleteDownloadLocked(long id, Map<Long, DownloadInfo> downloads) {
        DownloadInfo info = downloads.get(id);
        if (info.mStatus == Downloads.Impl.STATUS_RUNNING) {
            info.mStatus = Downloads.Impl.STATUS_CANCELED;
        }
        if (info.mDestination != Downloads.Impl.DESTINATION_EXTERNAL && info.mFileName != null) {
            Log.d("deleteDownloadLocked() deleting " + info.mFileName);
            deleteFileIfExists(info.mFileName);
        }
        downloads.remove(info.mId);
    }

    private void deleteFileIfExists(String path) {
        if (!TextUtils.isEmpty(path)) {
            Log.d("deleteFileIfExists() deleting " + path);
            final File file = new File(path);
            if (file.exists() && !file.delete()) {
                Log.w("file: '" + path + "' couldn't be deleted");
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, @NonNull PrintWriter writer, String[] args) {
        Log.e("I want to dump but nothing to dump into");
    }
}
