package com.andronux.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.util.Log;

import com.andronux.termux.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileShareService extends Service {
    private static final String TAG = "TermuxFileShare";
    public static final String ACTION_FILE_SHARE = 
        "com.andronux.termux.FILE_SHARE";
    public static final String EXTRA_FILE_PATH = 
        "com.andronux.termux.FILE_SHARE_PATH";
    public static final String EXTRA_PENDING_INTENT = 
        "com.andronux.termux.FILE_SHARE_RESULT_PENDING_INTENT";
    public static final String EXTRA_REQUESTER_PACKAGE = 
        "com.andronux.termux.FILE_SHARE_REQUESTER_PACKAGE";
    
    private static final int NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = 
        "termux_file_share_channel";
    private static final String DOCUMENTS_PROVIDER_AUTHORITY = 
        "com.andronux.termux.documents";
    
    private static final ExecutorService executor = 
        Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notif = createNotification("Preparing...");
        startForeground(NOTIFICATION_ID, notif);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "File Transfers",
                NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(
                NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("File Transfer")
            .setContentText(text)
            .setOngoing(true)
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, 
                             int startId) {
        Log.d(TAG, "onStartCommand");

        if (intent == null || 
            !ACTION_FILE_SHARE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        executor.execute(() -> processRequest(intent, startId));
        return START_REDELIVER_INTENT;
    }

    private void processRequest(Intent intent, int startId) {
        String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
        String requesterPackage = intent.getStringExtra(
            EXTRA_REQUESTER_PACKAGE);
        PendingIntent resultIntent = 
            intent.getParcelableExtra(EXTRA_PENDING_INTENT);

        if (filePath == null || resultIntent == null) {
            sendError(resultIntent, "Missing parameters");
            stopSelf(startId);
            return;
        }

        try {
            File sourceFile = new File(filePath);
            if (!sourceFile.exists() || !sourceFile.isFile()) {
                sendError(resultIntent, "File not found");
                stopSelf(startId);
                return;
            }

            updateNotification("Reading: " + sourceFile.getName());

            File cacheDir = getCacheDir();
            File tempFile = new File(cacheDir, 
                "shared_" + System.currentTimeMillis() + 
                "_" + sourceFile.getName());

            copyFile(sourceFile, tempFile);
            
            Log.d(TAG, "File cached at: " + 
                tempFile.getAbsolutePath());
            
            Uri fileUri = DocumentsContract.buildDocumentUri(
                DOCUMENTS_PROVIDER_AUTHORITY,
                tempFile.getAbsolutePath()
            );
            
            Log.d(TAG, "Created URI: " + fileUri.toString());
            
            if (requesterPackage != null) {
                grantUriPermission(requesterPackage, fileUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Log.d(TAG, "Granted URI permission to: " + 
                    requesterPackage);
            }
            
            sendSuccess(resultIntent, fileUri.toString(), 
                sourceFile.getName());

        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            sendError(resultIntent, e.getMessage());
        } finally {
            stopSelf(startId);
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = 
                (NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, 
                    createNotification(text));
            }
        } catch (Exception e) {
            Log.w(TAG, "Notification error", e);
        }
    }

    private void sendSuccess(PendingIntent pi, String fileUri, 
                            String fileName) {
        try {
            Bundle data = new Bundle();
            data.putString("file_uri", fileUri);
            data.putString("file_name", fileName);

            Intent result = new Intent();
            result.putExtra("file_share_result", data);
            pi.send(this, 0, result);
            Log.d(TAG, "Sent success with URI: " + fileUri);
        } catch (Exception e) {
            Log.e(TAG, "Send error", e);
        }
    }

    private void sendError(PendingIntent pi, String error) {
        try {
            Bundle data = new Bundle();
            data.putString("file_name", "error");
            data.putString("error", error);

            Intent result = new Intent();
            result.putExtra("file_share_result", data);
            pi.send(this, 0, result);
            Log.d(TAG, "Sent error: " + error);
        } catch (Exception e) {
            Log.e(TAG, "Error send failed", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
