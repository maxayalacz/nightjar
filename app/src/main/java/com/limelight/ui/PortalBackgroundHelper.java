package com.limelight.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.UiHelper;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Attaches and lifecycle-manages {@link PortalShaderView} across {@link NightjarActivity} screens,
 * providing Frame-0 starfield handoff (zero jump/stutter between menus) and One UI foreground
 * fade-in / fade-out depth transitions.
 */
public final class PortalBackgroundHelper {
    private static final String PORTAL_BG_TAG = "oneui_portal_starfield_bg";
    private static final String PORTAL_HANDOFF_TAG = "oneui_portal_handoff_bg";
    private static final int TAG_ANIM_GEN = 0x7F0A1101;
    private static final int TAG_ENTER_RUNNING = 0x7F0A1102;
    private static final int TAG_EXIT_RUNNING = 0x7F0A1103;
    private static final int TAG_ENTER_PLAYED = 0x7F0A1104;

    private static final AtomicInteger sAnimGen = new AtomicInteger(1);
    private static boolean sColdBootAnimPlayed = false;
    private static volatile boolean sReturningFromStream = false;

    private PortalBackgroundHelper() { }

    static boolean isRobolectric() {
        return "robolectric".equalsIgnoreCase(Build.FINGERPRINT);
    }

    public static void markReturningFromStream() {
        sReturningFromStream = true;
    }

