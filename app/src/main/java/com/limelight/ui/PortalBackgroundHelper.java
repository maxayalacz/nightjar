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

    public static boolean isRobolectric() {
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

        boolean recreated = (activity instanceof NightjarActivity)
                && ((NightjarActivity) activity).isRecreatedFromState();
        if (!sColdBootAnimPlayed) {
            sColdBootAnimPlayed = true;
            playColdBootSpiral(activity, shaderView, getForegroundRoot(content, shaderView, handoffView));
        } else if (recreated) {
            forceForegroundVisible(activity);
        } else {
            animateForegroundEnter(activity, false);
        }

        return shaderView;
    }

    /** Called from NightjarActivity.onConfigurationChanged() when rotating without recreating. */
    public static void onConfigurationChanged(Activity activity) {
        if (activity == null || isRobolectric()) return;
        PortalShaderView psv = attach(activity);
        if (psv != null) {
            psv.refreshFromPrefs();
            psv.startRendering();
        }
        forceForegroundVisible(activity);
        OneUiThemeApplier.applyToActivity(activity);
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content != null) {
            GlassHost host = findGlassHost(content);
            if (host != null) {
                host.attachPortalSync(psv);
                host.refreshGlass();
            }
        }
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
                host.attachPortalSync(psv);
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

    /** Smoothly fades out foreground UI (keeping PortalShaderView at alpha=1), captures handoff, and launches next menu. */
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
        final GlassHost host = findGlassHost(content);
        java.util.List<View> fgs = collectForegroundViews(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        if (fgs.isEmpty()) {
            captureHandoff(from);
            launchAction.run();
            UiHelper.disableActivityTransition(from, false);
            return;
        }
        final View anchor = fgs.get(0);
        if (Boolean.TRUE.equals(anchor.getTag(TAG_EXIT_RUNNING))) {
            return;
        }
        final int gen = sAnimGen.incrementAndGet();
        anchor.setTag(TAG_ANIM_GEN, gen);
        anchor.setTag(TAG_ENTER_RUNNING, null);
        anchor.setTag(TAG_EXIT_RUNNING, Boolean.TRUE);

        for (int i = 0; i < fgs.size(); i++) {
            final View fg = fgs.get(i);
            fg.animate().setListener(null).setUpdateListener(null).cancel();
            if (fg.getWidth() > 0 && fg.getHeight() > 0) {
                fg.setPivotX(fg.getWidth() * 0.5f);
                fg.setPivotY(fg.getHeight() * 0.5f);
            }
            android.view.ViewPropertyAnimator anim = fg.animate()
                    .alpha(0f)
                    .scaleX(1.03f)
                    .scaleY(1.03f)
                    .setDuration(135L)
                    .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f));
            if (i == 0) {
                if (host != null) {
                    anim.setUpdateListener(a -> host.invalidate());
                }
                anim.setListener(new AnimatorListenerAdapter() {
                    private boolean canceled = false;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                        anchor.animate().setListener(null).setUpdateListener(null);
                        if (Integer.valueOf(gen).equals(anchor.getTag(TAG_ANIM_GEN))) {
                            anchor.setTag(TAG_EXIT_RUNNING, null);
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        anchor.animate().setListener(null).setUpdateListener(null);
                        if (Integer.valueOf(gen).equals(anchor.getTag(TAG_ANIM_GEN))) {
                            anchor.setTag(TAG_EXIT_RUNNING, null);
                        }
                        if (!canceled && !from.isFinishing()) {
                            captureHandoff(from);
                            launchAction.run();
                            UiHelper.disableActivityTransition(from, false);
                        }
                    }
                });
            }
            anim.start();
        }
    }

    /** Smoothly fades out foreground UI (keeping PortalShaderView at alpha=1), captures handoff, and finishes. */
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
        final GlassHost host = findGlassHost(content);
        java.util.List<View> fgs = collectForegroundViews(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        if (fgs.isEmpty()) {
            captureHandoff(activity);
            UiHelper.disableActivityTransition(activity, true);
            superFinish.run();
            return;
        }
        final View anchor = fgs.get(0);
        if (Boolean.TRUE.equals(anchor.getTag(TAG_EXIT_RUNNING))) {
            return;
        }
        final int gen = sAnimGen.incrementAndGet();
        anchor.setTag(TAG_ANIM_GEN, gen);
        anchor.setTag(TAG_ENTER_RUNNING, null);
        anchor.setTag(TAG_EXIT_RUNNING, Boolean.TRUE);

        for (int i = 0; i < fgs.size(); i++) {
            final View fg = fgs.get(i);
            fg.animate().setListener(null).setUpdateListener(null).cancel();
            if (fg.getWidth() > 0 && fg.getHeight() > 0) {
                fg.setPivotX(fg.getWidth() * 0.5f);
                fg.setPivotY(fg.getHeight() * 0.5f);
            }
            android.view.ViewPropertyAnimator anim = fg.animate()
                    .alpha(0f)
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(130L)
                    .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f));
            if (i == 0) {
                if (host != null) {
                    anim.setUpdateListener(a -> host.invalidate());
                }
                anim.setListener(new AnimatorListenerAdapter() {
                    private boolean canceled = false;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                        anchor.animate().setListener(null).setUpdateListener(null);
                        if (Integer.valueOf(gen).equals(anchor.getTag(TAG_ANIM_GEN))) {
                            anchor.setTag(TAG_EXIT_RUNNING, null);
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        anchor.animate().setListener(null).setUpdateListener(null);
                        if (Integer.valueOf(gen).equals(anchor.getTag(TAG_ANIM_GEN))) {
                            anchor.setTag(TAG_EXIT_RUNNING, null);
                        }
                        if (!canceled) {
                            captureHandoff(activity);
                            UiHelper.disableActivityTransition(activity, true);
                            superFinish.run();
                        }
                    }
                });
            }
            anim.start();
        }
    }

    /** Immediately restores full foreground visibility and scale without fading from 0. */
    public static void forceForegroundVisible(Activity activity) {
        if (activity == null) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        java.util.List<View> fgs = collectForegroundViews(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        for (int i = 0; i < fgs.size(); i++) {
            View fg = fgs.get(i);
            fg.animate().setListener(null).setUpdateListener(null).cancel();
            if (i == 0) {
                fg.setTag(TAG_ENTER_PLAYED, Boolean.TRUE);
                fg.setTag(TAG_ENTER_RUNNING, null);
                fg.setTag(TAG_EXIT_RUNNING, null);
            }
            fg.setAlpha(1f);
            fg.setScaleX(1f);
            fg.setScaleY(1f);
        }
        GlassHost host = findGlassHost(content);
        if (host != null) {
            host.refreshGlass();
        }
    }

    private static void animateForegroundEnter(Activity activity, boolean returning) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        final GlassHost host = findGlassHost(content);
        final java.util.List<View> fgs = collectForegroundViews(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        if (fgs.isEmpty()) return;
        final View anchor = fgs.get(0);
        if (!returning && Boolean.TRUE.equals(anchor.getTag(TAG_ENTER_PLAYED))) return;
        anchor.setTag(TAG_ENTER_PLAYED, Boolean.TRUE);

        final int gen = sAnimGen.incrementAndGet();
        anchor.setTag(TAG_ANIM_GEN, gen);
        anchor.setTag(TAG_EXIT_RUNNING, null);
        anchor.setTag(TAG_ENTER_RUNNING, Boolean.TRUE);

        float startScale = returning ? 1.025f : 0.975f;
        for (View fg : fgs) {
            fg.animate().setListener(null).setUpdateListener(null).cancel();
            fg.setAlpha(0f);
            fg.setScaleX(startScale);
            fg.setScaleY(startScale);
        }

        final Runnable startAnim = () -> {
            if (!Integer.valueOf(gen).equals(anchor.getTag(TAG_ANIM_GEN))) return;
            for (int i = 0; i < fgs.size(); i++) {
                final View fg = fgs.get(i);
                if (fg.getWidth() > 0 && fg.getHeight() > 0) {
                    fg.setPivotX(fg.getWidth() * 0.5f);
                    fg.setPivotY(fg.getHeight() * 0.5f);
                }
                android.view.ViewPropertyAnimator anim = fg.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200L)
                        .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f));
                if (i == 0) {
                    if (host != null) {
                        anim.setUpdateListener(a -> host.invalidate());
                    }
                    anim.setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            anchor.animate().setListener(null).setUpdateListener(null);
                            if (Integer.valueOf(gen).equals(anchor.getTag(TAG_ANIM_GEN))) {
                                anchor.setTag(TAG_ENTER_RUNNING, null);
                            }
                            if (host != null) host.refreshGlass();
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            anchor.animate().setListener(null).setUpdateListener(null);
                            if (Integer.valueOf(gen).equals(anchor.getTag(TAG_ANIM_GEN))) {
                                anchor.setTag(TAG_ENTER_RUNNING, null);
                            }
                            if (host != null) host.refreshGlass();
                        }
                    });
                }
                anim.start();
            }
        };

        // Fail-safe so foreground UI can never remain stuck invisible if a rotation/layout pass interrupts animation
        anchor.postDelayed(() -> {
            if (Integer.valueOf(gen).equals(anchor.getTag(TAG_ANIM_GEN))
                    && !Boolean.TRUE.equals(anchor.getTag(TAG_EXIT_RUNNING))) {
                forceForegroundVisible(activity);
            }
        }, 320L);

        anchor.post(startAnim);
    }

    private static void restoreForegroundIfNeeded(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        java.util.List<View> fgs = collectForegroundViews(content, findShaderView(content), content.findViewWithTag(PORTAL_HANDOFF_TAG));
        if (fgs.isEmpty()) return;
        final View anchor = fgs.get(0);
        anchor.setTag(TAG_EXIT_RUNNING, null);
        if (Boolean.TRUE.equals(anchor.getTag(TAG_ENTER_RUNNING))) return;
        if (sReturningFromStream) {
            sReturningFromStream = false;
            animateForegroundEnter(activity, true);
            return;
        }
        if (anchor.getAlpha() < 0.99f || Math.abs(anchor.getScaleX() - 1f) > 0.005f) {
            if ((activity instanceof NightjarActivity) && ((NightjarActivity) activity).isRecreatedFromState()) {
                forceForegroundVisible(activity);
            } else {
                animateForegroundEnter(activity, true);
            }
        }
    }

    /** Returns only foreground UI views, never GlassHost itself or PortalShaderView/handoffView. */
    private static java.util.List<View> collectForegroundViews(ViewGroup content, @Nullable View shaderView, @Nullable View handoffView) {
        java.util.List<View> result = new java.util.ArrayList<>();
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (isBgView(child, shaderView, handoffView)) continue;
            if (child instanceof GlassHost) {
                GlassHost host = (GlassHost) child;
                for (int j = 0; j < host.getChildCount(); j++) {
                    View hc = host.getChildAt(j);
                    if (j == 0 && hc instanceof ViewGroup) {
                        ViewGroup layer0 = (ViewGroup) hc;
                        for (int k = 0; k < layer0.getChildCount(); k++) {
                            View c0 = layer0.getChildAt(k);
                            if (!isBgView(c0, shaderView, handoffView) && !(c0 instanceof NightSkyView) && !(c0 instanceof BackdropView)) {
                                result.add(c0);
                            }
                        }
                    } else if (!isBgView(hc, shaderView, handoffView)) {
                        result.add(hc);
                    }
                }
            } else {
                result.add(child);
            }
        }
        return result;
    }

    private static boolean isBgView(View v, @Nullable View shaderView, @Nullable View handoffView) {
        return v == shaderView || v == handoffView
                || PORTAL_BG_TAG.equals(v.getTag())
                || PORTAL_HANDOFF_TAG.equals(v.getTag());
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
