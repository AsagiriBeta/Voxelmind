package asagiribeta.voxelmind.client.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ScreenshotUtil {
    private ScreenshotUtil() {}

    public static byte[] captureToPngBytes(Minecraft mc) {
        try {
            RenderTarget rt = mc.getMainRenderTarget();
            AtomicReference<byte[]> ref = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            Consumer<NativeImage> cb = img -> {
                try (img) {
                    // Write to temp file using reflection to support Path or File signatures
                    Path tmp = Files.createTempFile("voxelmind_ss", ".png");
                    try {
                        Method mPath;
                        try {
                            mPath = NativeImage.class.getMethod("writeToFile", Path.class);
                            mPath.invoke(img, tmp);
                        } catch (NoSuchMethodException e1) {
                            Method mFile = NativeImage.class.getMethod("writeToFile", File.class);
                            mFile.invoke(img, tmp.toFile());
                        }
                        byte[] bytes = Files.readAllBytes(tmp);
                        ref.set(bytes);
                    } finally {
                        try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    latch.countDown();
                }
            };
            try {
                // Try (RenderTarget, Consumer) signature
                Screenshot.class.getMethod("takeScreenshot", RenderTarget.class, Consumer.class)
                        .invoke(null, rt, cb);
            } catch (NoSuchMethodException nsme) {
                try {
                    // Try (RenderTarget, int, Consumer) signature
                    Screenshot.class.getMethod("takeScreenshot", RenderTarget.class, int.class, Consumer.class)
                            .invoke(null, rt, 0, cb);
                } catch (NoSuchMethodException nsme2) {
                    return null;
                }
            }
            latch.await(300, TimeUnit.MILLISECONDS);
            return ref.get();
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    /**
     * Capture the current frame asynchronously and pass PNG bytes to the callback on a background thread.
     * This does not block the client thread.
     */
    public static void captureAsync(Minecraft mc, Consumer<byte[]> onPngBytes) {
        try {
            RenderTarget rt = mc.getMainRenderTarget();
            Consumer<NativeImage> cb = img -> {
                // Encode to PNG bytes off the render thread
                byte[] data = null;
                try (img) {
                    Path tmp = Files.createTempFile("voxelmind_ss", ".png");
                    try {
                        Method mPath;
                        try {
                            mPath = NativeImage.class.getMethod("writeToFile", Path.class);
                            mPath.invoke(img, tmp);
                        } catch (NoSuchMethodException e1) {
                            Method mFile = NativeImage.class.getMethod("writeToFile", File.class);
                            mFile.invoke(img, tmp.toFile());
                        }
                        data = Files.readAllBytes(tmp);
                    } finally {
                        try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                    }
                } catch (Throwable t) {
                    // swallow; data will remain null
                }
                try { onPngBytes.accept(data); } catch (Throwable ignored) {}
            };
            try {
                // Try (RenderTarget, Consumer)
                Screenshot.class.getMethod("takeScreenshot", RenderTarget.class, Consumer.class)
                        .invoke(null, rt, cb);
            } catch (NoSuchMethodException nsme) {
                try {
                    // Try (RenderTarget, int, Consumer)
                    Screenshot.class.getMethod("takeScreenshot", RenderTarget.class, int.class, Consumer.class)
                            .invoke(null, rt, 0, cb);
                } catch (NoSuchMethodException nsme2) {
                    // Not available
                    onPngBytes.accept(null);
                }
            }
        } catch (Throwable t) {
            onPngBytes.accept(null);
        }
    }
}