    /** Finds the hosting Activity from any Context (including ContextThemeWrapper). */
    @Nullable
    public static Activity activityOf(@Nullable Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Returns the active PortalShaderView attached to this activity, if any. */
    @Nullable
    public static PortalShaderView getShaderView(@Nullable Activity activity) {
        if (activity == null) return null;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        return findShaderView(content);
    }

    /** Ensures handoff ImageView + PortalShaderView are attached behind the activity's content. */
    @Nullable
    public static PortalShaderView attach(Activity activity) {
        if (activity == null || isRobolectric()) return null;
        if (activity instanceof ThemeStudioActivity) return null;

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return null;

        PortalShaderView existing = findShaderView(content);
        if (existing != null) {
            showHandoffUntilFirstFrame(activity, content, existing);
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

        AppThemePalette initPalette = AppThemePalette.getCurrent(activity);
        boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        // Frame-0 handoff placeholder shown behind GL while the new surface initializes
        final ImageView handoffView = new ImageView(activity);
        handoffView.setTag(PORTAL_HANDOFF_TAG);
        handoffView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        handoffView.setBackgroundColor(initPalette.background | 0xFF000000);
        handoffView.setClickable(false);
        handoffView.setFocusable(false);
        handoffView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Bitmap snap = PortalShaderView.getHandoffBitmap(landscape);
        if (snap != null && !snap.isRecycled()) {
            handoffView.setImageBitmap(snap);
        }

        PortalShaderView shaderView = new PortalShaderView(activity);
        shaderView.setTag(PORTAL_BG_TAG);
        shaderView.configureStarfieldBackgroundMode();
        shaderView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shaderView.setFirstFrameListener(() ->
                handoffView.postOnAnimation(() -> handoffView.setVisibility(View.GONE)));

        ViewGroup.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        targetContainer.addView(handoffView, 0, lp);
        targetContainer.addView(shaderView, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        applyBackdropAndTheme(activity, content, shaderView);

        if (!sColdBootAnimPlayed) {
            sColdBootAnimPlayed = true;
            playColdBootSpiral(activity, shaderView, getForegroundRoot(content, shaderView, handoffView));
        } else {
            animateForegroundEnter(activity, false);
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
        OneUiThemeApplier.applyToActivity(activity);
        restoreForegroundIfNeeded(activity);
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

    /** Smoothly fades out foreground, captures starfield handoff, and launches the next menu activity. */
    public static void startMenuActivityWithDepth(Activity from, Intent intent, Runnable launchAction) {
        if (from == null || intent == null || launchAction == null || from.isFinishing() || isRobolectric()) {
            if (launchAction != null) launchAction.run();
            return;
        }
        ViewGroup content = from.findViewById(android.R.id.content);
        if (content == null) {
            captureHandoff(from);
            launchAction.run();
            UiHelper.disableActivityTransition(from, false);
            return;
        }
        final View fg = getForegroundRoot(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        if (fg == null) {
            captureHandoff(from);
            launchAction.run();
            UiHelper.disableActivityTransition(from, false);
            return;
        }
        if (Boolean.TRUE.equals(fg.getTag(TAG_EXIT_RUNNING))) {
            return;
        }
        final int gen = sAnimGen.incrementAndGet();
        fg.animate().setListener(null).cancel();
        fg.setTag(TAG_ANIM_GEN, gen);
        fg.setTag(TAG_ENTER_RUNNING, null);
        fg.setTag(TAG_EXIT_RUNNING, Boolean.TRUE);
        if (fg.getWidth() > 0 && fg.getHeight() > 0) {
            fg.setPivotX(fg.getWidth() * 0.5f);
            fg.setPivotY(fg.getHeight() * 0.5f);
        }
        fg.animate()
                .alpha(0f)
                .scaleX(1.035f)
                .scaleY(1.035f)
                .setDuration(140L)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withLayer()
                .setListener(new AnimatorListenerAdapter() {
                    private boolean canceled = false;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                        fg.animate().setListener(null);
                        if (Integer.valueOf(gen).equals(fg.getTag(TAG_ANIM_GEN))) {
                            fg.setTag(TAG_EXIT_RUNNING, null);
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        fg.animate().setListener(null);
                        if (Integer.valueOf(gen).equals(fg.getTag(TAG_ANIM_GEN))) {
                            fg.setTag(TAG_EXIT_RUNNING, null);
                        }
                        if (!canceled && !from.isFinishing()) {
                            captureHandoff(from);
                            launchAction.run();
                            UiHelper.disableActivityTransition(from, false);
                        }
                    }
                })
                .start();
    }

    /** Smoothly fades out foreground, captures starfield handoff, and finishes the menu activity. */
    public static void finishMenuWithDepth(Activity activity, Runnable superFinish) {
        if (activity == null || superFinish == null || isRobolectric()) {
            if (superFinish != null) superFinish.run();
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            captureHandoff(activity);
            UiHelper.disableActivityTransition(activity, true);
            superFinish.run();
            return;
        }
        final View fg = getForegroundRoot(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        if (fg == null) {
            captureHandoff(activity);
            UiHelper.disableActivityTransition(activity, true);
            superFinish.run();
            return;
        }
        if (Boolean.TRUE.equals(fg.getTag(TAG_EXIT_RUNNING))) {
            return;
        }
        final int gen = sAnimGen.incrementAndGet();
        fg.animate().setListener(null).cancel();
        fg.setTag(TAG_ANIM_GEN, gen);
        fg.setTag(TAG_ENTER_RUNNING, null);
        fg.setTag(TAG_EXIT_RUNNING, Boolean.TRUE);
        if (fg.getWidth() > 0 && fg.getHeight() > 0) {
            fg.setPivotX(fg.getWidth() * 0.5f);
            fg.setPivotY(fg.getHeight() * 0.5f);
        }
        fg.animate()
                .alpha(0f)
                .scaleX(0.965f)
                .scaleY(0.965f)
                .setDuration(135L)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withLayer()
                .setListener(new AnimatorListenerAdapter() {
                    private boolean canceled = false;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                        fg.animate().setListener(null);
                        if (Integer.valueOf(gen).equals(fg.getTag(TAG_ANIM_GEN))) {
                            fg.setTag(TAG_EXIT_RUNNING, null);
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        fg.animate().setListener(null);
                        if (Integer.valueOf(gen).equals(fg.getTag(TAG_ANIM_GEN))) {
                            fg.setTag(TAG_EXIT_RUNNING, null);
                        }
                        if (!canceled) {
                            captureHandoff(activity);
                            UiHelper.disableActivityTransition(activity, true);
                            superFinish.run();
                        }
                    }
                })
                .start();
    }

    private static void animateForegroundEnter(Activity activity, boolean returning) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        final View fg = getForegroundRoot(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        if (fg == null) return;
        if (!returning && Boolean.TRUE.equals(fg.getTag(TAG_ENTER_PLAYED))) return;
        fg.setTag(TAG_ENTER_PLAYED, Boolean.TRUE);

        final int gen = sAnimGen.incrementAndGet();
        fg.animate().setListener(null).cancel();
        fg.setTag(TAG_ANIM_GEN, gen);
        fg.setTag(TAG_EXIT_RUNNING, null);
        fg.setTag(TAG_ENTER_RUNNING, Boolean.TRUE);
        fg.setAlpha(0f);
        float startScale = returning ? 1.03f : 0.97f;
        fg.setScaleX(startScale);
        fg.setScaleY(startScale);

        final Runnable startAnim = () -> {
            if (!Integer.valueOf(gen).equals(fg.getTag(TAG_ANIM_GEN))) return;
            if (fg.getWidth() > 0 && fg.getHeight() > 0) {
                fg.setPivotX(fg.getWidth() * 0.5f);
                fg.setPivotY(fg.getHeight() * 0.5f);
            }
            fg.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(210L)
                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                    .withLayer()
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            fg.animate().setListener(null);
                            if (Integer.valueOf(gen).equals(fg.getTag(TAG_ANIM_GEN))) {
                                fg.setTag(TAG_ENTER_RUNNING, null);
                            }
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            fg.animate().setListener(null);
                            if (Integer.valueOf(gen).equals(fg.getTag(TAG_ANIM_GEN))) {
                                fg.setTag(TAG_ENTER_RUNNING, null);
                            }
                        }
                    })
                    .start();
        };

        if (fg.getWidth() > 0 && fg.getHeight() > 0) {
            fg.postOnAnimation(startAnim);
        } else {
            fg.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (fg.getViewTreeObserver().isAlive()) {
                        fg.getViewTreeObserver().removeOnPreDrawListener(this);
                    }
                    fg.postOnAnimation(startAnim);
                    return true;
                }
            });
        }
    }

    private static void restoreForegroundIfNeeded(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        final View fg = getForegroundRoot(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        if (fg == null) return;
        fg.setTag(TAG_EXIT_RUNNING, null);
        if (Boolean.TRUE.equals(fg.getTag(TAG_ENTER_RUNNING))) return;
        if (sReturningFromStream) {
            sReturningFromStream = false;
            animateForegroundEnter(activity, true);
            return;
        }
        if (fg.getAlpha() < 0.99f || Math.abs(fg.getScaleX() - 1f) > 0.005f) {
            animateForegroundEnter(activity, true);
        }
    }

    private static void showHandoffUntilFirstFrame(Activity activity, ViewGroup content, PortalShaderView psv) {
        View candidate = content.findViewWithTag(PORTAL_HANDOFF_TAG);
        if (!(candidate instanceof ImageView)) return;
        final ImageView handoffView = (ImageView) candidate;
        AppThemePalette palette = AppThemePalette.getCurrent(activity);
        handoffView.setBackgroundColor(palette.background | 0xFF000000);
        if (psv.hasRenderedFrame()) {
            handoffView.setVisibility(View.GONE);
            return;
        }
        boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        Bitmap snap = PortalShaderView.getHandoffBitmap(landscape);
        if (snap != null && !snap.isRecycled()) {
            handoffView.setImageBitmap(snap);
        } else {
            handoffView.setImageDrawable(null);
        }
        handoffView.setVisibility(View.VISIBLE);
        psv.setFirstFrameListener(() ->
                handoffView.postOnAnimation(() -> handoffView.setVisibility(View.GONE)));
    }

    private static void applyBackdropAndTheme(Activity activity, ViewGroup content, PortalShaderView shaderView) {
        AppThemePalette palette = AppThemePalette.getCurrent(activity);
        if (activity.getWindow() != null) {
            activity.getWindow().setBackgroundDrawable(new ColorDrawable(palette.background | 0xFF000000));
        }

        boolean showCoverBackdrop = PreferenceConfiguration.readPreferences(activity).portalShowCoverBackdrop;
        updateBackgroundChildren(content, showCoverBackdrop);

        View root = getForegroundRoot(content, shaderView, content.findViewWithTag(PORTAL_HANDOFF_TAG));
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
            fg.setTag(TAG_ENTER_PLAYED, Boolean.TRUE);
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
    private static View getForegroundRoot(ViewGroup content, @Nullable View shaderView, @Nullable View handoffView) {
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child != shaderView && child != handoffView
                    && !PORTAL_BG_TAG.equals(child.getTag())
                    && !PORTAL_HANDOFF_TAG.equals(child.getTag())) {
                return child;
            }
        }
        return null;
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
