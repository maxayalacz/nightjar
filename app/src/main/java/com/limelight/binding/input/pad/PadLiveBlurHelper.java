package com.limelight.binding.input.pad;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.AppThemePalette;
import com.limelight.ui.PortalBackgroundHelper;
import com.limelight.ui.PortalShaderView;
import com.limelight.utils.UiHelper;

import java.lang.ref.WeakReference;

/**
 * Live backdrop blur sampler for Nightjar's {@link ModernController} controls and {@link PadEditor}
 * floating window, sharing Moonlight OSC's exact live-blur settings ({@code oscLiveBlur},
 * {@code oscBlurAmount}, {@code oscBlurResolutionPercent}, {@code oscBlurFramerate}) over an active
 * stream and falling back to {@link PortalShaderView#getBlurredBitmap()} in the standalone Settings editor.
 */
public final class PadLiveBlurHelper {
    private static final long PREF_REFRESH_INTERVAL_MS = 1000L;

    private final ModernController controller;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final int[] surfaceLoc = new int[2];
    private final int[] elemLoc = new int[2];
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private final Bitmap[] buffers = new Bitmap[2];
    private int[] scratchPix = null;
    private int[] scratchTmp = null;
    private int writeIndex = 0;
    private volatile Bitmap readyBitmap = null;
    private volatile int surfaceScreenLeft = 0;
    private volatile int surfaceScreenTop = 0;
    private volatile int surfaceWidth = 0;
    private volatile int surfaceHeight = 0;

    private boolean running = false;
    private boolean inFlight = false;
    private volatile boolean oscBlurEnabled = false;
    private volatile boolean cardBlurEnabled = false;
    private int cachedResPct = PreferenceConfiguration.DEFAULT_OSC_BLUR_RESOLUTION_PERCENT;
    private int cachedBlurRadius = 3;
    private int cachedSatPct = -1;
    private long cachedIntervalMs = 16L;
    private long lastPrefRefreshMs = 0L;
    private WeakReference<PortalShaderView> portalRef;

