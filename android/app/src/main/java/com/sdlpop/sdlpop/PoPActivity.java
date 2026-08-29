/*
 * SDLPoP - Android entry point.
 *
 * Responsibilities:
 *   1. Extract the bundled game data from the APK's assets to internal storage
 *      on first launch (APK assets are not reachable through fopen(), which is
 *      what all of SDLPoP's resource loading is built on).
 *   2. Tell the native layer where that data lives, before SDL_main() runs.
 *   3. Overlay the on-screen controls.
 *   4. Seed a default SDLPoP.ini (start_fullscreen = true) so the game's own
 *      Settings > Visuals > "start fullscreen" toggle is ON out of the box.
 *      The game itself then drives SDL into fullscreen, which triggers
 *      SDLActivity's immersive handling — no window-style code needed here.
 */

package com.sdlpop.sdlpop;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class PoPActivity extends SDLActivity {

    private static final String TAG = "SDLPoP";

    /** Bump whenever the bundled game data changes so it gets re-extracted. */
    private static final int DATA_VERSION = 1;

    private static final String DATA_DIR = "data";
    private static final String MARKER_FILE = "popdata.version";
    private static final String INI_FILE = "SDLPoP.ini";

    /** Implemented in app/src/main/cpp/pop_android.c */
    public static native void nativeSetup(String dataDir);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (mBrokenLibraries) {
            return;
        }

        try {
            extractGameData();
            seedDefaultIni();
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract game data", e);
        }

        // The SDL main thread is only started once the surface is ready and the
        // activity is resumed, so this always runs before SDL_main().
        nativeSetup(getFilesDir().getAbsolutePath());

        mLayout.addView(new VirtualPad(this));
    }

    /* ------------------------------------------------------------------ */
    /* Game data extraction                                                */
    /* ------------------------------------------------------------------ */

    private void extractGameData() throws IOException {
        File target = new File(getFilesDir(), DATA_DIR);
        File marker = new File(getFilesDir(), MARKER_FILE);
        String wanted = Integer.toString(DATA_VERSION);

        if (target.isDirectory() && readMarker(marker).equals(wanted)) {
            return;
        }

        deleteRecursive(target);
        copyAssetPath(DATA_DIR, target);
        writeMarker(marker, wanted);
    }

    /** Copies an asset path recursively; the path may be a file or a directory. */
    private void copyAssetPath(String assetPath, File target) throws IOException {
        AssetManager am = getAssets();
        String[] entries = am.list(assetPath);

        if (entries == null || entries.length == 0) {
            copyAssetFile(assetPath, target);
            return;
        }

        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Cannot create directory " + target);
        }
        for (String entry : entries) {
            copyAssetPath(assetPath + "/" + entry, new File(target, entry));
        }
    }

    /**
     * Copies the bundled default SDLPoP.ini to the app's files dir, but only
     * if it is not already there.
     *
     * The file must NOT be overwritten on later launches: SDLPoP lets an
     * in-game-menu-generated SDLPoP.cfg override the .ini only while the
     * .cfg is newer (menu.c load_ingame_settings compares mtimes). Refreshing
     * the .ini every start would silently reset whatever the user changed in
     * the in-game menu.
     */
    private void seedDefaultIni() throws IOException {
        File target = new File(getFilesDir(), INI_FILE);
        if (!target.exists()) {
            copyAssetFile(INI_FILE, target);
        }
    }

    private void copyAssetFile(String assetPath, File target) throws IOException {
        InputStream in = getAssets().open(assetPath);
        try {
            OutputStream out = new BufferedOutputStream(new FileOutputStream(target));
            try {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private String readMarker(File marker) {
        byte[] buffer = new byte[32];
        try {
            InputStream in = new java.io.FileInputStream(marker);
            try {
                int read = in.read(buffer);
                return read > 0 ? new String(buffer, 0, read, "UTF-8").trim() : "";
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private void writeMarker(File marker, String value) throws IOException {
        OutputStream out = new FileOutputStream(marker);
        try {
            out.write(value.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
