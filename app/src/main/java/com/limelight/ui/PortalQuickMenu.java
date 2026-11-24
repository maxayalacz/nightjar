package com.limelight.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Moonlight-style centered One UI frosted Quick Menu + expandable Side Menu drawer over the live
 * stream, powered by single-pass off-UI-thread {@link PixelCopy} live blur and Nightjar's
 * {@link StreamMenu.Options} stream quality & control actions.
 */
public final class PortalQuickMenu {
    private static final int MAX_BITRATE_KBPS = 150000;
    private static final int MIN_BITRATE_KBPS = 500;

    private final Activity activity;
    private final Supplier<StreamMenu.Options> optionsSupplier;
    private final Consumer<StreamMenu.Item> onItem;
    private final Runnable onOpenFullMenu;
    private final Dialog dialog;
    private final AppThemePalette palette;
    private final StreamBlurController blurController;

    private FrameLayout overlayRoot;
    private FrostedStreamCardLayout quickMenuCard;
    private FrostedStreamCardLayout sideMenuCard;
    private TextView subtitleView;
    private SeekBar bitrateSeekBar;
    private TextView bitrateValueView;
    private boolean sideMenuOpen = false;
    private boolean dismissing = false;

    public PortalQuickMenu(
            @NonNull Activity activity,
            @NonNull Supplier<StreamMenu.Options> optionsSupplier,
            @NonNull Consumer<StreamMenu.Item> onItem,
            @Nullable Runnable onOpenFullMenu) {
        this.activity = activity;
        this.optionsSupplier = optionsSupplier;
        this.onItem = onItem;
        this.onOpenFullMenu = onOpenFullMenu;
        this.palette = AppThemePalette.getCurrent(activity);
        this.blurController = new StreamBlurController(activity);
        this.dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        buildUi();
    }

    public Dialog dialog() {
        return dialog;
    }

    public void showBitrate(int kbps, boolean usable) {
        if (bitrateSeekBar == null || kbps <= 0) return;
        bitrateSeekBar.setEnabled(usable);
        int clamped = Math.max(MIN_BITRATE_KBPS, Math.min(MAX_BITRATE_KBPS, kbps));
        bitrateSeekBar.setProgress((clamped - MIN_BITRATE_KBPS) / 500);
        if (bitrateValueView != null) {
            bitrateValueView.setText(formatBitrate(clamped));
        }
    }

    public void refresh() {
        StreamMenu.Options o = optionsSupplier.get();
        if (subtitleView != null && o != null) {
            subtitleView.setText(formatStreamSubtitle(o));
        }
        if (o != null && o.bitrateKbps > 0) {
            showBitrate(o.bitrateKbps, !o.bitrateWaiting);
        }
    }

    public void onHostDismissed() {
        dismissing = true;
        blurController.stop();
    }

    public void dismiss() {
        if (dismissing) return;
        dismissing = true;
        blurController.stop();
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void buildUi() {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            window.getDecorView().setSystemUiVisibility(
                    activity.getWindow().getDecorView().getSystemUiVisibility());
        }

        overlayRoot = new FrameLayout(activity);
        overlayRoot.setBackgroundColor(0x55000000);
        overlayRoot.setOnClickListener(v -> dismiss());

        int quickWidth = dp(308f);
        quickMenuCard = new FrostedStreamCardLayout(activity, palette, blurController, dp(26f));
        quickMenuCard.setClickable(true);
        quickMenuCard.setPadding(dp(18f), dp(18f), dp(18f), dp(18f));

        LinearLayout quickCol = new LinearLayout(activity);
        quickCol.setOrientation(LinearLayout.VERTICAL);

        StreamMenu.Options opts = optionsSupplier.get();

        // Header
        TextView titleTv = new TextView(activity);
        titleTv.setText(opts != null && opts.appName != null && !opts.appName.isEmpty()
                ? opts.appName : activity.getString(R.string.app_label));
        titleTv.setTextColor(palette.getPrimaryTextColor());
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setMaxLines(1);
        quickCol.addView(titleTv);

        subtitleView = new TextView(activity);
        subtitleView.setText(opts != null ? formatStreamSubtitle(opts) : "");
        subtitleView.setTextColor(palette.getSecondaryTextColor());
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2f);
        subLp.bottomMargin = dp(14f);
        quickCol.addView(subtitleView, subLp);

