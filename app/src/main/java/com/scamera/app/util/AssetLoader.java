package com.scamera.app.util;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Text assets (the shaders) of the app's APK. */
public class AssetLoader {
    private final AssetManager assets;

    public AssetLoader(Context context) {
        assets = context.getAssets();
    }

    /** The asset as a stream (buffered access: the weights and textures read whole). */
    public java.io.InputStream getInputStream(String name) throws IOException {
        return assets.open(name, AssetManager.ACCESS_BUFFER);
    }

    /**
     * The asset's text, each of its lines ended by a single '\n' (CR LF read as one end of line): the shader sources, whose
     * exact text keys the viewfinder's kept program binaries. A wrong path fails here, naming the asset.
     */
    public String getString(String name) {
        final StringBuilder text = new StringBuilder(4096);
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(assets.open(name, AssetManager.ACCESS_BUFFER),
                StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) text.append(line).append('\n');
        } catch (java.io.FileNotFoundException missing) {
            throw new IllegalArgumentException("Asset not found: " + name, missing);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Asset not readable: " + name, unreadable);
        }
        return text.toString();
    }
}
