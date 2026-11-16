package com.oshare.airdrop;

import android.content.Context;

import java.io.File;

/**
 * Where this app keeps its settings file.
 *
 * <p>{@code getFilesDir()} rather than external storage: the file is a handful of
 * {@code key=value} lines, it belongs to the app, and it must survive neither a reinstall nor
 * a person with a file manager — the settings screen is the way to change it.
 *
 * <p>One place, because the name is used by two activities and by the shell that dumps the
 * file during bring-up. A second spelling of it is how an app ends up with two settings files
 * and a page that shows the wrong one.
 */
final class SettingsFile {
    /** The file name inside the app's private directory. */
    private static final String NAME = "settings.conf";

    private SettingsFile() {}

    static File of(Context context) {
        return new File(context.getFilesDir(), NAME);
    }

    /**
     * The same file, addressed from the directory rather than from a {@link Context}.
     *
     * <p>For the two callers that only ever have the data directory: {@code ServiceId} is handed
     * one, and the settings table lives beside the file it is handed. A second spelling of the
     * name would be exactly the two-settings-files bug this class exists to prevent.
     */
    static File of(File directory) {
        return new File(directory, NAME);
    }
}