        // Primary Resume button (accent)
        quickCol.addView(createQuickActionButton("Resume Stream", true, false, v -> dismiss()),
                rowLp(0, 8));

        // Toggle Side Menu (Stream Quality & Controls)
        TextView btnSideMenu = createQuickActionButton("Stream Quality & Controls  ▸", false, false,
                v -> toggleSideMenu());
        quickCol.addView(btnSideMenu, rowLp(0, 8));

        // 2-column quick action grid: Keyboard / Touch Mode
        LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(createQuickActionButton("Keyboard", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.KEYBOARD)), halfLp(0, 4));
        row1.addView(createQuickActionButton("Touch Mode", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.TOUCH_MODE)), halfLp(4, 0));
        quickCol.addView(row1, rowLp(0, 8));

        // 2-column quick action grid: Performance Overlay / Stream Health
        LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(createQuickActionButton("Stats Overlay", false, false,
                v -> onItem.accept(StreamMenu.Item.OVERLAY)), halfLp(0, 4));
        row2.addView(createQuickActionButton("Stream Health", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.STREAM_HEALTH)), halfLp(4, 0));
        quickCol.addView(row2, rowLp(0, 10));

        // Disconnect + Quit Game row
        LinearLayout exitRow = new LinearLayout(activity);
        exitRow.setOrientation(LinearLayout.HORIZONTAL);
        exitRow.addView(createQuickActionButton("Disconnect", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.DISCONNECT)), halfLp(0, 4));
        exitRow.addView(createQuickActionButton("Quit Game", false, true,
                v -> runItemAndDismiss(StreamMenu.Item.QUIT_GAME)), halfLp(4, 0));
        quickCol.addView(exitRow, rowLp(0, 0));

        quickMenuCard.addView(quickCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams quickLp = new FrameLayout.LayoutParams(
                quickWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        quickLp.gravity = Gravity.CENTER;
        overlayRoot.addView(quickMenuCard, quickLp);

        // Expandable Side Menu Drawer
        sideMenuCard = buildSideMenu(opts);
        int sideWidth = dp(320f);
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(
                sideWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        sideLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        sideLp.setMargins(dp(12f), dp(20f), dp(16f), dp(20f));
        sideMenuCard.setVisibility(View.GONE);
        overlayRoot.addView(sideMenuCard, sideLp);

        dialog.setContentView(overlayRoot);
        dialog.setCancelable(true);
        dialog.setOnShowListener(d -> {
            blurController.registerTarget(quickMenuCard);
            blurController.registerTarget(sideMenuCard);
            blurController.start();
            quickMenuCard.setAlpha(0f);
            quickMenuCard.setScaleX(0.93f);
            quickMenuCard.setScaleY(0.93f);
            quickMenuCard.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200L)
                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                    .setUpdateListener(a -> quickMenuCard.invalidate())
                    .start();
        });
        dialog.setOnDismissListener(d -> blurController.stop());
    }

    private FrostedStreamCardLayout buildSideMenu(@Nullable StreamMenu.Options opts) {
        FrostedStreamCardLayout card = new FrostedStreamCardLayout(activity, palette, blurController, dp(26f));
        card.setClickable(true);
        card.setPadding(dp(16f), dp(16f), dp(16f), dp(16f));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        // Side Menu Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(activity);
        heading.setText("Stream Quality & Controls");
        heading.setTextColor(palette.getPrimaryTextColor());
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = createQuickActionButton("✕", false, false, v -> toggleSideMenu());
        closeBtn.setPadding(dp(12f), dp(6f), dp(12f), dp(6f));
        header.addView(closeBtn);
        col.addView(header, rowLp(0, 12));

        // ---- Section 1: Stream Quality (expanded by default) ----
        LinearLayout qualityBody = addAccordionSection(col, "Stream Quality", true);

        if (opts != null && opts.bitrateKbps > 0 && opts.bitrateChange != null) {
            LinearLayout brHeader = new LinearLayout(activity);
            brHeader.setOrientation(LinearLayout.HORIZONTAL);
            TextView brLabel = new TextView(activity);
            brLabel.setText("Stream Bitrate");
            brLabel.setTextColor(palette.getPrimaryTextColor());
            brLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            brHeader.addView(brLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            bitrateValueView = new TextView(activity);
            bitrateValueView.setText(formatBitrate(opts.bitrateKbps));
            bitrateValueView.setTextColor(palette.accent);
            bitrateValueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            bitrateValueView.setTypeface(Typeface.DEFAULT_BOLD);
            brHeader.addView(bitrateValueView);
            qualityBody.addView(brHeader, rowLp(0, 4));

            bitrateSeekBar = new SeekBar(activity);
            bitrateSeekBar.setMax((MAX_BITRATE_KBPS - MIN_BITRATE_KBPS) / 500);
            int clamped = Math.max(MIN_BITRATE_KBPS, Math.min(MAX_BITRATE_KBPS, opts.bitrateKbps));
            bitrateSeekBar.setProgress((clamped - MIN_BITRATE_KBPS) / 500);
            bitrateSeekBar.setEnabled(!opts.bitrateWaiting);
            bitrateSeekBar.setProgressTintList(ColorStateList.valueOf(palette.accent));
            bitrateSeekBar.setThumbTintList(ColorStateList.valueOf(palette.highlight));
            bitrateSeekBar.setProgressBackgroundTintList(
                    ColorStateList.valueOf((0x44 << 24) | (palette.secondaryText & 0x00FFFFFF)));
            bitrateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int kbps = MIN_BITRATE_KBPS + progress * 500;
                    if (bitrateValueView != null) {
                        bitrateValueView.setText(formatBitrate(kbps));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) { }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    int kbps = MIN_BITRATE_KBPS + seekBar.getProgress() * 500;
                    int applied = opts.bitrateChange.applyAsInt(kbps);
                    if (applied > 0 && bitrateValueView != null) {
                        bitrateValueView.setText(formatBitrate(applied));
                    }
                }
            });
            qualityBody.addView(bitrateSeekBar, rowLp(0, 10));
        }

        if (opts != null && opts.rateChange) {
            qualityBody.addView(createQuickActionButton("Change Bitrate & Frame Rate", false, false,
                    v -> runItemAndDismiss(StreamMenu.Item.RATE_CHANGE)), rowLp(0, 8));
        } else {
            qualityBody.addView(createQuickActionButton("Manual Bitrate Picker", false, false,
                    v -> runItemAndDismiss(StreamMenu.Item.BITRATE)), rowLp(0, 8));
        }
        qualityBody.addView(createQuickActionButton("Automatic Quality (Adaptive Res/FPS)", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.AUTOMATIC_QUALITY)), rowLp(0, 8));
        qualityBody.addView(createQuickActionButton("Toggle Automatic Bitrate", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.AUTOMATIC_BITRATE)), rowLp(0, 8));
        qualityBody.addView(createQuickActionButton("Video Scaling Mode", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.VIDEO_SCALING)), rowLp(0, 8));
        qualityBody.addView(createQuickActionButton("Toggle Performance Overlay", false, false,
                v -> onItem.accept(StreamMenu.Item.OVERLAY)), rowLp(0, 8));
        qualityBody.addView(createQuickActionButton("Stream Health & Diagnostics", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.STREAM_HEALTH)), rowLp(0, 4));

        // ---- Section 2: Controls & Audio ----
        LinearLayout controlsBody = addAccordionSection(col, "Controls & Audio", false);
        controlsBody.addView(createQuickActionButton("Toggle On-Screen Controls", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.ONSCREEN_CONTROLS)), rowLp(0, 8));
        controlsBody.addView(createQuickActionButton("PC Keyboard Overlay", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.PC_KEYBOARD)), rowLp(0, 8));
        controlsBody.addView(createQuickActionButton("Toggle Microphone Mute", false, false,
                v -> onItem.accept(StreamMenu.Item.MICROPHONE)), rowLp(0, 8));
        controlsBody.addView(createQuickActionButton("Picture-in-Picture", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.PICTURE_IN_PICTURE)), rowLp(0, 8));
        controlsBody.addView(createQuickActionButton("Reconnect Stream", false, false,
                v -> runItemAndDismiss(StreamMenu.Item.RECONNECT)), rowLp(0, 4));

        // Fallback to Nightjar's full menu if needed
        if (onOpenFullMenu != null) {
            col.addView(createQuickActionButton("More Nightjar Options…", false, false, v -> {
                dismiss();
                onOpenFullMenu.run();
            }), rowLp(8, 0));
        }

        scroll.addView(col);
        card.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return card;
    }

    private LinearLayout addAccordionSection(LinearLayout parent, String title, boolean expanded) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12f), dp(10f), dp(12f), dp(10f));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14f));
        bg.setColor((0x30 << 24) | (palette.accent & 0x00FFFFFF));
        header.setBackground(bg);

        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextColor(palette.getPrimaryTextColor());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(activity);
        chevron.setText(expanded ? "▾" : "▸");
        chevron.setTextColor(palette.accent);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        header.addView(chevron);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(4f), dp(8f), dp(4f), dp(8f));
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);

        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setText(show ? "▾" : "▸");
        });

        parent.addView(header, rowLp(4, 4));
        parent.addView(body, rowLp(0, 6));
        return body;
    }

    private void toggleSideMenu() {
        sideMenuOpen = !sideMenuOpen;
        int rootW = overlayRoot.getWidth();
        int quickW = quickMenuCard.getWidth() > 0 ? quickMenuCard.getWidth() : dp(308f);
        int sideW = sideMenuCard.getWidth() > 0 ? sideMenuCard.getWidth() : dp(320f);
        float shiftX = 0f;
        if (sideMenuOpen && rootW > (quickW + sideW + dp(40f))) {
            shiftX = -(sideW * 0.5f + dp(10f));
        }

        quickMenuCard.animate().cancel();
        quickMenuCard.animate()
                .translationX(shiftX)
                .alpha(sideMenuOpen && shiftX == 0f ? 0.35f : 1f)
                .setDuration(220L)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .setUpdateListener(a -> quickMenuCard.invalidate())
                .start();

        if (sideMenuOpen) {
            sideMenuCard.setVisibility(View.VISIBLE);
            sideMenuCard.setAlpha(0f);
            sideMenuCard.setTranslationX(dp(36f));
            sideMenuCard.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(220L)
                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                    .setUpdateListener(a -> sideMenuCard.invalidate())
                    .start();
        } else {
            sideMenuCard.animate()
                    .alpha(0f)
                    .translationX(dp(36f))
                    .setDuration(170L)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            sideMenuCard.animate().setListener(null);
                            if (!sideMenuOpen) sideMenuCard.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void runItemAndDismiss(StreamMenu.Item item) {
        onItem.accept(item);
        dismiss();
    }

    private TextView createQuickActionButton(String text, boolean primary, boolean danger, View.OnClickListener click) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(12f), dp(11f), dp(12f), dp(11f));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btn.setTypeface(primary ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        btn.setFocusable(true);
        btn.setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16f));
        if (primary) {
            bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            bg.setColors(new int[]{palette.highlight, palette.accent});
            int onAccent = ColorUtils.calculateLuminance(palette.accent) > 0.45 ? 0xFF0B0E14 : 0xFFFFFFFF;
            btn.setTextColor(onAccent);
        } else if (danger) {
            bg.setColor(0xD8C62828);
            bg.setStroke(Math.max(1, dp(1f)), 0x44FFFFFF);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor((0x38 << 24) | (palette.surface & 0x00FFFFFF));
            bg.setStroke(Math.max(1, dp(1f)), palette.cardOutline);
            btn.setTextColor(palette.getPrimaryTextColor());
        }
        btn.setBackground(bg);
        btn.setOnClickListener(click);
        return btn;
    }

    private LinearLayout.LayoutParams rowLp(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private LinearLayout.LayoutParams halfLp(int startDp, int endDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(startDp));
        lp.setMarginEnd(dp(endDp));
        return lp;
    }

    private int dp(float val) {
        return Math.round(UiHelper.dpToPx(activity, val));
    }

    private static String formatBitrate(int kbps) {
        if (kbps >= 1000) {
            return String.format(Locale.US, "%.1f Mbps", kbps / 1000f);
        }
        return kbps + " kbps";
    }

    private static String formatStreamSubtitle(StreamMenu.Options o) {
        StringBuilder sb = new StringBuilder();
        if (o.streamWidth > 0 && o.streamHeight > 0) {
            sb.append(o.streamWidth).append("×").append(o.streamHeight);
        }
        if (o.streamFps > 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(o.streamFps).append(" FPS");
        }
        if (o.bitrateKbps > 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(formatBitrate(o.bitrateKbps));
        }
        if (o.hdr) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("HDR");
        }
        return sb.toString();
    }

    /** Single-pass off-UI-thread PixelCopy live stream blur shared by Quick Menu and Side Menu. */
    private static final class StreamBlurController {
        private final Activity activity;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final List<View> targets = new ArrayList<>();
        private final int blurPct;
        private final int resPct;
        private final int satPct;
        private final boolean liveEnabled;

        private HandlerThread copyThread;
        private Handler copyHandler;
        private volatile Bitmap latestBlurred;
        private Bitmap copyBuffer;
        private int[] pixBuf;
        private int[] tmpBuf;
        private boolean running = false;
        private boolean inFlight = false;

        StreamBlurController(Activity activity) {
            this.activity = activity;
            PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(activity);
            this.liveEnabled = AppThemePalette.isLiveBlurEffective(activity) && cfg.blurAmountPercent > 0;
            this.blurPct = Math.max(0, Math.min(200, cfg.blurAmountPercent));
            this.resPct = Math.max(5, Math.min(40, cfg.blurResolutionPercent));
            this.satPct = Math.max(0, Math.min(300, cfg.blurSaturationPercent));
        }

        void registerTarget(View v) {
            targets.add(v);
        }

        void start() {
            if (!liveEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.N || running) return;
            running = true;
            copyThread = new HandlerThread("PortalQuickMenuBlur");
            copyThread.start();
            copyHandler = new Handler(copyThread.getLooper());
            scheduleNext(0L);
        }

        void stop() {
            running = false;
            mainHandler.removeCallbacksAndMessages(null);
            if (copyThread != null) {
                copyThread.quitSafely();
                copyThread = null;
                copyHandler = null;
            }
        }

        @Nullable
        Bitmap getBlurredBitmap() {
            return latestBlurred;
        }

        float getSaturation() {
            return satPct / 100f;
        }

        private void scheduleNext(long delayMs) {
            if (!running) return;
            mainHandler.postDelayed(this::requestFrame, delayMs);
        }

        private void requestFrame() {
            if (!running || inFlight || copyHandler == null) return;
            View sv = activity.findViewById(R.id.surfaceView);
            if (!(sv instanceof SurfaceView)) return;
            SurfaceView surfaceView = (SurfaceView) sv;
            if (!surfaceView.getHolder().getSurface().isValid()
                    || surfaceView.getWidth() <= 0 || surfaceView.getHeight() <= 0) {
                scheduleNext(60L);
                return;
            }
            float scale = resPct / 100f;
            int w = Math.max(32, Math.round(surfaceView.getWidth() * scale));
            int h = Math.max(32, Math.round(surfaceView.getHeight() * scale));
            if (copyBuffer == null || copyBuffer.getWidth() != w || copyBuffer.getHeight() != h) {
                copyBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                pixBuf = new int[w * h];
                tmpBuf = new int[w * h];
            }
            inFlight = true;
            try {
                PixelCopy.request(surfaceView, copyBuffer, result -> {
                    inFlight = false;
                    if (!running) return;
                    if (result == PixelCopy.SUCCESS) {
                        int radius = Math.max(1, Math.min(18, Math.round((blurPct / 100f) * 6f)));
                        UiHelper.fastBoxBlur(copyBuffer, radius, pixBuf, tmpBuf);
                        latestBlurred = copyBuffer;
                        mainHandler.post(() -> {
                            for (View t : targets) {
                                if (t.getVisibility() == View.VISIBLE) t.invalidate();
                            }
                        });
                    }
                    scheduleNext(33L);
                }, copyHandler);
            } catch (Throwable ignored) {
                inFlight = false;
            }
        }
    }

    /** Frosted card layout drawing live blurred stream backdrop + unified One UI tint & rim. */
    private static final class FrostedStreamCardLayout extends FrameLayout {
        private final AppThemePalette palette;
        private final StreamBlurController blur;
        private final float cornerRadius;
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF bounds = new RectF();
        private final int[] loc = new int[2];

        FrostedStreamCardLayout(Context context, AppThemePalette palette,
                                StreamBlurController blur, float cornerRadius) {
            super(context);
            this.palette = palette;
            this.blur = blur;
            this.cornerRadius = cornerRadius;
            setWillNotDraw(false);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(blur.getSaturation());
            bmpPaint.setColorFilter(new ColorMatrixColorFilter(cm));
            rimPaint.setStyle(Paint.Style.STROKE);
            float outlineScale = Math.max(0f, PreferenceConfiguration.readPreferences(context)
                    .cardOutlineThicknessPercent / 100f);
            rimPaint.setStrokeWidth(outlineScale <= 0f ? 0f
                    : Math.max(1f, UiHelper.dpToPx(context, 1.2f) * outlineScale));
            rimPaint.setColor(palette.cardOutline);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            bounds.set(0, 0, w, h);
            clipPath.reset();
            clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW);

            Bitmap bmp = blur.getBlurredBitmap();
            boolean drewBlur = false;
            if (bmp != null && !bmp.isRecycled()) {
                getLocationOnScreen(loc);
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int screenH = getResources().getDisplayMetrics().heightPixels;
                if (screenW > 0 && screenH > 0) {
                    canvas.save();
                    canvas.clipPath(clipPath);
                    Rect dst = new Rect(-loc[0], -loc[1], screenW - loc[0], screenH - loc[1]);
                    canvas.drawBitmap(bmp, null, dst, bmpPaint);
                    canvas.restore();
                    drewBlur = true;
                }
            }

            fillPaint.setColor(palette.getQuickMenuCardTint(drewBlur || !blur.liveEnabled));
            canvas.drawPath(clipPath, fillPaint);

            if (rimPaint.getStrokeWidth() > 0f) {
                float half = rimPaint.getStrokeWidth() * 0.5f;
                bounds.inset(half, half);
                canvas.drawRoundRect(bounds, Math.max(0f, cornerRadius - half),
                        Math.max(0f, cornerRadius - half), rimPaint);
            }
            super.onDraw(canvas);
        }
    }
}
