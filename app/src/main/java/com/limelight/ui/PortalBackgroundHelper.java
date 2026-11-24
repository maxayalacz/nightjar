package com.limelight.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * Attaches and lifecycle-manages {@link PortalShaderView} across {@link NightjarActivity} screens.
 *
 * <ul>
 *   <li>When a screen's root is a {@link GlassHost}, injects {@link PortalShaderView} at index 0 of
 *       the host's content child (child 0) so every {@link GlassSurface} blurs live over the starfield.</li>
 *   <li>Hides static {@link NightSkyView} layers and respects the Theme Studio toggle for
 *       {@link BackdropView} game cover art blending.</li>
 *   <li>Plays the cold-boot spiral animation once on initial app launch.</li>
 *   <li>Skips GL surface attachment under Robolectric unit tests.</li>
 * </ul>
 */
public final class PortalBackgroundHelper {
    private static final String PORTAL_BG_TAG = "oneui_portal_starfield_bg";
    private static boolean sColdBootAnimPlayed = false;

    private PortalBackgroundHelper() { }

    private static boolean isRobolectric() {
        return "robolectric".equalsIgnoreCase(Build.FINGERPRINT);
    }

    /** Ensures a PortalShaderView is attached behind the activity's content and returns it. */
    @Nullable
    public static PortalShaderView attach(Activity activity) {
        if (activity == null || isRobolectric()) return null;
        if (activity instanceof ThemeStudioActivity) return null;

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return null;

        PortalShaderView existing = findShaderView(content);
        if (existing != null) {
            applyBackdropAndTheme(activity, content, existing);
            return existing;
        }

        View root = content.getChildAt(0);
        ViewGroup targetContainer = null;
        if (root instanceof GlassHost && ((GlassHost) root).getChildCount() > 0) {
            View firstChild = ((GlassHost) root).getChildAt(0);
            if (firstChild instanceof ViewGroup) {
                targetContainer = (ViewGroup) firstChild;
            }
        }
        if (targetContainer == null) {
            targetContainer = content;
        }

        PortalShaderView shaderView = new PortalShaderView(activity);
        shaderView.setTag(PORTAL_BG_TAG);
        shaderView.configureStarfieldBackgroundMode();
        shaderView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        ViewGroup.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        targetContainer.addView(shaderView, 0, lp);

        applyBackdropAndTheme(activity, content, shaderView);

        if (!sColdBootAnimPlayed) {
            sColdBootAnimPlayed = true;
            playColdBootSpiral(activity, shaderView, root);
        }

        return shaderView;
    }

    /** Called from NightjarActivity.onResume(). */
    public static void onResume(Activity activity) {
        if (activity == null || isRobolectric()) return;
        PortalShaderView psv = attach(activity);
        if (psv != null) {
            psv.refreshFromPrefs();
            psv.startRendering();
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content != null) {
            GlassHost host = findGlassHost(content);
            if (host != null) {
                host.refreshGlass();
            }
        }
    }

    /** Called from NightjarActivity.onPause(). */
    public static void onPause(Activity activity) {
        if (activity == null || isRobolectric()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        PortalShaderView psv = findShaderView(content);
        if (psv != null) {
            PortalShaderView.captureHandoffFrame(psv);
            psv.stopRenderingAsync();
        }
    }

    /** Captures a handoff frame from the current activity's PortalShaderView before launching Game. */
    public static void captureHandoff(Activity activity) {
        if (activity == null || isRobolectric()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        PortalShaderView psv = findShaderView(content);
        if (psv != null) {
            PortalShaderView.captureHandoffFrame(psv);
        }
    }

    private static void applyBackdropAndTheme(Activity activity, ViewGroup content, PortalShaderView shaderView) {
        AppThemePalette palette = AppThemePalette.getCurrent(activity);
        if (activity.getWindow() != null) {
            activity.getWindow().setBackgroundDrawable(new ColorDrawable(palette.background));
        }

        boolean showCoverBackdrop = PreferenceConfiguration.readPreferences(activity).portalShowCoverBackdrop;
        updateBackgroundChildren(content, showCoverBackdrop);

        // If the activity root is not a GlassHost, clear its opaque background so PortalShaderView shows through
        View root = content.getChildAt(0);
        if (root != null && root != shaderView && !(root instanceof GlassHost)) {
            root.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private static void updateBackgroundChildren(View view, boolean showCoverBackdrop) {
        if (view instanceof NightSkyView) {
            view.setVisibility(View.GONE);
            return;
        }
        if (view instanceof BackdropView) {
            if (showCoverBackdrop) {
                view.setVisibility(View.VISIBLE);
                view.setAlpha(0.55f);
            } else {
                view.setVisibility(View.GONE);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                updateBackgroundChildren(vg.getChildAt(i), showCoverBackdrop);
            }
        }
    }

    private static void playColdBootSpiral(Activity activity, PortalShaderView psv, @Nullable View fg) {
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(activity);
        long durationMs = Math.max(400L, cfg.portalDisconnectOpenMs);

        psv.setIsDisconnecting(true);
        psv.setUniforms(0f, 0f, 0f, 0f, 0f);

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(durationMs);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            psv.setUniforms(0f, 0f, 0f, p, 0f);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                psv.setIsDisconnecting(false);
                psv.setUniforms(0f, 0f, 0f, 1f, 0f);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                psv.setIsDisconnecting(false);
                psv.setUniforms(0f, 0f, 0f, 1f, 0f);
            }
        });
        anim.start();

        if (fg != null && fg != psv) {
            fg.setAlpha(0f);
            fg.setScaleX(0.96f);
            fg.setScaleY(0.96f);
            fg.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(120L)
                    .setDuration(420L)
                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                    .start();
        }
    }

    @Nullable
    private static PortalShaderView findShaderView(ViewGroup root) {
        View found = root.findViewWithTag(PORTAL_BG_TAG);
        return found instanceof PortalShaderView ? (PortalShaderView) found : null;
    }

    @Nullable
    private static GlassHost findGlassHost(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c instanceof GlassHost) return (GlassHost) c;
        }
        return null;
    }
}