    private final Drawable portalTick = new Drawable() {
        @Override public void draw(@NonNull Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void invalidateSelf() {
            controller.invalidateBlurTargets();
        }
    };

    private final Runnable captureTick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            refreshBlurPreferenceIfNeeded(false);
            if (!oscBlurEnabled && !(controller.isEditing() && cardBlurEnabled)) {
                readyBitmap = null;
                handler.postDelayed(this, 500L);
                return;
            }
            requestFrameCapture();
            handler.postDelayed(this, Math.max(8L, cachedIntervalMs));
        }
    };

    public PadLiveBlurHelper(ModernController controller, Context context) {
        this.controller = controller;
        this.context = context;
    }

    private static long resolveFpsIntervalMs(String fpsPref) {
        if ("max".equalsIgnoreCase(fpsPref)) return 8L;
        if ("120".equalsIgnoreCase(fpsPref)) return 8L;
        if ("90".equalsIgnoreCase(fpsPref)) return 11L;
        if ("30".equalsIgnoreCase(fpsPref)) return 33L;
        return 16L;
    }

    private void refreshBlurPreferenceIfNeeded(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastPrefRefreshMs < PREF_REFRESH_INTERVAL_MS) {
            return;
        }
        lastPrefRefreshMs = now;

        if (PortalBackgroundHelper.isRobolectric()) {
            oscBlurEnabled = false;
            cardBlurEnabled = false;
            return;
        }

        SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        boolean saverActive = PreferenceConfiguration.isUiBatterySaverEffective(context);
        boolean toggleOn = prefs.getBoolean(PreferenceConfiguration.OSC_LIVE_BLUR_PREF_STRING,
                PreferenceConfiguration.DEFAULT_OSC_LIVE_BLUR);
        oscBlurEnabled = toggleOn && !saverActive;
        cardBlurEnabled = AppThemePalette.isLiveBlurEffective(context);

        int blurPct = Math.max(0, Math.min(100,
                prefs.getInt(PreferenceConfiguration.OSC_BLUR_AMOUNT_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_OSC_BLUR_AMOUNT)));
        if (!oscBlurEnabled && cardBlurEnabled) {
            int uiBlur = Math.max(0, Math.min(200,
                    PreferenceConfiguration.readPreferences(context).blurAmountPercent));
            blurPct = Math.min(100, Math.round(uiBlur * 0.65f));
        }
        int resPct = Math.max(5, Math.min(50,
                prefs.getInt(PreferenceConfiguration.OSC_BLUR_RESOLUTION_PERCENT_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_OSC_BLUR_RESOLUTION_PERCENT)));
        String fpsPref = prefs.getString(PreferenceConfiguration.OSC_BLUR_FRAMERATE_PREF_STRING,
                PreferenceConfiguration.DEFAULT_OSC_BLUR_FRAMERATE);

        cachedResPct = resPct;
        cachedIntervalMs = resolveFpsIntervalMs(fpsPref);

        if (blurPct == 0) {
            cachedBlurRadius = 0;
        } else {
            double norm = blurPct / 100.0;
            cachedBlurRadius = Math.max(1, Math.min(10, (int) Math.round(0.6 + 6.4 * Math.pow(norm, 1.25))));
        }

        int satPct = PreferenceConfiguration.readPreferences(context).blurSaturationPercent;
        if (satPct != cachedSatPct) {
            cachedSatPct = satPct;
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(Math.max(0f, Math.min(3f, satPct / 100f)));
            bitmapPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        }
    }

    public void refreshBlurPreferenceNow() {
        refreshBlurPreferenceIfNeeded(true);
        if (!oscBlurEnabled && !(controller.isEditing() && cardBlurEnabled)) {
            readyBitmap = null;
            controller.invalidateBlurTargets();
            return;
        }
        if (running) {
            handler.removeCallbacks(captureTick);
            handler.post(captureTick);
        } else {
            start();
        }
    }

    public void start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || PortalBackgroundHelper.isRobolectric()) {
            return;
        }
        if (running) {
            refreshBlurPreferenceIfNeeded(true);
            return;
        }
        running = true;
        refreshBlurPreferenceIfNeeded(true);
        handler.removeCallbacks(captureTick);
        handler.post(captureTick);
    }

    public void stop() {
        running = false;
        inFlight = false;
        handler.removeCallbacks(captureTick);
        readyBitmap = null;
    }

    private void requestFrameCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || inFlight) {
            return;
        }
        if (!oscBlurEnabled && !(controller.isEditing() && cardBlurEnabled)) {
            return;
        }
        Activity activity = UiHelper.findActivity(context);
        if (activity == null || activity.isFinishing()) {
            return;
        }
        SurfaceView surfaceView = UiHelper.findStreamSurfaceView(activity);
        TextureView textureView = (surfaceView == null) ? UiHelper.findStreamTextureView(activity) : null;
        boolean hasSurface = surfaceView != null && surfaceView.getWidth() > 0 && surfaceView.getHeight() > 0
                && surfaceView.getHolder().getSurface().isValid();
        boolean hasTexture = !hasSurface && textureView != null && textureView.isAvailable()
                && textureView.getWidth() > 0 && textureView.getHeight() > 0;
        if (!hasSurface && !hasTexture) {
            PortalShaderView psv = PortalBackgroundHelper.getShaderView(activity);
            if (psv != null) {
                if (portalRef == null || portalRef.get() != psv) {
                    portalRef = new WeakReference<>(psv);
                    psv.registerFrostedDrawable(portalTick);
                }
                controller.invalidateBlurTargets();
            }
            return;
        }

        final View videoView = hasSurface ? surfaceView : textureView;
        final int sw = videoView.getWidth();
        final int sh = videoView.getHeight();
        videoView.getLocationOnScreen(surfaceLoc);
        final int sl = surfaceLoc[0];
        final int st = surfaceLoc[1];

        int minEdge = Math.max(1, Math.min(sw, sh));
        int shortTarget = Math.max(24, (Math.round(minEdge * (cachedResPct / 100f)) + 1) & ~1);
        float aspect = Math.max(1f, Math.max(sw, sh) / (float) minEdge);
        int longTarget = Math.max(shortTarget, (Math.round(shortTarget * aspect) + 1) & ~1);

        int bw = (sw >= sh) ? longTarget : shortTarget;
        int bh = (sw >= sh) ? shortTarget : longTarget;

        if (buffers[0] == null || buffers[0].getWidth() != bw || buffers[0].getHeight() != bh) {
            buffers[0] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            buffers[1] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            scratchPix = new int[bw * bh];
            scratchTmp = new int[bw * bh];
            readyBitmap = null;
        }

        final int idx = writeIndex;
        final Bitmap target = buffers[idx];
        if (hasTexture) {
            try {
                textureView.getBitmap(target);
                int blurRadius = cachedBlurRadius;
                if (blurRadius > 0) {
                    UiHelper.fastBoxBlur(target, blurRadius, scratchPix, scratchTmp);
                }
                surfaceScreenLeft = sl;
                surfaceScreenTop = st;
                surfaceWidth = sw;
                surfaceHeight = sh;
                readyBitmap = target;
                writeIndex = 1 - idx;
                controller.invalidateBlurTargets();
            } catch (Throwable ignored) {
            }
            return;
        }

        inFlight = true;
        try {
            PixelCopy.request(surfaceView, target, copyResult -> {
                inFlight = false;
                if (!running || copyResult != PixelCopy.SUCCESS) {
                    return;
                }
                int blurRadius = cachedBlurRadius;
                if (blurRadius > 0) {
                    UiHelper.fastBoxBlur(target, blurRadius, scratchPix, scratchTmp);
                }
                surfaceScreenLeft = sl;
                surfaceScreenTop = st;
                surfaceWidth = sw;
                surfaceHeight = sh;
                readyBitmap = target;
                writeIndex = 1 - idx;
                controller.invalidateBlurTargets();
            }, handler);
        } catch (IllegalArgumentException | IllegalStateException e) {
            inFlight = false;
        }
    }

    /** Draws live blur behind an OSC button/stick/pad when {@code oscLiveBlur} is enabled. */
    public boolean drawOscBlurForBounds(Canvas canvas, View view, Path clipPath, RectF bounds, float alphaFactor) {
        if (!oscBlurEnabled || alphaFactor <= 0.005f) {
            return false;
        }
        return drawResolvedBlur(canvas, view, clipPath, bounds, alphaFactor);
    }

    /** Draws One UI live frosted card blur behind the floating {@link PadEditor} card. */
    public boolean drawCardBlurForBounds(Canvas canvas, View view, Path clipPath, RectF bounds, float alphaFactor) {
        refreshBlurPreferenceIfNeeded(false);
        if (!cardBlurEnabled || alphaFactor <= 0.005f) {
            return false;
        }
        return drawResolvedBlur(canvas, view, clipPath, bounds, alphaFactor);
    }

    private boolean drawResolvedBlur(Canvas canvas, View view, Path clipPath, RectF bounds, float alphaFactor) {
        if (view == null || PortalBackgroundHelper.isRobolectric()) {
            return false;
        }
        Bitmap bmp = readyBitmap;
        int sw = surfaceWidth;
        int sh = surfaceHeight;
        int originL = surfaceScreenLeft;
        int originT = surfaceScreenTop;

        if (bmp == null || bmp.isRecycled() || sw <= 0 || sh <= 0) {
            View rootView = view.getRootView();
            View portalCandidate = rootView != null ? rootView.findViewWithTag("oneui_portal_starfield_bg") : null;
            if (portalCandidate instanceof PortalShaderView
                    && portalCandidate.getWidth() > 0 && portalCandidate.getHeight() > 0) {
                PortalShaderView psv = (PortalShaderView) portalCandidate;
                if (portalRef == null || portalRef.get() != psv) {
                    portalRef = new WeakReference<>(psv);
                    psv.registerFrostedDrawable(portalTick);
                }
                Bitmap portalBmp = psv.getBlurredBitmap();
                if (portalBmp != null && !portalBmp.isRecycled()) {
                    portalCandidate.getLocationOnScreen(surfaceLoc);
                    bmp = portalBmp;
                    originL = surfaceLoc[0];
                    originT = surfaceLoc[1];
                    sw = portalCandidate.getWidth();
                    sh = portalCandidate.getHeight();
                }
            }
        }
        if (bmp == null || bmp.isRecycled() || sw <= 0 || sh <= 0) {
            return false;
        }

        view.getLocationOnScreen(elemLoc);
        float screenLeft = elemLoc[0] + bounds.left;
        float screenTop = elemLoc[1] + bounds.top;
        float screenRight = elemLoc[0] + bounds.right;
        float screenBottom = elemLoc[1] + bounds.bottom;

        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        float scaleX = bw / (float) sw;
        float scaleY = bh / (float) sh;

        int srcL = Math.max(0, Math.min(bw - 1, Math.round((screenLeft - originL) * scaleX)));
        int srcT = Math.max(0, Math.min(bh - 1, Math.round((screenTop - originT) * scaleY)));
        int srcR = Math.max(srcL + 1, Math.min(bw, Math.round((screenRight - originL) * scaleX)));
        int srcB = Math.max(srcT + 1, Math.min(bh, Math.round((screenBottom - originT) * scaleY)));

        srcRect.set(srcL, srcT, srcR, srcB);
        dstRect.set(bounds);
        bitmapPaint.setAlpha(Math.max(0, Math.min(255, Math.round(alphaFactor * 255f))));

        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint);
        canvas.restore();
        return true;
    }
}
