package com.limelight.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.R;
import com.limelight.binding.input.touch.TouchMode;
import com.limelight.binding.video.PerformanceOverlay;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 1:1 port of the Moonlight fork's One UI frosted Quick Menu (centered 4x2 squircle icon grid +
 * centered Settings gear pill) and switchable Left/Right collapsible Accordion Side Settings Drawer,
 * extended with Nightjar's live Stream Quality controls.
 */
public final class PortalQuickMenu {
    private static final int NEON_ORANGE = 0xFFFF9100;
    private static final int NEON_RED = 0xFFFF3B5C;
    private static final int MAX_BITRATE_KBPS = 150000;
    private static final int MIN_BITRATE_KBPS = 500;
    private static final String PREF_DRAWER_ON_RIGHT = "quick_settings_drawer_right";
    private static final PathInterpolator SPRING_EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);

    /** Live callbacks bridging the Quick Menu and Side Drawer directly to the active stream. */
    public interface Host {
        int getTouchMode();
        void cycleTouchMode();
        boolean isOscVisible();
        void toggleOscLive();
        boolean isPcKeyboardVisible();
        void togglePerfOverlaySessionOnly();
        void sendClipboardLive();
        void fetchClipboardLive();
        void setOscOpacityLive(int opacity);
        void setOscOutlineThicknessLive(int percent);
        void setOscVibrateLive(boolean enabled);
        void setFreeAnalogStickLive(boolean enabled);
        void setOscLiveBlurLive(boolean enabled);
        void setOscBlurAmountLive(int percent);
        void setOscBlurResolutionPercentLive(int percent);
        void setOscBlurFramerateLive(String fps);
        String getOscEngine();
        void setOscEngineLive(String engine);
        void setTrackpadSensitivityXLive(int percent);
        void setTrackpadSensitivityYLive(int percent);
        void setTrackpadSwapAxisLive(boolean enabled);
        void setFloatingMenuButtonLive(boolean enabled);
        void setDisableWarningsLive(boolean enabled);
        void setCardOutlineThicknessLive(int percent);
        void setUiPerformanceModeLive(boolean enabled);
        void setUiBatterySaverModeLive(String mode);
        void setPerformanceOverlayModeLive(@Nullable String mode);
    }

    private interface BooleanConsumer {
        void accept(boolean value);
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private static final class AccordionSection {
        final LinearLayout content;
        final TextView chevron;
        boolean expanded = false;

        AccordionSection(LinearLayout content, TextView chevron) {
            this.content = content;
            this.chevron = chevron;
        }
    }

    private final Activity activity;
    private final Supplier<StreamMenu.Options> optionsSupplier;
    private final Consumer<StreamMenu.Item> onItem;
    private final Host host;
    @Nullable private final Runnable onOpenFullMenu;
    private final Dialog dialog;
    private final AppThemePalette palette;
    private final StreamBlurController blurController;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private FrameLayout overlayRoot;
    private FrostedStreamCardLayout quickMenuCard;
    private FrostedStreamCardLayout sideDrawerCard;
    private SeekBar bitrateSeekBar;
    private TextView bitrateValueView;
    private Runnable refreshMouseTile;
    private Runnable refreshOscTile;
    private Runnable refreshPerfTile;
    private boolean drawerOpen = false;
    private boolean drawerOnRight = false;
    private Runnable closeDrawerAction;
    private boolean dismissing = false;

    public PortalQuickMenu(
            @NonNull Activity activity,
            @NonNull Supplier<StreamMenu.Options> optionsSupplier,
            @NonNull Consumer<StreamMenu.Item> onItem,
            @NonNull Host host,
            @Nullable Runnable onOpenFullMenu) {
        this.activity = activity;
        this.optionsSupplier = optionsSupplier;
        this.onItem = onItem;
        this.host = host;
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
        if (o != null && o.bitrateKbps > 0) {
            showBitrate(o.bitrateKbps, !o.bitrateWaiting);
        }
        if (refreshMouseTile != null) refreshMouseTile.run();
        if (refreshOscTile != null) refreshOscTile.run();
        if (refreshPerfTile != null) refreshPerfTile.run();
    }

    public void onHostDismissed() {
        dismissing = true;
        uiHandler.removeCallbacksAndMessages(null);
        blurController.stop();
    }

    public void dismiss() {
        if (dismissing) return;
        dismissing = true;
        uiHandler.removeCallbacksAndMessages(null);
        blurController.stop();
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void buildUi() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_BUTTON_B
                    || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                if (drawerOpen && closeDrawerAction != null) {
                    closeDrawerAction.run();
                    return true;
                }
                dismiss();
                return true;
            }
            return false;
        });

        final StreamMenu.Options opts = optionsSupplier.get();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        drawerOnRight = prefs.getBoolean(PREF_DRAWER_ON_RIGHT, false);

        // Fullscreen transparent overlay container catching outside taps
        overlayRoot = new FrameLayout(activity);
        overlayRoot.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayRoot.setClipChildren(false);
        overlayRoot.setClipToPadding(false);

        final View scrimView = new View(activity);
        scrimView.setBackgroundColor(0x28000000);
        scrimView.setAlpha(0f);
        scrimView.setOnClickListener(v -> dismiss());
        overlayRoot.addView(scrimView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Centered Quick Menu Card (4x2 Squircle Grid + Centered Settings Pill Below) ---
        final int tileSize = dp(58f);
        final int tileGap = dp(12f);
        final int cardPad = dp(18f);
        final float cardRadius = dp(28f);

        quickMenuCard = new FrostedStreamCardLayout(activity, palette, blurController, cardRadius);
        quickMenuCard.setClickable(true);

        LinearLayout rootColumn = new LinearLayout(activity);
        rootColumn.setOrientation(LinearLayout.VERTICAL);
        rootColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        rootColumn.setPadding(cardPad, cardPad, cardPad, dp(14f));
        rootColumn.setClipChildren(false);
        rootColumn.setClipToPadding(false);

        LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row1.setClipChildren(false);
        row1.setClipToPadding(false);

        LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.setClipChildren(false);
        row2.setClipToPadding(false);

        // 1. Disconnect (Neon Orange icon & outline)
        ImageButton btnDisconnect = createSquircleIconButton(
                activity, R.drawable.ic_qm_disconnect, "Disconnect", tileSize);
        applyNeonTileStyle(btnDisconnect, NEON_ORANGE, false);
        attachFocusRing(btnDisconnect, () -> applyNeonTileStyle(btnDisconnect, NEON_ORANGE, btnDisconnect.isFocused()));
        btnDisconnect.setOnClickListener(v -> {
            animateTileTap(v);
            runItemAndDismiss(StreamMenu.Item.DISCONNECT);
        });

        // 2. Quit Session (Neon Red icon & outline)
        ImageButton btnQuit = createSquircleIconButton(
                activity, R.drawable.ic_qm_quit, "Quit Session", tileSize);
        applyNeonTileStyle(btnQuit, NEON_RED, false);
        attachFocusRing(btnQuit, () -> applyNeonTileStyle(btnQuit, NEON_RED, btnQuit.isFocused()));
        btnQuit.setOnClickListener(v -> {
            animateTileTap(v);
            runItemAndDismiss(StreamMenu.Item.QUIT_GAME);
        });

        // 3. Mouse / Touch Mode Cycle
        final boolean canTouch = opts == null || (opts.canSendInput && opts.localOutput);
        ImageButton btnMouseMode = createSquircleIconButton(
                activity, resolveTouchModeIcon(host.getTouchMode()), "Touch / Mouse Mode", tileSize);
        refreshMouseTile = () -> {
            int mode = host.getTouchMode();
            btnMouseMode.setImageResource(resolveTouchModeIcon(mode));
            String[] modeNames = activity.getResources().getStringArray(R.array.stream_touch_modes);
            String desc = (mode >= 0 && mode < modeNames.length) ? modeNames[mode] : "Touch Mode";
            setTooltipAndDesc(btnMouseMode, desc);
            boolean active = (mode != TouchMode.OFF);
            applyToggleTileStyle(btnMouseMode, active, btnMouseMode.isFocused());
        };
        refreshMouseTile.run();
        attachFocusRing(btnMouseMode, refreshMouseTile);
        btnMouseMode.setEnabled(canTouch);
        if (!canTouch) btnMouseMode.setAlpha(0.32f);
        btnMouseMode.setOnClickListener(v -> {
            animateTileTap(v);
            host.cycleTouchMode();
            refreshMouseTile.run();
        });

        // 4. On-Screen Controller Toggle
        final boolean canOsc = opts == null || (opts.canSendInput && opts.localOutput);
        ImageButton btnOsc = createSquircleIconButton(
                activity, R.drawable.ic_qm_osc, "On-Screen Controller", tileSize);
        refreshOscTile = () -> applyToggleTileStyle(btnOsc, host.isOscVisible(), btnOsc.isFocused());
        refreshOscTile.run();
        attachFocusRing(btnOsc, refreshOscTile);
        btnOsc.setEnabled(canOsc);
        if (!canOsc) {
            btnOsc.setAlpha(0.32f);
        } else {
            btnOsc.setOnClickListener(v -> {
                animateTileTap(v);
                host.toggleOscLive();
                refreshOscTile.run();
            });
        }

        // 5. On-Screen Keyboard Toggle
        final boolean canInput = opts == null || opts.canSendInput;
        ImageButton btnKeyboard = createSquircleIconButton(
                activity, R.drawable.ic_qm_keyboard, "On-Screen Keyboard", tileSize);
        Runnable refreshKbdTile = () -> applyToggleTileStyle(
                btnKeyboard, host.isPcKeyboardVisible(), btnKeyboard.isFocused());
        refreshKbdTile.run();
        attachFocusRing(btnKeyboard, refreshKbdTile);
        btnKeyboard.setEnabled(canInput);
        if (!canInput) btnKeyboard.setAlpha(0.32f);
        btnKeyboard.setOnClickListener(v -> {
            animateTileTap(v);
            runItemAndDismiss(StreamMenu.Item.PC_KEYBOARD);
        });

        // 6. Performance Stats Toggle
        ImageButton btnPerf = createSquircleIconButton(
                activity, R.drawable.ic_qm_perf_stats, "Performance Stats", tileSize);
        refreshPerfTile = () -> {
            StreamMenu.Options cur = optionsSupplier.get();
            boolean shown = cur != null && cur.overlayShown;
            applyToggleTileStyle(btnPerf, shown, btnPerf.isFocused());
        };
        refreshPerfTile.run();
        attachFocusRing(btnPerf, refreshPerfTile);
        btnPerf.setOnClickListener(v -> {
            animateTileTap(v);
            host.togglePerfOverlaySessionOnly();
            refreshPerfTile.run();
        });

        // 7. Upload Clipboard (Send to host)
        ImageButton btnUploadClip = createSquircleIconButton(
                activity, R.drawable.ic_qm_clipboard_upload, "Upload Clipboard", tileSize);
        Runnable refreshUploadTile = () -> applyActionTileStyle(btnUploadClip, false, btnUploadClip.isFocused());
        refreshUploadTile.run();
        attachFocusRing(btnUploadClip, refreshUploadTile);
        btnUploadClip.setOnClickListener(v -> {
            animateTileTap(v);
            flashActionTile(btnUploadClip, refreshUploadTile);
            host.sendClipboardLive();
        });

        // 8. Fetch Clipboard (Get from host)
        ImageButton btnFetchClip = createSquircleIconButton(
                activity, R.drawable.ic_qm_clipboard_fetch, "Fetch Clipboard", tileSize);
        Runnable refreshFetchTile = () -> applyActionTileStyle(btnFetchClip, false, btnFetchClip.isFocused());
        refreshFetchTile.run();
        attachFocusRing(btnFetchClip, refreshFetchTile);
        btnFetchClip.setOnClickListener(v -> {
            animateTileTap(v);
            flashActionTile(btnFetchClip, refreshFetchTile);
            host.fetchClipboardLive();
        });

        addTileToRow(row1, btnDisconnect, tileSize, 0);
        addTileToRow(row1, btnQuit, tileSize, tileGap);
        addTileToRow(row1, btnMouseMode, tileSize, tileGap);
        addTileToRow(row1, btnOsc, tileSize, tileGap);

        addTileToRow(row2, btnKeyboard, tileSize, 0);
        addTileToRow(row2, btnPerf, tileSize, tileGap);
        addTileToRow(row2, btnUploadClip, tileSize, tileGap);
        addTileToRow(row2, btnFetchClip, tileSize, tileGap);

        rootColumn.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = tileGap;
        rootColumn.addView(row2, row2Lp);

        // 9. Centered Settings Pill below the 4x2 grid
        final int pillWidth = dp(96f);
        final int pillHeight = dp(36f);
        final FrameLayout btnSettingsPill = new FrameLayout(activity);
        btnSettingsPill.setFocusable(true);
        btnSettingsPill.setClickable(true);
        setTooltipAndDesc(btnSettingsPill, "Quick Settings");

        final ImageView settingsIconView = new ImageView(activity);
        settingsIconView.setImageResource(R.drawable.ic_settings);
        settingsIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int gearSize = dp(20f);
        FrameLayout.LayoutParams gearLp = new FrameLayout.LayoutParams(gearSize, gearSize);
        gearLp.gravity = Gravity.CENTER;
        btnSettingsPill.addView(settingsIconView, gearLp);

        final Runnable refreshSettingsPill = () -> applySettingsPillStyle(
                btnSettingsPill, settingsIconView, drawerOpen, btnSettingsPill.isFocused());
        refreshSettingsPill.run();
        attachFocusRing(btnSettingsPill, refreshSettingsPill);

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(pillWidth, pillHeight);
        pillLp.topMargin = dp(12f);
        pillLp.gravity = Gravity.CENTER_HORIZONTAL;
        rootColumn.addView(btnSettingsPill, pillLp);

        quickMenuCard.addView(rootColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams quickCardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        quickCardLp.gravity = Gravity.CENTER;
        overlayRoot.addView(quickMenuCard, quickCardLp);

        // --- Switchable Left/Right Scrollable Live-Blur Side Settings Drawer ---
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final int drawerMargin = dp(12f);
        final int drawerWidth = Math.max(dp(256f), Math.min(dp(304f), Math.round(dm.widthPixels * 0.42f)));

        sideDrawerCard = new FrostedStreamCardLayout(activity, palette, blurController, cardRadius);
        sideDrawerCard.setClickable(true);
        sideDrawerCard.setVisibility(View.GONE);
        sideDrawerCard.setAlpha(0f);
        sideDrawerCard.setTranslationX((drawerOnRight ? 1f : -1f) * (drawerWidth + dp(24f)));

        final FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        drawerLp.gravity = (drawerOnRight ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        drawerLp.setMargins(drawerMargin, drawerMargin, drawerMargin, drawerMargin);

        final Runnable updateQuickMenuShift = () -> {
            float targetShiftX = 0f;
            if (drawerOpen) {
                int screenW = overlayRoot.getWidth() > 0 ? overlayRoot.getWidth() : dm.widthPixels;
                int quickW = quickMenuCard.getWidth() > 0 ? quickMenuCard.getWidth() : dp(304f);
                float defaultLeft = (screenW - quickW) * 0.5f;
                float minClearLeft = drawerMargin + drawerWidth + dp(12f);
                if (defaultLeft < minClearLeft) {
                    float maxShift = Math.max(0f, screenW - dp(10f) - (defaultLeft + quickW));
                    float shiftMag = Math.min(minClearLeft - defaultLeft, maxShift);
                    targetShiftX = drawerOnRight ? -shiftMag : shiftMag;
                }
            }
            quickMenuCard.animate().cancel();
            quickMenuCard.animate()
                    .translationX(targetShiftX)
                    .setDuration(240L)
                    .setInterpolator(SPRING_EASE)
                    .setUpdateListener(a -> quickMenuCard.invalidate())
                    .start();
        };

        Runnable setDrawerVisibility = () -> {
            boolean open = drawerOpen;
            float hiddenOffset = (drawerOnRight ? 1f : -1f) * (drawerWidth + dp(24f));
            refreshSettingsPill.run();
            settingsIconView.animate().cancel();
            settingsIconView.animate()
                    .rotation(open ? 60f : 0f)
                    .setDuration(240L)
                    .setInterpolator(SPRING_EASE)
                    .start();

            if (open) {
                blurController.setDrawerAnimating(true);
                sideDrawerCard.setVisibility(View.VISIBLE);
                sideDrawerCard.animate().cancel();
                sideDrawerCard.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(240L)
                        .setInterpolator(SPRING_EASE)
                        .setUpdateListener(a -> sideDrawerCard.invalidate())
                        .withEndAction(() -> blurController.setDrawerAnimating(false))
                        .start();
            } else {
                blurController.setDrawerAnimating(true);
                sideDrawerCard.animate().cancel();
                sideDrawerCard.animate()
                        .translationX(hiddenOffset)
                        .alpha(0f)
                        .setDuration(190L)
                        .setInterpolator(SPRING_EASE)
                        .setUpdateListener(a -> sideDrawerCard.invalidate())
                        .withEndAction(() -> {
                            blurController.setDrawerAnimating(false);
                            if (!drawerOpen) {
                                sideDrawerCard.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
            updateQuickMenuShift.run();
        };

        Runnable swapDrawerSide = () -> {
            drawerOnRight = !drawerOnRight;
            prefs.edit().putBoolean(PREF_DRAWER_ON_RIGHT, drawerOnRight).apply();

            blurController.setDrawerAnimating(true);
            sideDrawerCard.animate().cancel();
            drawerLp.gravity = (drawerOnRight ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
            sideDrawerCard.setLayoutParams(drawerLp);

            float enterOffset = (drawerOnRight ? 1f : -1f) * dp(44f);
            sideDrawerCard.setTranslationX(enterOffset);
            sideDrawerCard.setAlpha(0.35f);
            sideDrawerCard.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(240L)
                    .setInterpolator(SPRING_EASE)
                    .setUpdateListener(a -> sideDrawerCard.invalidate())
                    .withEndAction(() -> blurController.setDrawerAnimating(false))
                    .start();

            updateQuickMenuShift.run();
        };

        Runnable refreshCardOutlines = () -> {
            quickMenuCard.refreshRimOutline();
            sideDrawerCard.refreshRimOutline();
        };

        closeDrawerAction = () -> {
            if (drawerOpen) {
                drawerOpen = false;
                setDrawerVisibility.run();
            }
        };

        View drawerContent = buildSideSettingsDrawerContent(
                activity, opts, closeDrawerAction, swapDrawerSide, refreshCardOutlines);
        sideDrawerCard.addView(drawerContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlayRoot.addView(sideDrawerCard, drawerLp);

        btnSettingsPill.setOnClickListener(v -> {
            animateTileTap(v);
            drawerOpen = !drawerOpen;
            if (drawerOpen) {
                sideDrawerCard.setTranslationX((drawerOnRight ? 1f : -1f) * (drawerWidth + dp(24f)));
            }
            setDrawerVisibility.run();
        });

        overlayRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) && drawerOpen) {
                overlayRoot.post(updateQuickMenuShift);
            }
        });

        dialog.setContentView(overlayRoot);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.FILL);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0f;
            lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            window.setAttributes(lp);
            View decor = window.getDecorView();
            decor.setPadding(0, 0, 0, 0);
            decor.setBackground(new ColorDrawable(Color.TRANSPARENT));
            decor.setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        }

        quickMenuCard.setAlpha(0f);
        quickMenuCard.setScaleX(0.93f);
        quickMenuCard.setScaleY(0.93f);

        dialog.setOnShowListener(d -> {
            blurController.registerTarget(quickMenuCard);
            blurController.registerTarget(sideDrawerCard);
            blurController.start();
            scrimView.animate()
                    .alpha(1f)
                    .setDuration(180L)
                    .setInterpolator(SPRING_EASE)
                    .start();
            quickMenuCard.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(210L)
                    .setInterpolator(SPRING_EASE)
                    .setUpdateListener(a -> quickMenuCard.invalidate())
                    .start();
        });
    }

    private View buildSideSettingsDrawerContent(
            Context ctx,
            @Nullable StreamMenu.Options opts,
            Runnable onCloseDrawer,
            Runnable onSwapSide,
            Runnable onCardOutlineChanged) {
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);

        // Fixed Header Bar with Swap-Side (⇄) and Close (✕) buttons
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16f), dp(14f), dp(12f), dp(12f));

        TextView titleView = new TextView(ctx);
        titleView.setText("Quick Settings");
        titleView.setTextColor(palette.getPrimaryTextColor());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView swapBtn = createDrawerHeaderIconButton(ctx, "\u21C4", "Move Drawer Side");
        swapBtn.setOnClickListener(v -> {
            animateTileTap(v);
            onSwapSide.run();
        });
        LinearLayout.LayoutParams swapLp = new LinearLayout.LayoutParams(dp(28f), dp(28f));
        swapLp.setMarginEnd(dp(6f));
        header.addView(swapBtn, swapLp);

        TextView closeBtn = createDrawerHeaderIconButton(ctx, "\u2715", "Close");
        closeBtn.setOnClickListener(v -> {
            animateTileTap(v);
            onCloseDrawer.run();
        });
        header.addView(closeBtn, new LinearLayout.LayoutParams(dp(28f), dp(28f)));

        container.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(ctx);
        divider.setBackgroundColor(palette.getDividerColor());
        container.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1f))));

        // Scrollable Accordion Settings Body (all categories start collapsed, matching Moonlight)
        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12f), dp(10f), dp(12f), dp(18f));

        final List<AccordionSection> sections = new ArrayList<>();

        // 1. Stream Quality (Nightjar's added section)
        LinearLayout qualityContent = addAccordionCategory(body, ctx, "Stream Quality", sections);
        if (opts != null && opts.bitrateKbps > 0 && opts.bitrateChange != null) {
            addBitrateSliderRow(qualityContent, ctx, opts);
        }
        if (opts != null && (opts.rateChangeBitrate || opts.automaticQuality || opts.automaticBitrateOn)) {
            addDrawerToggleRow(qualityContent, ctx, "Automatic Bitrate", opts.automaticBitrateOn, enabled -> {
                onItem.accept(StreamMenu.Item.AUTOMATIC_BITRATE);
            });
        }
        if (opts != null && opts.rateChange) {
            addDrawerActionButton(qualityContent, ctx, "Bitrate & Frame Rate", () ->
                    runItemAndDismiss(StreamMenu.Item.RATE_CHANGE));
        } else {
            addDrawerActionButton(qualityContent, ctx, "Manual Bitrate", () ->
                    runItemAndDismiss(StreamMenu.Item.BITRATE));
        }
        if (opts != null && opts.automaticQuality) {
            addDrawerActionButton(qualityContent, ctx, "Automatic Quality", () ->
                    runItemAndDismiss(StreamMenu.Item.AUTOMATIC_QUALITY));
        }
        addDrawerActionButton(qualityContent, ctx, "Stream Health & Diagnostics", () ->
                runItemAndDismiss(StreamMenu.Item.STREAM_HEALTH));

        // 2. On-Screen Controller Category (hidden on external display)
        if (opts == null || opts.localOutput) {
            LinearLayout oscContent = addAccordionCategory(body, ctx, "On-Screen Controller", sections);

            addDrawerSliderRow(oscContent, ctx, "Opacity", 0, 100, 1, cfg.oscOpacity, "%",
                    host::setOscOpacityLive);

            addDrawerSliderRow(oscContent, ctx, "Outline Thickness", 0, 250, 5, cfg.oscOutlineThicknessPercent, "%",
                    host::setOscOutlineThicknessLive);

            addDrawerToggleRow(oscContent, ctx, "Vibrate Controls", cfg.vibrateOsc,
                    host::setOscVibrateLive);

            addDrawerToggleRow(oscContent, ctx, "Free Analog Stick", cfg.enableNewAnalogStick,
                    host::setFreeAnalogStickLive);

            final View[] oscBlurAmountRowRef = new View[1];
            final View[] oscBlurResRowRef = new View[1];
            final View[] oscBlurFpsRowRef = new View[1];
            addDrawerToggleRow(oscContent, ctx, "OSC Live Blur", cfg.oscLiveBlur, enabled -> {
                host.setOscLiveBlurLive(enabled);
                if (oscBlurAmountRowRef[0] != null) setRowEnabledState(oscBlurAmountRowRef[0], enabled);
                if (oscBlurResRowRef[0] != null) setRowEnabledState(oscBlurResRowRef[0], enabled);
                if (oscBlurFpsRowRef[0] != null) setRowEnabledState(oscBlurFpsRowRef[0], enabled);
            });
            oscBlurAmountRowRef[0] = addDrawerSliderRow(oscContent, ctx, "OSC Blur Amount",
                    0, 100, 1, cfg.oscBlurAmountPercent, "%", host::setOscBlurAmountLive);
            oscBlurResRowRef[0] = addDrawerSliderRow(oscContent, ctx, "OSC Blur Resolution",
                    5, 50, 1, cfg.oscBlurResolutionPercent, "%", host::setOscBlurResolutionPercentLive);
            oscBlurFpsRowRef[0] = addDrawerSegmentedRow(oscContent, ctx, "OSC Blur Framerate",
                    new String[]{"30", "60", "90", "120", "max"},
                    new String[]{"30", "60", "90", "120", "Max"},
                    cfg.oscBlurFramerate, host::setOscBlurFramerateLive);
            setRowEnabledState(oscBlurAmountRowRef[0], cfg.oscLiveBlur);
            setRowEnabledState(oscBlurResRowRef[0], cfg.oscLiveBlur);
            setRowEnabledState(oscBlurFpsRowRef[0], cfg.oscLiveBlur);

            addDrawerSegmentedRow(oscContent, ctx, "OSC Engine",
                    new String[]{PreferenceConfiguration.OSC_ENGINE_MOONLIGHT, PreferenceConfiguration.OSC_ENGINE_NIGHTJAR},
                    new String[]{"Moonlight", "Nightjar"},
                    host.getOscEngine(), engine -> {
                        host.setOscEngineLive(engine);
                        if (refreshOscTile != null) refreshOscTile.run();
                    });

            addDrawerActionButton(oscContent, ctx, "Edit OSC Layout", () ->
                    runItemAndDismiss(StreamMenu.Item.CONTROLS_LAYOUT));
        }

        // 3. Touch & Trackpad Category
        if (opts == null || opts.canSendInput) {
            LinearLayout touchContent = addAccordionCategory(body, ctx, "Touch & Trackpad", sections);

            addDrawerSliderRow(touchContent, ctx, "Trackpad Sensitivity X",
                    10, 300, 10, cfg.trackpadSensitivityX, "%", host::setTrackpadSensitivityXLive);
            addDrawerSliderRow(touchContent, ctx, "Trackpad Sensitivity Y",
                    10, 300, 10, cfg.trackpadSensitivityY, "%", host::setTrackpadSensitivityYLive);
            addDrawerToggleRow(touchContent, ctx, "Swap Trackpad Axis", cfg.trackpadSwapAxis,
                    host::setTrackpadSwapAxisLive);
            if (opts != null && opts.mouseConnected) {
                addDrawerToggleRow(touchContent, ctx, "Show Local Mouse Cursor", opts.cursorVisible,
                        enabled -> runItemAndDismiss(StreamMenu.Item.MOUSE_CURSOR));
            }
            addDrawerToggleRow(touchContent, ctx, "Special Keyboard Keys",
                    opts != null && opts.keyboardKeysShown,
                    enabled -> runItemAndDismiss(StreamMenu.Item.KEYBOARD_KEYS));
            addDrawerActionButton(touchContent, ctx, "Select Touch Mode…", () ->
                    runItemAndDismiss(StreamMenu.Item.TOUCH_MODE));
        }

        // 4. Display & View Category
        LinearLayout displayContent = addAccordionCategory(body, ctx, "Display & View", sections);
        if (opts == null || opts.scalingAvailable) {
            addDrawerActionButton(displayContent, ctx, "Video Scaling Mode", () ->
                    runItemAndDismiss(StreamMenu.Item.VIDEO_SCALING));
        }
        addDrawerToggleRow(displayContent, ctx, "Pan & Zoom Mode",
                opts != null && opts.zoomEnabled,
                enabled -> runItemAndDismiss(StreamMenu.Item.ZOOM));
        if (opts != null && opts.pictureInPicture) {
            addDrawerActionButton(displayContent, ctx, "Picture-in-Picture", () ->
                    runItemAndDismiss(StreamMenu.Item.PICTURE_IN_PICTURE));
        }
        if (opts != null && opts.externalDisplays) {
            addDrawerActionButton(displayContent, ctx, "Video Output", () ->
                    runItemAndDismiss(StreamMenu.Item.VIDEO_OUTPUT));
        }
        addDrawerActionButton(displayContent, ctx, "Reconnect Stream", () ->
                runItemAndDismiss(StreamMenu.Item.RECONNECT));

        // 5. Overlay & UI Category
        LinearLayout overlayContent = addAccordionCategory(body, ctx, "Overlay & UI", sections);
        addDrawerSegmentedRow(overlayContent, ctx, "UI Performance Mode",
                new String[]{"off", "on"},
                new String[]{"Off", "On"},
                cfg.uiPerformanceMode ? "on" : "off",
                val -> host.setUiPerformanceModeLive("on".equals(val)));

        addDrawerSegmentedRow(overlayContent, ctx, "UI Battery Saver",
                new String[]{"off", "auto", "on"},
                new String[]{"Off", "Auto", "On"},
                cfg.uiBatterySaverMode,
                mode -> {
                    host.setUiBatterySaverModeLive(mode);
                    GlassSpec.invalidateCache();
                    blurController.onBatterySaverChanged();
                });

        addDrawerSliderRow(overlayContent, ctx, "Card Outline Thickness",
                0, 250, 5, cfg.cardOutlineThicknessPercent, "%", val -> {
                    host.setCardOutlineThicknessLive(val);
                    GlassSpec.invalidateCache();
                    onCardOutlineChanged.run();
                });

        String activeOverlay = (opts != null && opts.overlayShown) ? opts.overlayMode : "off";
        addDrawerSegmentedRow(overlayContent, ctx, "Performance Overlay",
                new String[]{"off", PerformanceOverlay.MODE_COMPACT, PerformanceOverlay.MODE_STATISTICS, PerformanceOverlay.MODE_DEBUG},
                new String[]{"Off", "Mini", "Stats", "Debug"},
                activeOverlay,
                mode -> {
                    host.setPerformanceOverlayModeLive("off".equals(mode) ? null : mode);
                    if (refreshPerfTile != null) refreshPerfTile.run();
                });

        if (opts == null || opts.menuButtonAvailable) {
            addDrawerToggleRow(overlayContent, ctx, "Floating Menu Button",
                    opts != null && opts.menuButtonShown,
                    host::setFloatingMenuButtonLive);
        }
        addDrawerToggleRow(overlayContent, ctx, "Disable Warnings", cfg.disableWarnings,
                host::setDisableWarningsLive);

        if (onOpenFullMenu != null) {
            addDrawerActionButton(overlayContent, ctx, "More Nightjar Options…", () -> {
                dismiss();
                onOpenFullMenu.run();
            });
        }

        scrollView.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return container;
    }

    private void addBitrateSliderRow(LinearLayout parent, Context ctx, StreamMenu.Options opts) {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(4f), dp(5f), dp(2f), dp(6f));

        LinearLayout topLine = new LinearLayout(ctx);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(ctx);
        labelView.setText("Stream Bitrate");
        labelView.setTextColor(palette.getSecondaryTextColor());
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        topLine.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bitrateValueView = new TextView(ctx);
        bitrateValueView.setText(formatBitrate(opts.bitrateKbps));
        bitrateValueView.setTextColor(palette.getNeonAccent());
        bitrateValueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        bitrateValueView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valLp.setMarginStart(dp(8f));
        topLine.addView(bitrateValueView, valLp);
        block.addView(topLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bitrateSeekBar = new SeekBar(ctx);
        bitrateSeekBar.setMax((MAX_BITRATE_KBPS - MIN_BITRATE_KBPS) / 500);
        int clamped = Math.max(MIN_BITRATE_KBPS, Math.min(MAX_BITRATE_KBPS, opts.bitrateKbps));
        bitrateSeekBar.setProgress((clamped - MIN_BITRATE_KBPS) / 500);
        bitrateSeekBar.setEnabled(!opts.bitrateWaiting);
        bitrateSeekBar.setProgressTintList(ColorStateList.valueOf(palette.highlight));
        bitrateSeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(palette.getCardRimColor()));
        bitrateSeekBar.setThumbTintList(ColorStateList.valueOf(palette.getNeonAccent()));
        bitrateSeekBar.setPadding(dp(8f), dp(4f), dp(8f), dp(2f));
        bitrateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int kbps = MIN_BITRATE_KBPS + progress * 500;
                if (bitrateValueView != null) bitrateValueView.setText(formatBitrate(kbps));
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int kbps = MIN_BITRATE_KBPS + sb.getProgress() * 500;
                int applied = opts.bitrateChange.applyAsInt(kbps);
                if (applied > 0 && bitrateValueView != null) {
                    bitrateValueView.setText(formatBitrate(applied));
                }
            }
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(2f);
        block.addView(bitrateSeekBar, sbLp);
        parent.addView(block, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView createDrawerHeaderIconButton(Context ctx, String symbol, String contentDesc) {
        TextView btn = new TextView(ctx);
        btn.setText(symbol);
        btn.setTextColor(palette.getPrimaryTextColor());
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btn.setGravity(Gravity.CENTER);
        btn.setFocusable(true);
        btn.setClickable(true);
        setTooltipAndDesc(btn, contentDesc);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(AppThemePalette.withAlpha(palette.surface, palette.isLight ? 0.68f : 0.58f));
        bg.setStroke(Math.max(1, dp(1f)), palette.getCardRimColor());
        btn.setBackground(bg);
        return btn;
    }

    private LinearLayout addAccordionCategory(
            LinearLayout parent, Context ctx, String title, List<AccordionSection> allSections) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dp(16f));
        cardBg.setColor(AppThemePalette.withAlpha(palette.surface, palette.isLight ? 0.65f : 0.55f));
        cardBg.setStroke(Math.max(1, dp(1f)), palette.getDividerColor());
        card.setBackground(cardBg);

        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(12f), dp(11f), dp(12f), dp(11f));
        headerRow.setFocusable(true);
        headerRow.setClickable(true);

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextColor(palette.getPrimaryTextColor());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(ctx);
        chevron.setText("\u25BE");
        chevron.setTextColor(palette.getNeonAccent());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        chevron.setGravity(Gravity.CENTER);
        headerRow.addView(chevron, new LinearLayout.LayoutParams(dp(20f), dp(20f)));

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10f), dp(2f), dp(10f), dp(10f));
        content.setVisibility(View.GONE);

        final AccordionSection section = new AccordionSection(content, chevron);
        allSections.add(section);

        headerRow.setOnClickListener(v -> {
            boolean willExpand = !section.expanded;
            for (int i = 0; i < allSections.size(); i++) {
                AccordionSection other = allSections.get(i);
                if (other != section && other.expanded) {
                    other.expanded = false;
                    other.content.setVisibility(View.GONE);
                    other.chevron.animate().cancel();
                    other.chevron.animate()
                            .rotation(0f)
                            .setDuration(180L)
                            .setInterpolator(SPRING_EASE)
                            .start();
                }
            }
            section.expanded = willExpand;
            chevron.animate().cancel();
            chevron.animate()
                    .rotation(willExpand ? 180f : 0f)
                    .setDuration(200L)
                    .setInterpolator(SPRING_EASE)
                    .start();
            if (willExpand) {
                content.setVisibility(View.VISIBLE);
                content.setAlpha(0f);
                content.animate().cancel();
                content.animate()
                        .alpha(1f)
                        .setDuration(170L)
                        .setInterpolator(SPRING_EASE)
                        .start();
            } else {
                content.animate().cancel();
                content.setVisibility(View.GONE);
            }
        });

        card.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (allSections.size() > 1) {
            cardLp.topMargin = dp(8f);
        }
        parent.addView(card, cardLp);
        return content;
    }

    private View addDrawerSegmentedRow(
            LinearLayout parent, Context ctx, String labelText,
            String[] values, String[] labels, String initialValue,
            Consumer<String> onSelected) {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(4f), dp(5f), dp(2f), dp(7f));

        TextView label = new TextView(ctx);
        label.setText(labelText);
        label.setTextColor(palette.getSecondaryTextColor());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        block.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout capsule = new LinearLayout(ctx);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setPadding(dp(3f), dp(3f), dp(3f), dp(3f));
        GradientDrawable capsuleBg = new GradientDrawable();
        capsuleBg.setShape(GradientDrawable.RECTANGLE);
        capsuleBg.setCornerRadius(dp(13f));
        capsuleBg.setColor(AppThemePalette.withAlpha(palette.surface, palette.isLight ? 0.65f : 0.55f));
        capsuleBg.setStroke(Math.max(1, dp(1f)), palette.getDividerColor());
        capsule.setBackground(capsuleBg);

        final TextView[] tabs = new TextView[values.length];
        final String[] selected = { initialValue != null ? initialValue : values[0] };

        Runnable refreshTabs = () -> {
            for (int i = 0; i < values.length; i++) {
                boolean active = values[i].equalsIgnoreCase(selected[0]);
                TextView tab = tabs[i];
                if (active) {
                    GradientDrawable tabBg = new GradientDrawable();
                    tabBg.setShape(GradientDrawable.RECTANGLE);
                    tabBg.setCornerRadius(dp(10f));
                    tabBg.setColor(AppThemePalette.withAlpha(palette.highlight, palette.isLight ? 0.24f : 0.34f));
                    tabBg.setStroke(Math.max(1, dp(1.1f)), palette.getNeonAccent());
                    tab.setBackground(tabBg);
                    tab.setTextColor(palette.getPrimaryTextColor());
                    tab.setTypeface(Typeface.DEFAULT_BOLD);
                } else {
                    tab.setBackground(null);
                    tab.setTextColor(palette.getSecondaryTextColor());
                    tab.setTypeface(Typeface.DEFAULT);
                }
            }
        };

        for (int i = 0; i < values.length; i++) {
            final String val = values[i];
            TextView tab = new TextView(ctx);
            tab.setText(labels[i]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(2f), dp(6f), dp(2f), dp(6f));
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setOnClickListener(v -> {
                selected[0] = val;
                refreshTabs.run();
                onSelected.accept(val);
            });
            tabs[i] = tab;
            capsule.addView(tab, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        refreshTabs.run();

        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.topMargin = dp(6f);
        block.addView(capsule, capLp);

        parent.addView(block, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return block;
    }

    private View addDrawerToggleRow(
            LinearLayout parent, Context ctx, String label,
            boolean initialState, BooleanConsumer onToggle) {
        final boolean[] checked = { initialState };

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4f), dp(7f), dp(2f), dp(7f));
        row.setFocusable(true);
        row.setClickable(true);

        TextView text = new TextView(ctx);
        text.setText(label);
        text.setTextColor(palette.getPrimaryTextColor());
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginEnd(dp(10f));
        row.addView(text, textLp);

        final int trackW = dp(40f);
        final int trackH = dp(22f);
        final int thumbSize = dp(16f);
        final int thumbMargin = dp(3f);
        final float travelPx = trackW - thumbSize - thumbMargin * 2f;

        FrameLayout switchTrack = new FrameLayout(ctx);
        View thumb = new View(ctx);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(Color.WHITE);
        thumb.setBackground(thumbBg);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(thumbSize, thumbSize);
        thumbLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        thumbLp.setMarginStart(thumbMargin);
        switchTrack.addView(thumb, thumbLp);

        Runnable applyVisual = () -> {
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setShape(GradientDrawable.RECTANGLE);
            trackBg.setCornerRadius(trackH * 0.5f);
            if (checked[0]) {
                trackBg.setColor(AppThemePalette.withAlpha(palette.highlight, 0.85f));
                trackBg.setStroke(Math.max(1, dp(1f)), palette.getCardRimColor());
            } else {
                trackBg.setColor(AppThemePalette.withAlpha(palette.cardOutline, palette.isLight ? 0.25f : 0.20f));
                trackBg.setStroke(Math.max(1, dp(1f)), palette.getDividerColor());
            }
            switchTrack.setBackground(trackBg);
            thumb.setAlpha(checked[0] ? 1f : 0.85f);
        };
        applyVisual.run();
        thumb.setTranslationX(checked[0] ? travelPx : 0f);

        row.addView(switchTrack, new LinearLayout.LayoutParams(trackW, trackH));

        row.setOnClickListener(v -> {
            checked[0] = !checked[0];
            applyVisual.run();
            thumb.animate().cancel();
            thumb.animate()
                    .translationX(checked[0] ? travelPx : 0f)
                    .setDuration(170L)
                    .setInterpolator(SPRING_EASE)
                    .start();
            onToggle.accept(checked[0]);
        });

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View addDrawerSliderRow(
            LinearLayout parent, Context ctx, String label,
            int minVal, int maxVal, int step, int initialVal,
            String suffix, IntConsumer onChanged) {
        final int clampedInit = Math.max(minVal, Math.min(maxVal, initialVal));
        final int safeStep = Math.max(1, step);
        final int stepsCount = Math.max(1, (maxVal - minVal) / safeStep);

        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(4f), dp(5f), dp(2f), dp(6f));

        LinearLayout topLine = new LinearLayout(ctx);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextColor(palette.getSecondaryTextColor());
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        labelView.setMaxLines(1);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        topLine.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = new TextView(ctx);
        valueView.setText(clampedInit + suffix);
        valueView.setTextColor(palette.getNeonAccent());
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valLp.setMarginStart(dp(8f));
        topLine.addView(valueView, valLp);

        block.addView(topLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar seekBar = new SeekBar(ctx);
        seekBar.setMax(stepsCount);
        seekBar.setProgress(Math.max(0, Math.min(stepsCount, Math.round((clampedInit - minVal) / (float) safeStep))));
        seekBar.setProgressTintList(ColorStateList.valueOf(palette.highlight));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(palette.getCardRimColor()));
        seekBar.setThumbTintList(ColorStateList.valueOf(palette.getNeonAccent()));
        seekBar.setPadding(dp(8f), dp(4f), dp(8f), dp(2f));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = Math.min(maxVal, minVal + progress * safeStep);
                valueView.setText(val + suffix);
                if (fromUser) {
                    onChanged.accept(val);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(2f);
        block.addView(seekBar, sbLp);

        parent.addView(block, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return block;
    }

    private void addDrawerActionButton(LinearLayout parent, Context ctx, String label, Runnable onClick) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextColor(palette.getPrimaryTextColor());
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(12f), dp(9f), dp(12f), dp(9f));
        btn.setFocusable(true);
        btn.setClickable(true);

        Runnable applyStyle = () -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(14f));
            boolean focused = btn.isFocused();
            bg.setColor(AppThemePalette.withAlpha(palette.surface, focused ? 0.80f : 0.62f));
            bg.setStroke(Math.max(1, dp(focused ? 1.6f : 1.1f)),
                    focused ? palette.getPrimaryTextColor() : palette.getCardRimColor());
            btn.setBackground(bg);
        };
        applyStyle.run();
        attachFocusRing(btn, applyStyle);
        btn.setOnClickListener(v -> {
            animateTileTap(v);
            onClick.run();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6f);
        lp.bottomMargin = dp(2f);
        parent.addView(btn, lp);
    }

    private void setRowEnabledState(View row, boolean enabled) {
        row.setAlpha(enabled ? 1f : 0.38f);
        setViewTreeEnabled(row, enabled);
    }

    private void setViewTreeEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setViewTreeEnabled(vg.getChildAt(i), enabled);
            }
        }
    }

    private void applySettingsPillStyle(View pill, ImageView icon, boolean isOpen, boolean focused) {
        int fg = palette.getPrimaryTextColor();
        int fgMuted = palette.getSecondaryTextColor();
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(999f));
        if (isOpen) {
            bg.setColor(AppThemePalette.withAlpha(palette.surface, focused ? 0.85f : 0.72f));
            bg.setStroke(Math.max(1, dp(focused ? 2.0f : 1.5f)),
                    focused ? fg : AppThemePalette.withAlpha(fg, 0.88f));
            pill.setBackground(bg);
            icon.setColorFilter(fg, PorterDuff.Mode.SRC_IN);
        } else {
            bg.setColor(AppThemePalette.withAlpha(palette.surface, focused ? 0.75f : 0.55f));
            bg.setStroke(Math.max(1, dp(focused ? 1.8f : 1.15f)),
                    focused ? fg : palette.getCardRimColor());
            pill.setBackground(bg);
            icon.setColorFilter(focused ? fg : fgMuted, PorterDuff.Mode.SRC_IN);
        }
    }

    private ImageButton createSquircleIconButton(Context ctx, int iconRes, String contentDesc, int tileSizePx) {
        ImageButton btn = new ImageButton(ctx);
        btn.setImageResource(iconRes);
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int iconPad = dp(15f);
        btn.setPadding(iconPad, iconPad, iconPad, iconPad);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setMinimumWidth(tileSizePx);
        btn.setMinimumHeight(tileSizePx);
        setTooltipAndDesc(btn, contentDesc);
        return btn;
    }

    private void setTooltipAndDesc(View v, String text) {
        v.setContentDescription(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.setTooltipText(text);
        }
    }

    private void addTileToRow(LinearLayout row, View tile, int tileSizePx, int marginStartPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileSizePx, tileSizePx);
        lp.setMarginStart(marginStartPx);
        row.addView(tile, lp);
    }

    private static int resolveTouchModeIcon(int mode) {
        switch (mode) {
            case TouchMode.MULTI_TOUCH:
                return R.drawable.ic_qm_mouse_multitouch;
            case TouchMode.TOUCHPAD:
            case TouchMode.LOCAL_CURSOR:
                return R.drawable.ic_qm_mouse_trackpad_natural;
            case TouchMode.TRACKPAD:
                return R.drawable.ic_qm_mouse_trackpad_gaming;
            case TouchMode.DIRECT:
            case TouchMode.OFF:
            default:
                return R.drawable.ic_qm_mouse_absolute;
        }
    }

    private void applyNeonTileStyle(ImageButton btn, int neonRgb, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(17f));
        bg.setColor(AppThemePalette.withAlpha(neonRgb, focused ? 0.28f : 0.16f));
        bg.setStroke(Math.max(1, dp(focused ? 2.2f : 1.6f)),
                AppThemePalette.withAlpha(neonRgb, focused ? 1.0f : 0.92f));
        btn.setBackground(bg);
        btn.setColorFilter(neonRgb, PorterDuff.Mode.SRC_IN);
    }

    private void applyToggleTileStyle(ImageButton btn, boolean isOn, boolean focused) {
        int fg = palette.getPrimaryTextColor();
        int fgMuted = palette.getSecondaryTextColor();
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(17f));
        if (isOn) {
            bg.setColor(AppThemePalette.withAlpha(palette.surface, focused ? 0.85f : 0.72f));
            int strokeColor = focused ? fg : AppThemePalette.withAlpha(fg, 0.88f);
            bg.setStroke(Math.max(1, dp(focused ? 2.1f : 1.6f)), strokeColor);
            btn.setBackground(bg);
            btn.setColorFilter(fg, PorterDuff.Mode.SRC_IN);
        } else {
            bg.setColor(AppThemePalette.withAlpha(palette.surface, focused ? 0.75f : 0.55f));
            int strokeColor = focused ? fg : palette.getCardRimColor();
            bg.setStroke(Math.max(1, dp(focused ? 1.8f : 1.15f)), strokeColor);
            btn.setBackground(bg);
            btn.setColorFilter(focused ? fg : AppThemePalette.withAlpha(fgMuted, 0.72f), PorterDuff.Mode.SRC_IN);
        }
    }

    private void applyActionTileStyle(ImageButton btn, boolean lit, boolean focused) {
        int fg = palette.getPrimaryTextColor();
        int fgMuted = palette.getSecondaryTextColor();
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(17f));
        if (lit) {
            bg.setColor(AppThemePalette.withAlpha(palette.surface, 0.88f));
            bg.setStroke(Math.max(1, dp(1.8f)), fg);
            btn.setBackground(bg);
            btn.setColorFilter(fg, PorterDuff.Mode.SRC_IN);
        } else {
            bg.setColor(AppThemePalette.withAlpha(palette.surface, focused ? 0.75f : 0.55f));
            bg.setStroke(Math.max(1, dp(focused ? 1.9f : 1.25f)), focused ? fg : palette.getCardRimColor());
            btn.setBackground(bg);
            btn.setColorFilter(focused ? fg : fgMuted, PorterDuff.Mode.SRC_IN);
        }
    }

    private void attachFocusRing(View btn, Runnable refreshStyle) {
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            refreshStyle.run();
            v.animate()
                    .scaleX(hasFocus ? 1.06f : 1f)
                    .scaleY(hasFocus ? 1.06f : 1f)
                    .setDuration(140L)
                    .setInterpolator(SPRING_EASE)
                    .start();
        });
    }

    private void animateTileTap(View v) {
        v.animate().cancel();
        final float targetScale = v.isFocused() ? 1.06f : 1f;
        v.animate()
                .scaleX(0.90f)
                .scaleY(0.90f)
                .setDuration(75L)
                .setInterpolator(SPRING_EASE)
                .withEndAction(() -> v.animate()
                        .scaleX(targetScale)
                        .scaleY(targetScale)
                        .setDuration(165L)
                        .setInterpolator(SPRING_EASE)
                        .start())
                .start();
    }

    private void flashActionTile(ImageButton btn, Runnable restoreStyle) {
        applyActionTileStyle(btn, true, btn.isFocused());
        uiHandler.postDelayed(() -> {
            if (!dismissing && dialog.isShowing()) {
                restoreStyle.run();
            }
        }, 260L);
    }

    private void runItemAndDismiss(StreamMenu.Item item) {
        onItem.accept(item);
        dismiss();
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

    /** Single-pass off-UI-thread PixelCopy / TextureView live stream blur shared by Quick Menu and Side Drawer (1:1 Moonlight port). */
    private static final class StreamBlurController {
        private static final long IDLE_CAPTURE_INTERVAL_MS = 25L;
        private static final long ACTIVE_CAPTURE_INTERVAL_MS = 16L;

        private final Activity activity;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private HandlerThread workerThread;
        private Handler workerHandler;
        private final List<FrostedStreamCardLayout> cards = new ArrayList<>();
        private final int[] surfaceLoc = new int[2];
        private final Bitmap[] buffers = new Bitmap[3];
        private int[] scratchPix;
        private int[] scratchTmp;

        private int writeIndex = 0;
        private volatile Bitmap readyBitmap = null;
        private volatile int surfaceScreenLeft = 0;
        private volatile int surfaceScreenTop = 0;
        private volatile int surfaceWidth = 0;
        private volatile int surfaceHeight = 0;

        private volatile boolean running = false;
        private volatile boolean inFlight = false;
        private volatile boolean drawerAnimating = false;
        private volatile boolean batterySaver = false;
        private volatile int blurPct;
        private volatile int blurRadius;
        private volatile int blurSaturationPct;
        private volatile int captureLongEdge;

        private final Runnable invalidateCardsRunnable = () -> {
            for (int i = 0; i < cards.size(); i++) {
                FrostedStreamCardLayout card = cards.get(i);
                if (card.getVisibility() == View.VISIBLE) {
                    card.invalidate();
                }
            }
        };

        private final Runnable captureTick = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                requestFrameCapture();
                long interval = drawerAnimating ? ACTIVE_CAPTURE_INTERVAL_MS : IDLE_CAPTURE_INTERVAL_MS;
                mainHandler.postDelayed(this, interval);
            }
        };

        StreamBlurController(Activity activity) {
            this.activity = activity;
            recomputeFromPrefs();
        }

        void recomputeFromPrefs() {
            AppThemePalette palette = AppThemePalette.getCurrent(activity);
            PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(activity);
            int rawBlurPct = Math.max(0, Math.min(2000, cfg.blurAmountPercent));
            this.blurSaturationPct = Math.max(0, Math.min(500, cfg.blurSaturationPercent));
            this.batterySaver = PreferenceConfiguration.isUiBatterySaverEffective(activity);
            if (batterySaver || rawBlurPct <= 0 || palette.getEffectiveTintAmount() >= 100) {
                this.blurPct = 0;
                this.blurRadius = 0;
                this.captureLongEdge = 160;
            } else {
                this.blurPct = rawBlurPct;
                this.blurRadius = Math.max(1, Math.min(18, Math.round(5f * (rawBlurPct / 100f))));
                this.captureLongEdge = chooseCaptureLongEdge(rawBlurPct);
            }
        }

        private static int chooseCaptureLongEdge(int blurPct) {
            if (blurPct <= 35) return 280;
            if (blurPct <= 85) return 220;
            if (blurPct <= 140) return 160;
            return 120;
        }

        int getBlurSaturationPct() {
            return blurSaturationPct;
        }

        boolean isBatterySaver() {
            return batterySaver;
        }

        void setDrawerAnimating(boolean animating) {
            this.drawerAnimating = animating;
        }

        void onBatterySaverChanged() {
            recomputeFromPrefs();
            if (blurRadius <= 0) {
                stop();
            } else if (!running) {
                start();
            }
            mainHandler.post(invalidateCardsRunnable);
        }

        void registerTarget(FrostedStreamCardLayout card) {
            if (!cards.contains(card)) {
                cards.add(card);
            }
        }

        void start() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || running || blurRadius <= 0) return;
            running = true;
            workerThread = new HandlerThread("StreamBlurThread");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
            mainHandler.removeCallbacks(captureTick);
            mainHandler.post(captureTick);
        }

        void stop() {
            running = false;
            inFlight = false;
            drawerAnimating = false;
            mainHandler.removeCallbacks(captureTick);
            mainHandler.removeCallbacks(invalidateCardsRunnable);
            if (workerHandler != null) {
                workerHandler.removeCallbacksAndMessages(null);
                workerHandler = null;
            }
            if (workerThread != null) {
                workerThread.quitSafely();
                workerThread = null;
            }
            readyBitmap = null;
            scratchPix = null;
            scratchTmp = null;
        }

        @Nullable Bitmap getReadyBitmap() { return readyBitmap; }
        int getSurfaceScreenLeft() { return surfaceScreenLeft; }
        int getSurfaceScreenTop() { return surfaceScreenTop; }
        int getSurfaceWidth() { return surfaceWidth; }
        int getSurfaceHeight() { return surfaceHeight; }

        private void requestFrameCapture() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || inFlight
                    || activity == null || activity.isFinishing()) {
                return;
            }
            Handler bg = workerHandler;
            if (bg == null) return;

            SurfaceView surfaceView = UiHelper.findStreamSurfaceView(activity);
            android.view.TextureView textureView = (surfaceView == null)
                    ? UiHelper.findStreamTextureView(activity) : null;
            boolean hasSurface = surfaceView != null && surfaceView.getWidth() > 0 && surfaceView.getHeight() > 0
                    && surfaceView.getHolder().getSurface().isValid();
            boolean hasTexture = !hasSurface && textureView != null && textureView.isAvailable()
                    && textureView.getWidth() > 0 && textureView.getHeight() > 0;
            if (!hasSurface && !hasTexture) return;

            final View videoView = hasSurface ? surfaceView : textureView;
            final int sw = videoView.getWidth();
            final int sh = videoView.getHeight();
            videoView.getLocationOnScreen(surfaceLoc);
            final int sl = surfaceLoc[0];
            final int st = surfaceLoc[1];

            int bw;
            int bh;
            if (sw >= sh) {
                bw = captureLongEdge;
                bh = Math.max(16, Math.round(captureLongEdge * (sh / (float) sw)));
            } else {
                bh = captureLongEdge;
                bw = Math.max(16, Math.round(captureLongEdge * (sw / (float) sh)));
            }

            if (buffers[0] == null || buffers[0].getWidth() != bw || buffers[0].getHeight() != bh) {
                for (int i = 0; i < buffers.length; i++) {
                    buffers[i] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                }
                readyBitmap = null;
            }
            final int neededPixels = bw * bh;
            if (scratchPix == null || scratchPix.length < neededPixels) {
                scratchPix = new int[neededPixels];
                scratchTmp = new int[neededPixels];
            }

            final int idx = writeIndex;
            final Bitmap target = buffers[idx];
            if (hasTexture) {
                inFlight = true;
                try {
                    textureView.getBitmap(target);
                    bg.post(() -> {
                        if (!running) {
                            inFlight = false;
                            return;
                        }
                        int radius = blurRadius;
                        if (radius > 0) {
                            UiHelper.fastBoxBlur(target, radius, scratchPix, scratchTmp);
                        }
                        surfaceScreenLeft = sl;
                        surfaceScreenTop = st;
                        surfaceWidth = sw;
                        surfaceHeight = sh;
                        readyBitmap = target;
                        writeIndex = (idx + 1) % buffers.length;
                        inFlight = false;
                        mainHandler.post(invalidateCardsRunnable);
                    });
                } catch (Throwable ignored) {
                    inFlight = false;
                }
                return;
            }

            inFlight = true;
            try {
                PixelCopy.request(surfaceView, target, copyResult -> {
                    if (!running || copyResult != PixelCopy.SUCCESS) {
                        inFlight = false;
                        return;
                    }
                    int radius = blurRadius;
                    if (radius > 0) {
                        UiHelper.fastBoxBlur(target, radius, scratchPix, scratchTmp);
                    }
                    surfaceScreenLeft = sl;
                    surfaceScreenTop = st;
                    surfaceWidth = sw;
                    surfaceHeight = sh;
                    readyBitmap = target;
                    writeIndex = (idx + 1) % buffers.length;
                    inFlight = false;
                    mainHandler.post(invalidateCardsRunnable);
                }, bg);
            } catch (IllegalArgumentException | IllegalStateException e) {
                inFlight = false;
            }
        }
    }

    /** Frosted card layout drawing live screen-aligned blurred stream crop + flat One UI tint & rim (1:1 Moonlight port). */
    private static final class FrostedStreamCardLayout extends FrameLayout {
        private final AppThemePalette palette;
        private final StreamBlurController blur;
        private final float cornerRadius;
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF cardBounds = new RectF();
        private final Rect srcRect = new Rect();
        private final RectF dstRect = new RectF();
        private final int[] viewLoc = new int[2];

        FrostedStreamCardLayout(Context context, AppThemePalette palette,
                                StreamBlurController blur, float cornerRadius) {
            super(context);
            this.palette = palette;
            this.blur = blur;
            this.cornerRadius = cornerRadius;
            setWillNotDraw(false);
            setClickable(true);
            int satPct = blur.getBlurSaturationPct();
            if (satPct != 100) {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(satPct / 100f);
                bmpPaint.setColorFilter(new ColorMatrixColorFilter(cm));
            }
            rimPaint.setStyle(Paint.Style.STROKE);
            refreshRimOutline();
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
            setClipToOutline(true);
            blur.registerTarget(this);
        }

        void refreshRimOutline() {
            rimPaint.setColor(palette.getCardRimColor());
            rimPaint.setStrokeWidth(UiHelper.getCardOutlineWidthPx(getContext(), 1.15f));
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            cardBounds.set(0f, 0f, w, h);
            clipPath.reset();
            clipPath.addRoundRect(cardBounds, cornerRadius, cornerRadius, Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (cardBounds.width() != w || cardBounds.height() != h) {
                cardBounds.set(0f, 0f, w, h);
                clipPath.reset();
                clipPath.addRoundRect(cardBounds, cornerRadius, cornerRadius, Path.Direction.CW);
            }

            boolean drewBlur = false;
            Bitmap bmp = blur.getReadyBitmap();
            int sw = blur.getSurfaceWidth();
            int sh = blur.getSurfaceHeight();

            canvas.save();
            canvas.clipPath(clipPath);

            if (bmp != null && !bmp.isRecycled() && sw > 0 && sh > 0) {
                getLocationOnScreen(viewLoc);
                int originL = blur.getSurfaceScreenLeft();
                int originT = blur.getSurfaceScreenTop();
                int bw = bmp.getWidth();
                int bh = bmp.getHeight();
                float scaleX = bw / (float) sw;
                float scaleY = bh / (float) sh;

                int srcL = Math.max(0, Math.min(bw - 1, Math.round((viewLoc[0] - originL) * scaleX)));
                int srcT = Math.max(0, Math.min(bh - 1, Math.round((viewLoc[1] - originT) * scaleY)));
                int srcR = Math.max(srcL + 1, Math.min(bw, Math.round((viewLoc[0] + w - originL) * scaleX)));
                int srcB = Math.max(srcT + 1, Math.min(bh, Math.round((viewLoc[1] + h - originT) * scaleY)));

                srcRect.set(srcL, srcT, srcR, srcB);
                dstRect.set(0f, 0f, w, h);
                canvas.drawBitmap(bmp, srcRect, dstRect, bmpPaint);
                drewBlur = true;
            }

            boolean matteFallback = !drewBlur && blur.isBatterySaver();
            int tintColor = matteFallback
                    ? (palette.surface | 0xFF000000)
                    : palette.getQuickMenuCardTint(drewBlur || blur.blurPct <= 0);
            if ((tintColor >>> 24) > 0) {
                fillPaint.setColor(tintColor);
                canvas.drawRect(cardBounds, fillPaint);
            }
            canvas.restore();

            float strokeW = rimPaint.getStrokeWidth();
            if (strokeW > 0.1f) {
                float inset = strokeW * 0.5f;
                dstRect.set(inset, inset, w - inset, h - inset);
                canvas.drawRoundRect(dstRect, Math.max(0f, cornerRadius - inset),
                        Math.max(0f, cornerRadius - inset), rimPaint);
            }
        }
    }
}
