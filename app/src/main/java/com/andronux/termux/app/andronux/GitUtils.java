package com.andronux.termux.app.andronux;

import android.content.Context;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;

public class GitUtils {
    public static void clone(String url, String path,
                             GitCloneCallback callback) {
        // Run on background thread
        new Thread(() -> {
            try {
                File directory = new File(path);
                if (!directory.exists()) {
                    directory.mkdirs();
                }

                Git.cloneRepository()
                        .setURI(url)
                        .setDirectory(directory)
                        .call()
                        .close();

                callback.onSuccess();
            } catch (GitAPIException e) {
                callback.onError(e);
            }
        }).start();
    }

    public interface GitCloneCallback {
        void onSuccess();
        void onError(Exception e);
    }
}
