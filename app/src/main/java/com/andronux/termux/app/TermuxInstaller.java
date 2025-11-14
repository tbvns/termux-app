package com.andronux.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.system.Os;
import android.util.Log;
import android.util.Pair;
import android.view.WindowManager;

import android.widget.Toast;
import com.andronux.termux.R;
import com.andronux.termux.app.andronux.GitUtils;
import com.andronux.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.andronux.termux.shared.data.IntentUtils;
import com.andronux.termux.shared.file.FileUtils;
import com.andronux.termux.shared.shell.command.ExecutionCommand;
import com.andronux.termux.shared.termux.crash.TermuxCrashUtils;
import com.andronux.termux.shared.termux.file.TermuxFileUtils;
import com.andronux.termux.shared.interact.MessageDialogUtils;
import com.andronux.termux.shared.logger.Logger;
import com.andronux.termux.shared.markdown.MarkdownUtils;
import com.andronux.termux.shared.errors.Error;
import com.andronux.termux.shared.android.PackageUtils;
import com.andronux.termux.shared.termux.TermuxConstants;
import com.andronux.termux.shared.termux.TermuxUtils;
import com.andronux.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.andronux.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static androidx.core.app.ActivityCompat.finishAffinity;
import static com.andronux.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.andronux.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.andronux.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.andronux.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");


                    Logger.logInfo(LOG_TAG, "Installing proot and arch linux.");
                    installProotAndArchLinux(activity, () -> {
                        Logger.logInfo(LOG_TAG, "Finished installing Proot Distro and Arch Linux");
                    });

                    Logger.logInfo(LOG_TAG, "Checking overlay permission before bootstrap installation...");
                    activity.runOnUiThread(() -> requestOverlayPermission(activity, () -> {
                        Logger.logInfo(LOG_TAG, "Overlay permission check passed.");
                    }));

                    // Generate termux.properties config file
                    Logger.logInfo(LOG_TAG, "Generating termux.properties configuration file.");
                    ensureTermuxPropertiesExists(activity);

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }


    /**
     * Ensures termux.properties file exists with default configuration
     * including allow-external-apps = true for RunCommandService
     */
    private static void ensureTermuxPropertiesExists(Activity activity) {
        try {
            // Use the correct home directory path
            File homeDir = new File("/data/data/com.andronux.termux/files/home");
            File termuxConfigDir = new File(homeDir, ".termux");
            File termuxPropertiesFile = new File(termuxConfigDir, "termux.properties");

            // Create directory if it doesn't exist
            if (!termuxConfigDir.exists()) {
                if (!termuxConfigDir.mkdirs()) {
                    Logger.logWarn(LOG_TAG, "Failed to create termux config directory: " + termuxConfigDir.getAbsolutePath());
                    return;
                }
            }

            // Create or update termux.properties
            String config = "# Termux configuration\n" +
                "# Allow external apps to execute commands via RunCommandService\n" +
                "allow-external-apps = true\n";

            try (FileOutputStream fos = new FileOutputStream(termuxPropertiesFile)) {
                fos.write(config.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            Logger.logInfo(LOG_TAG, "termux.properties created successfully at: " + termuxPropertiesFile.getAbsolutePath());

        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to create termux.properties: " + e.getMessage());
        }
    }

    static void installProotAndArchLinux(final Activity activity, final Runnable whenDone) {
        // Create ProgressDialog on UI thread
        activity.runOnUiThread(() -> {
            final ProgressDialog progress = ProgressDialog.show(
                activity,
                null,
                "Installing Arch Linux...",
                true,
                false
            );

            // Do heavy work on background thread
            new Thread() {
                @Override
                public void run() {
                    try {
                        Logger.logInfo(LOG_TAG, "Starting PRoot and Arch Linux installation.");

                        String homeDir = "/data/data/com.andronux.termux/files/home";
                        File proofDistroDir = new File(homeDir, "proot-distro");

                        // Clone proot-distro
                        GitUtils.clone(
                            "https://github.com/tbvns/proot-distro.git",
                            proofDistroDir.getAbsolutePath(),
                            new GitUtils.GitCloneCallback() {
                                @Override
                                public void onSuccess() {
                                    Logger.logInfo(LOG_TAG, "PRoot distro cloned successfully.");
                                    // Switch back to UI thread for next operation
                                    activity.runOnUiThread(() -> {
                                        executeInstallScript(activity, homeDir, whenDone, progress);
                                    });
                                }

                                @Override
                                public void onError(Exception e) {
                                    Logger.logError(LOG_TAG, "Failed to clone proot-distro: " + e.getMessage());
                                    activity.runOnUiThread(() -> {
                                        dismissProgress(progress);
                                        showBootstrapErrorDialog(activity, whenDone,
                                            "PRoot distro clone failed:\n" + e.getMessage());
                                    });
                                }
                            }
                        );

                    } catch (final Exception e) {
                        Logger.logError(LOG_TAG, "PRoot installation failed: " + e.getMessage());
                        activity.runOnUiThread(() -> {
                            dismissProgress(progress);
                            showBootstrapErrorDialog(activity, whenDone,
                                Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                        });
                    }
                }
            }.start();
        });
    }

    private static void executeInstallScript(
        Context context,
        String homeDir,
        Runnable whenDone,
        ProgressDialog progress) {

        // This is now called on UI thread
        try {
            String command = "/data/data/com.andronux.termux/files/usr/bin/bash";
            Uri execUri = new Uri.Builder()
                .scheme(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
                .path(command)
                .build();

            Intent execIntent = new Intent(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
                execUri
            );
            execIntent.setClass(context, TermuxService.class);
            execIntent.putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS,
                new String[]{
                    "-c",
                    "cd proot-distro && ./install.sh && proot-distro install archlinux"
                }
            );
            execIntent.putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR,
                homeDir
            );
            execIntent.putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER,
                ExecutionCommand.Runner.TERMINAL_SESSION.getName()
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(execIntent);
            } else {
                context.startService(execIntent);
            }

            Logger.logInfo(LOG_TAG, "Installation script started.");

            // Dismiss progress after short delay on main thread
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                dismissProgress(progress);
                if (whenDone != null) {
                    whenDone.run();
                }
            }, 1000);

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to execute install script: " + e.getMessage());
            dismissProgress(progress);
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                showBootstrapErrorDialog(activity, whenDone,
                    "Installation script failed:\n" + e.getMessage());
            }
        }
    }

    private static void dismissProgress(ProgressDialog progress) {
        try {
            progress.dismiss();
        } catch (RuntimeException e) {
            // Activity already dismissed - ignore
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.andronux.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.andronux.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

    // Add this constant for the request code
    private static final int REQUEST_OVERLAY_PERMISSION = 1234;

    /**
     * Checks if the app has permission to draw over other apps.
     * @param context The application context
     * @return true if permission is granted, false otherwise
     */
    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; // Permission not required on older Android versions
    }

    /**
     * Requests permission to draw over other apps.
     * Shows a dialog explaining why the permission is needed, then navigates to settings.
     * @param activity The current activity
     * @param onGranted Callback to run when permission is granted or not required
     */
    public static void requestOverlayPermission(final Activity activity, final Runnable onGranted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!Settings.canDrawOverlays(activity)) {
                new AlertDialog.Builder(activity)
                    .setTitle("Permission Required")
                    .setMessage("On Android 10+, Andronux requires permission to display over other apps. This is mandatory for creating terminal sessions in the background.\n\nWithout this permission, the app might not function properly.\n\nYou will be redirected to settings to grant this permission.")
                    .setPositiveButton("Grant Permission", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                    })
                    .setNegativeButton("Refuse", (dialog, which) -> {
                        dialog.dismiss();
                        Toast.makeText(activity,
                            "Overlay permission is required, the app might break without.",
                            Toast.LENGTH_LONG).show();
                    })
                    .setCancelable(false)
                    .show();
            } else {
                Logger.logInfo(LOG_TAG, "Overlay permission already granted");
                if (onGranted != null) {
                    onGranted.run();
                }
            }
        } else {
            // Permission not required on Android 9 and below
            Logger.logInfo(LOG_TAG, "Overlay permission not required on Android version " + Build.VERSION.SDK_INT);
            if (onGranted != null) {
                onGranted.run();
            }
        }
    }

    /**
     * Requests permission to draw over other apps without showing explanation dialog.
     * Directly navigates to settings.
     * @param activity The current activity
     */
    public static void requestOverlayPermissionDirect(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(activity)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            }
        }
    }

    /**
     * Call this method in your Activity's onActivityResult to handle the permission result
     * @param requestCode The request code
     * @param activity The current activity
     * @param callback Optional callback to execute after permission check
     */
    public static void handleOverlayPermissionResult(int requestCode, Activity activity, Runnable callback) {
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(activity)) {
                    Toast.makeText(activity, "Overlay permission granted successfully",
                        Toast.LENGTH_SHORT).show();
                    Logger.logInfo(LOG_TAG, "Overlay permission granted");
                    if (callback != null) {
                        callback.run();
                    }
                } else {
                    Toast.makeText(activity, "Overlay permission denied",
                        Toast.LENGTH_LONG).show();
                    Logger.logWarn(LOG_TAG, "Overlay permission denied by user");
                }
            }
        }
    }

}
