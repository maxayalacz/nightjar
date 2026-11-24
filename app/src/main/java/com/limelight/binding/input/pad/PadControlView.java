package com.limelight.binding.input.pad;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;

import com.limelight.binding.input.LayoutGamepad;
import com.limelight.binding.input.LayoutStick;
import com.limelight.binding.input.MouseDeltaAccumulator;
import com.limelight.binding.input.TouchLayout;
import com.limelight.binding.input.touch.PointerAcceleration;
import com.limelight.binding.input.SteamControls;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.ui.Haptics;

/**
 * One control of the modern on-screen controller. Each control is its own view so a finger that
 * misses every control reaches the stream underneath, while several fingers on different controls
 * each go to their own. Sticks follow one finger; the face cluster and the D-pad follow every finger
 * on them, so a thumb can slide from A to B or rest between them for both. A Steam Controller trackpad follows one
 * finger, sends it as a touch on that pad and clicks the pad from a tap; a grip holds from one tap to the next. The pointer touchpad moves the
 * mouse or the camera, with tap, two-finger right-click and two-finger scroll. A split cluster's members are views of
 * their own, and a finger that lands on one slides across all four ({@link Group}). A press shows at once; let go, it
 * eases back over {@link PadPainter#RELEASE_MS}, and its halo past the control's edge draws in the {@link HaloLayer}.
 */
@SuppressLint("ViewConstructor") // Created by ModernController for one control.
final class PadControlView extends View {
    private static final int MAX_POINTERS = 4;

    final PadLayout.Control control;
    private final PadPainter painter;
    private final LayoutGamepad gamepad;
    private final Runnable changed;
    private final PadPainter.State state = new PadPainter.State();
    private LayoutStick stick;
    private final int[] pointerIds = new int[MAX_POINTERS], pointerFlags = new int[MAX_POINTERS];
    private int stickPointer = -1;
    // A stick tap (short, barely pushed) arms its click for the next touch; a flick does not.
    private long stickReleasedAt, stickDownAt;
    private float stickPush;
    private boolean stickClick, haptics = true, stickTouch;
    private float restingAlpha = 1;
    /** A stick's knob, drawn in a larger view of its own; null for every other control. */
    private final KnobLayer knobLayer;
    /** The keys the WASD stick holds; null for every other control. */
    private final WasdKeys wasd;
    /** Where a trackpad's touches go; the trackpads send nothing without one. */
    private ModernController.TouchSink touchSink;
    /** A trackpad tap in the making: when the finger went down, and how far it has travelled since. */
    private long padDownAt;
    /** A tapped trackpad's click is held and its touch not yet ended; where the finger was. */
    private boolean padClickPending;
    private float padClickX, padClickY;
    private final Runnable padClickRelease = this::finishPadClick;
    private float padTravel, padLastX, padLastY;
    /** The split cluster this control is a member of; null for every other control. */
    private Group group;
    /** The layer under the controls that draws a press's halo past this control's edge; null without one. */
    private View haloLayer;
    private PadLiveBlurHelper blurHelper;
    /** The parts held at the last look ({@link PadPainter#heldParts}), and when each let go while easing back, else 0. */
    private int heldParts;
    private final long[] releasedAt = new long[PadPainter.PARTS];
    private final Runnable easing = this::ease;

    /** Where the pointer touchpad's mouse movement, clicks and scrolls go; it sends nothing without one. */
    private ModernController.PointerSink pointerSink;
    private PadPointerMotion pointerMotion;
    private boolean pointerCamera;
    private MouseDeltaAccumulator scrollV = new MouseDeltaAccumulator(), scrollH = new MouseDeltaAccumulator();
    private final int[] pointerDelta = new int[2];
    private final float density;
    private static final float SCROLL_DP_PER_NOTCH = 24, SCROLL_UNITS_PER_NOTCH = 120, HORIZONTAL_SCROLL_RATIO = .5f;
    private static final int CAMERA_TICK_MS = 16;
    /**
     * How long a tapped trackpad's click is held, finger still on the pad, before it releases and the
     * touch ends: a little over the classic controller's 25 ms first resend, so one report carries
     * the press alone. The OSC has no other tap or hold constant to reuse.
     */
    static final long PAD_CLICK_HOLD_MS = 40;
    private long pointerTickNs;
    private float pointerTravel;
    private final float[] pointerLastX = new float[2], pointerLastY = new float[2];
    private final long[] pointerDownAt = new long[2];
    private boolean pointerScrolled, pointerSecondDown, pointerTap;
    /** The event time of the move being handled, in nanoseconds; the pointer touchpad's smoothing needs it. */
    private long touchTimeNs;
    private final Runnable cameraTick = new Runnable() {
        @Override
        public void run() {
            if (pointerMotion == null || !pointerCamera) return;
            long now = System.nanoTime();
            pointerMotion.tick(now - pointerTickNs);
            pointerTickNs = now;
            int slot = slot();
            gamepad.setStick(slot, TouchLayout.RIGHT, pointerMotion.cameraX(), pointerMotion.cameraY());
            changed.run();
            if (pointerMotion.cameraMagnitude() > PadPointerMotion.CAMERA_STOP) postDelayed(this, CAMERA_TICK_MS);
        }
    };

    PadControlView(Context context, PadLayout.Control control, PadPainter painter, LayoutGamepad gamepad,
                   Runnable changed, WasdKeys.Keys keys) {
        super(context);
        this.control = control;
        this.painter = painter;
        this.gamepad = gamepad;
        this.changed = changed;
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setHapticFeedbackEnabled(true);
        java.util.Arrays.fill(pointerIds, -1);
        configureStick(true, .08f);
        knobLayer = control.shape == PadLayout.Shape.STICK ? new KnobLayer(context, this) : null;
        wasd = control == PadLayout.Control.WASD ? new WasdKeys(keys) : null;
    }

    /**
     * Draws a stick's knob. At full deflection the knob's centre reaches the base's rim, so half of
     * it leaves the control's box. Rather than growing the control (which would take touches meant
     * for its neighbours or the stream), the knob draws in this view: the box plus the overhang on
     * every side, above the controls, taking no touches at all.
     */
    @SuppressLint("ViewConstructor")
    static final class KnobLayer extends View {
        private final PadControlView owner;

        KnobLayer(Context context, PadControlView owner) {
            super(context);
            this.owner = owner;
            setFocusable(false);
            setClickable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // The control's box sits centred in this view.
            int width = owner.getWidth(), height = owner.getHeight();
            owner.painter.drawStickKnob(canvas, owner.control, (getWidth() - width) / 2f, (getHeight() - height) / 2f,
                    width, height, owner.state);
        }

        @SuppressLint("ClickableViewAccessibility") // Draws only; touches fall through to what is below.
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }
    }

    /**
     * Draws the press cue that reaches past the controls' edges ({@link PadPainter#drawHalo}), so a thumb covering a
     * small button still sees it answer: one view under every control, the size of the frame, taking no touches. It
     * draws only around a held or easing part, never for a control at opacity 0, and a control asks it to redraw when
     * its press changes; its own frames run only while a press ring does.
     */
    @SuppressLint("ViewConstructor")
    static final class HaloLayer extends View {
        private final PadPainter painter;
        private final java.util.Collection<PadControlView> controls;

        /** {@code controls} is read on every draw, so the layer follows the controller's controls as they change. */
        HaloLayer(Context context, PadPainter painter, java.util.Collection<PadControlView> controls) {
            super(context);
            this.painter = painter;
            this.controls = controls;
            setFocusable(false);
            setClickable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = AnimationUtils.currentAnimationTimeMillis();
            boolean running = false;
            for (PadControlView view : controls) {
                if (view.getVisibility() != VISIBLE || view.restingAlpha <= 0) continue;
                // The controls are this layer's siblings, laid out in the same frame.
                running |= painter.drawHalo(canvas, view.control, view.getLeft() - getLeft(), view.getTop() - getTop(),
                        view.getWidth(), view.getHeight(), view.state, view.getAlpha(), now);
            }
            if (running) postInvalidateOnAnimation();
        }

        @SuppressLint("ClickableViewAccessibility") // Draws only; touches fall through to what is below.
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }
    }

    /** Where this control's press halo draws ({@link HaloLayer}). */
    void setHaloLayer(View haloLayer) {
        this.haloLayer = haloLayer;
    }

    void setBlurHelper(PadLiveBlurHelper blurHelper) {
        this.blurHelper = blurHelper;
    }

    /** The knob's own view for a stick, to add above the controls; null for other controls. */
    View knobLayer() {
        return knobLayer;
    }

    /** How far past each edge the knob layer reaches for a stick of this box, in whole pixels. */
    int knobOverhang(float width, float height) {
        return (int) Math.ceil(painter.knobOverhang(width, height));
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (knobLayer != null) knobLayer.setVisibility(visibility);
        // A hidden member takes no input, also from a finger that slid onto it from another member.
        if (group != null && visibility != VISIBLE) group.update(null);
        if (haloLayer != null) haloLayer.invalidate();
    }

    /**
     * The four members of a split cluster (A, B, X and Y, or the D-pad's directions) as one group. There is no touch
     * router: Android keeps sending a finger's moves to the view it landed on, which owns it. On every move the owner
     * asks the group which members' boxes hold the finger, in the frame's coordinates, and holds all of them, so
     * sliding from A to B lets go of A and presses B, and a finger where two boxes overlap holds both (a D-pad
     * diagonal). Each member shows itself pressed while any finger holds it and sends its own button in its own slot
     * of the gamepad report, as separate controls do.
     */
    static final class Group {
        private final PadControlView[] members;
        /** The members some finger holds, as bits in member order. */
        private int held;

        /** {@code members} in {@link PadLayout.Control#memberIndex} order; one missing is null. */
        Group(PadControlView... members) {
            this.members = members;
            for (PadControlView member : members) {
                if (member != null) member.group = this;
            }
        }

        /** The shown members whose boxes hold the point ({@code x}, {@code y}) of the frame, as bits. */
        int under(float x, float y) {
            int bits = 0;
            for (int i = 0; i < members.length; i++) {
                PadControlView member = members[i];
                if (member == null || member.getVisibility() != VISIBLE) continue;
                if (x >= member.getLeft() && x < member.getRight() && y >= member.getTop() && y < member.getBottom()) {
                    bits |= 1 << i;
                }
            }
            return bits;
        }

        /**
         * Gathers what every finger on the group holds and shows and sends it; {@code owner}'s newly held member
         * ticks, as a newly held button does on the clusters.
         */
        void update(PadControlView owner) {
            int before = held;
            held = 0;
            for (PadControlView member : members) {
                if (member == null) continue;
                for (int i = 0; i < MAX_POINTERS; i++) {
                    if (member.pointerIds[i] >= 0) held |= member.pointerFlags[i];
                }
            }
            for (int i = 0; i < members.length; i++) {
                PadControlView member = members[i];
                if (member == null) continue;
                boolean on = (held & 1 << i) != 0 && member.getVisibility() == VISIBLE;
                if (!on) held &= ~(1 << i);
                member.hold(on);
            }
            if (owner != null && (held & ~before) != 0) {
                owner.feedback(owner.control.shape == PadLayout.Shape.FACE_BUTTON ? HapticFeedbackConstants.VIRTUAL_KEY :
                        HapticFeedbackConstants.CLOCK_TICK);
            }
        }
    }

    /** The group of members this view is one of, for tests; null for every other control. */
    Group group() {
        return group;
    }

    /** A member held or let go by the group's fingers: its look and its button follow. */
    private void hold(boolean on) {
        int flags = on ? PadInput.buttonFlag(control) : 0;
        if (state.pressed == on && state.flags == flags) return;
        state.pressed = on;
        state.flags = flags;
        gamepad.setButtons(slot(), flags);
        changed();
    }

    /** A member's finger at ({@code x}, {@code y}) of this view: it holds every member under it and lets go of the rest. */
    private void memberMove(int index, float x, float y) {
        int under = group.under(getLeft() + x, getTop() + y);
        if (under == pointerFlags[index]) return;
        pointerFlags[index] = under;
        group.update(this);
    }

    void configureStick(boolean floating, float deadzone) {
        stick = new LayoutStick(floating, Math.max(0, Math.min(.9f, deadzone)));
    }

    /** With the Steam Controller controls, a finger on an analog stick also touches that controller's stick. */
    void setStickTouch(boolean stickTouch) {
        this.stickTouch = stickTouch;
    }

    void setHaptics(boolean haptics) {
        this.haptics = haptics;
    }

    void setTouchSink(ModernController.TouchSink touchSink) {
        this.touchSink = touchSink;
    }

    /**
     * Builds the pointer touchpad's motion model: the camera's mapping, or the pointer's with the user's curve.
     * {@code speedPercent} is the Trackpad speed setting, which sets how far a swipe turns the camera.
     */
    void configurePointer(boolean camera, String curve, int speedPercent) {
        pointerCamera = camera;
        pointerMotion = new PadPointerMotion(PointerAcceleration.forCurve(curve), speedPercent);
        // The pad itself says which it does, so a glance tells the Camera mode from the Mouse one.
        state.label = control == PadLayout.Control.POINTER_PAD ? painter.pointerLabel(camera) : "";
        redraw();
    }

    void setPointerSink(ModernController.PointerSink sink) {
        pointerSink = sink;
    }

    /**
     * The alpha at rest; a press shows at least 60 % so a faint control still answers visibly. At 0, the opacity
     * setting's 0 %, a control stays invisible even while pressed: the player chose controls nobody sees, so a press
     * draws nothing, not the control, a stick's knob, a touchpad's finger nor the pressed amber.
     */
    void setRestingAlpha(float alpha) {
        restingAlpha = alpha;
        applyAlpha();
    }

    /** The alpha a press raises a faint, but visible, control to. */
    static final float PRESSED_ALPHA = .6f;

    /**
     * The alpha for a control at rest at {@code resting}, pressed in by {@code level}: 1 while held, easing back to 0
     * once let go, so a faint control fades back with its press.
     */
    static float alpha(float resting, float level) {
        if (resting <= 0) return 0;
        return resting + (Math.max(resting, PRESSED_ALPHA) - resting) * Math.max(0, Math.min(1, level));
    }

    private void applyAlpha() {
        trackPress();
        showAlpha();
    }

    private void showAlpha() {
        boolean active = state.flags != 0 || state.pressed || state.stickActive;
        float alpha = alpha(restingAlpha, active ? 1 : easingLevel());
        setAlpha(alpha);
        if (knobLayer != null) knobLayer.setAlpha(alpha);
    }

    /** The deepest press still easing back, or 0. */
    private float easingLevel() {
        float level = 0;
        for (int i = 0; i < PadPainter.PARTS; i++) {
            if (releasedAt[i] != 0) level = Math.max(level, state.press[i]);
        }
        return level;
    }

    /**
     * Follows which parts are held: a part going down shows pressed at once and rings, a part let go eases back over
     * the next frames ({@link #ease}). At opacity 0 a part only goes in and out, as nothing of it is drawn.
     */
    private void trackPress() {
        int held = PadPainter.heldParts(control, state, painter.facesSwapped());
        if (held == heldParts) return;
        long now = AnimationUtils.currentAnimationTimeMillis();
        boolean visible = restingAlpha > 0, letGo = false;
        for (int i = 0; i < PadPainter.PARTS; i++) {
            boolean on = (held & 1 << i) != 0, was = (heldParts & 1 << i) != 0;
            if (on) {
                state.press[i] = 1;
                releasedAt[i] = 0;
                if (!was) state.pressedAt[i] = visible ? now : 0;
            } else if (was && visible) {
                releasedAt[i] = now;
                letGo = true;
            } else if (was) {
                state.press[i] = 0;
            }
        }
        heldParts = held;
        if (letGo) {
            removeCallbacks(easing);
            postOnAnimation(easing);
        }
        if (haloLayer != null) haloLayer.invalidate();
    }

    /** One frame of the let-go parts easing back; it comes again until all of them are out. */
    private void ease() {
        long now = AnimationUtils.currentAnimationTimeMillis();
        boolean running = false;
        for (int i = 0; i < PadPainter.PARTS; i++) {
            if (releasedAt[i] == 0) continue;
            float level = PadPainter.easeBack(now - releasedAt[i]);
            state.press[i] = level;
            if (level <= 0) {
                releasedAt[i] = 0;
            } else {
                running = true;
            }
        }
        showAlpha();
        redraw();
        if (haloLayer != null) haloLayer.invalidate();
        if (running) postOnAnimation(easing);
    }

    /** Drops every easing press at once: input was taken away, so nothing lingers. What is still held stays. */
    private void stopEasing() {
        removeCallbacks(easing);
        java.util.Arrays.fill(releasedAt, 0);
        heldParts = PadPainter.heldParts(control, state, painter.facesSwapped());
        for (int i = 0; i < PadPainter.PARTS; i++) {
            boolean on = (heldParts & 1 << i) != 0;
            state.press[i] = on ? 1 : 0;
            if (!on) state.pressedAt[i] = 0;
        }
        showAlpha();
        if (haloLayer != null) haloLayer.invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(easing);
        finishPadClick();
    }

    private void redraw() {
        invalidate();
        if (knobLayer != null) knobLayer.invalidate();
    }

    private int slot() {
        return control.ordinal();
    }

    private float size() {
        return Math.min(getWidth(), getHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        state.now = AnimationUtils.currentAnimationTimeMillis();
        painter.bindBlurView(this, blurHelper);
        try {
            if (knobLayer != null) {
                painter.drawStickBase(canvas, control, 0, 0, getWidth(), getHeight(), state);
            } else {
                painter.draw(canvas, control, 0, 0, getWidth(), getHeight(), state);
            }
        } finally {
            painter.clearBlurView();
        }
        // A touchpad's trail fades over the next frames, also once the finger lifts.
        if (restingAlpha > 0 && state.trailShows(state.now)) postInvalidateOnAnimation();
    }

    /** A touchpad finger at ({@code x}, {@code y}); {@code first} starts a new trail behind it. */
    private void touchAt(float x, float y, boolean first) {
        state.touchX = x;
        state.touchY = y;
        if (first) state.trailStart = state.trailCount = 0;
        if (restingAlpha > 0) state.trail(x, y, AnimationUtils.currentAnimationTimeMillis());
    }

    @SuppressLint("ClickableViewAccessibility") // A gamepad control, not a click target.
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                touchTimeNs = eventTimeNs(event, -1);
                down(event.getPointerId(index), event.getX(index), event.getY(index), event.getEventTime());
                break;
            }
            case MotionEvent.ACTION_MOVE:
                // Historical samples carry their own times, so the pointer touchpad's speed is not one spike per
                // frame. Every other control reports once per event, as it always has: extra stick, button and touch
                // reports for intermediate positions would change existing input.
                if (control.shape == PadLayout.Shape.TOUCHPAD) {
                    for (int history = 0; history < event.getHistorySize(); history++) {
                        long time = eventTimeNs(event, history);
                        for (int i = 0; i < event.getPointerCount(); i++) {
                            move(event.getPointerId(i), event.getHistoricalX(i, history), event.getHistoricalY(i, history), time);
                        }
                    }
                }
                // Each sample is stamped with its own event time, not the time it happened to be dispatched: the gap
                // between them is the finger's speed, and a late frame must not read as a slower one.
                touchTimeNs = eventTimeNs(event, -1);
                for (int i = 0; i < event.getPointerCount(); i++) {
                    move(event.getPointerId(i), event.getX(i), event.getY(i), touchTimeNs);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(event.getPointerId(event.getActionIndex()), event.getEventTime());
                break;
            case MotionEvent.ACTION_CANCEL:
                release();
                break;
            default:
                break;
        }
        return true;
    }

    void down(int pointer, float x, float y, long time) {
        if (group != null) {
            int free = slotFor(-1);
            if (free < 0) return;
            pointerIds[free] = pointer;
            pointerFlags[free] = 0;
            memberMove(free, x, y);
            return;
        }
        switch (control.shape) {
            case STICK:
                if (stickPointer >= 0) return;
                stickPointer = pointer;
                stick.down(x, y, getWidth(), getHeight(), PadPainter.travel(size()));
                stickClick = PadInput.stickClick(stickReleasedAt, time);
                stickDownAt = time;
                stickPush = 0;
                state.stickActive = true;
                state.centerX = stick.centerX();
                state.centerY = stick.centerY();
                // The WASD stick answers when a key goes down, in updateStick, not on touch.
                if (wasd == null) feedback(HapticFeedbackConstants.VIRTUAL_KEY);
                updateStick();
                break;
            case FACE:
            case DPAD: {
                int free = slotFor(-1);
                if (free < 0) return;
                pointerIds[free] = pointer;
                pointerFlags[free] = 0;
                move(pointer, x, y);
                break;
            }
            case PAD:
                // One finger per pad, as on the controller.
                if (state.pressed) return;
                finishPadClick();
                pointerIds[0] = pointer;
                state.pressed = true;
                padDownAt = time;
                padTravel = 0;
                padLastX = x;
                padLastY = y;
                padTouch(MoonBridge.LI_TOUCH_EVENT_DOWN, x, y);
                applyAlpha();
                redraw();
                break;
            case TOUCHPAD:
                if (!state.pressed) {
                    // The first finger: a new gesture, from where it landed.
                    pointerIds[0] = pointer;
                    pointerIds[1] = -1;
                    state.pressed = true;
                    pointerDownAt[0] = time;
                    pointerTravel = 0;
                    pointerScrolled = pointerSecondDown = pointerTap = false;
                    pointerLastX[0] = x;
                    pointerLastY[0] = y;
                    if (pointerMotion != null) pointerMotion.reset();
                    removeCallbacks(cameraTick);
                    touchAt(x, y, true);
                    applyAlpha();
                    redraw();
                } else if (pointerIds[1] < 0) {
                    // A second finger turns the gesture into scroll or right-click; the pointer stops where it was.
                    pointerIds[1] = pointer;
                    pointerSecondDown = true;
                    pointerDownAt[1] = time;
                    pointerLastX[1] = x;
                    pointerLastY[1] = y;
                    removeCallbacks(cameraTick);
                    if (pointerCamera && pointerMotion != null) {
                        pointerMotion.stopCamera();
                        gamepad.setStick(slot(), TouchLayout.RIGHT, 0, 0);
                        changed.run();
                    }
                }
                break;
            default:
                if (PadInput.latches(control)) {
                    // A grip: each tap toggles it; the finger lifting changes nothing.
                    state.pressed = !state.pressed;
                    gamepad.setButtons(slot(), state.pressed ? PadInput.buttonFlag(control) : 0);
                    feedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    changed();
                    break;
                }
                if (state.pressed) return;
                pointerIds[0] = pointer;
                state.pressed = true;
                if (PadInput.isTrigger(control)) {
                    gamepad.setTrigger(slot(), control == PadLayout.Control.LT ? TouchLayout.LEFT : TouchLayout.RIGHT, 1);
                } else {
                    gamepad.setButtons(slot(), PadInput.buttonFlag(control));
                }
                feedback(HapticFeedbackConstants.VIRTUAL_KEY);
                changed();
                break;
        }
    }

    void move(int pointer, float x, float y) {
        move(pointer, x, y, System.nanoTime());
    }

    /**
     * An event sample's own time in nanoseconds: {@code history} names a historical sample, -1 the event itself.
     * Android 14 reports nanoseconds; before it, whole milliseconds are all the event carries, so they are widened.
     */
    private static long eventTimeNs(MotionEvent event, int history) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return history < 0 ? event.getEventTimeNanos() : event.getHistoricalEventTimeNanos(history);
        }
        return (history < 0 ? event.getEventTime() : event.getHistoricalEventTime(history)) * 1_000_000L;
    }

    void move(int pointer, float x, float y, long eventTimeNs) {
        touchTimeNs = eventTimeNs;
        if (group != null) {
            int index = slotFor(pointer);
            if (index >= 0) memberMove(index, x, y);
            return;
        }
        switch (control.shape) {
            case STICK:
                if (pointer != stickPointer) return;
                stick.move(x, y);
                updateStick();
                break;
            case FACE:
            case DPAD: {
                int index = slotFor(pointer);
                if (index < 0) return;
                // The whole box answers, a resized one's buttons spread over it as they are drawn.
                int flags = control.shape == PadLayout.Shape.FACE ?
                        PadInput.face(x, y, getWidth(), getHeight(), painter.facesSwapped()) :
                        PadInput.dpad(x, y, getWidth(), getHeight());
                if (flags == pointerFlags[index]) return;
                int before = state.flags;
                pointerFlags[index] = flags;
                updateMulti();
                // A tick for every newly held button or direction; sliding off to nothing is silent.
                if ((state.flags & ~before) != 0) {
                    feedback(control.shape == PadLayout.Shape.FACE ? HapticFeedbackConstants.VIRTUAL_KEY :
                            HapticFeedbackConstants.CLOCK_TICK);
                }
                break;
            }
            case PAD:
                if (!state.pressed || pointer != pointerIds[0]) return;
                padTravel += (float) Math.hypot(x - padLastX, y - padLastY);
                padLastX = x;
                padLastY = y;
                padTouch(MoonBridge.LI_TOUCH_EVENT_MOVE, x, y);
                redraw();
                break;
            case TOUCHPAD: {
                int pointerSlot = pointer == pointerIds[0] ? 0 : pointerIds[1] >= 0 && pointer == pointerIds[1] ? 1 : -1;
                if (pointerSlot < 0) return;
                // The centroid travels with the fingers: each contributes its share, so two fingers moving
                // together scroll once rather than twice, and a finger left after a lift scrolls at its own pace.
                int fingers = (pointerIds[0] >= 0 ? 1 : 0) + (pointerIds[1] >= 0 ? 1 : 0);
                float dx = (x - pointerLastX[pointerSlot]) / fingers, dy = (y - pointerLastY[pointerSlot]) / fingers;
                pointerLastX[pointerSlot] = x;
                pointerLastY[pointerSlot] = y;
                pointerTravel += (float) Math.hypot(dx, dy);
                if (pointerSecondDown) {
                    // Scrolling starts only past the tap slop, so a two-finger tap with a small drift stays a
                    // tap and never both scrolls and clicks right; two fingers scroll; neither moves the
                    // pointer, even after one lifts.
                    if (!pointerScrolled && pointerTravel > PadInput.TOUCHPAD_TAP_SLOP_DP * density) {
                        pointerScrolled = true;
                        pointerTap = false;
                    }
                    if (pointerScrolled) scrollBy(dx, dy);
                    redraw();
                } else if (pointerSlot == 0) {
                    touchAt(x, y, false);
                    if (pointerMotion != null) {
                        pointerMotion.move(dx, dy, touchTimeNs, density, pointerDelta);
                        if (pointerCamera) {
                            pointerTickNs = System.nanoTime();
                            gamepad.setStick(slot(), TouchLayout.RIGHT, pointerMotion.cameraX(), pointerMotion.cameraY());
                            changed.run();
                            removeCallbacks(cameraTick);
                            postDelayed(cameraTick, CAMERA_TICK_MS);
                        } else if (pointerDelta[0] != 0 || pointerDelta[1] != 0) {
                            if (pointerSink != null) pointerSink.move(pointerDelta[0], pointerDelta[1]);
                        }
                    }
                    redraw();
                }
                break;
            }
            default:
                break;
        }
    }

    void up(int pointer, long time) {
        if (group != null) {
            int index = slotFor(pointer);
            if (index < 0) return;
            pointerIds[index] = -1;
            pointerFlags[index] = 0;
            group.update(this);
            return;
        }
        switch (control.shape) {
            case STICK:
                if (pointer != stickPointer) return;
                stickPointer = -1;
                stickReleasedAt = !stickClick && PadInput.stickTap(time - stickDownAt, stickPush) ? time : 0;
                stickClick = false;
                stick.release();
                state.stickActive = false;
                state.centerX = Float.NaN;
                state.centerY = Float.NaN;
                updateStick();
                break;
            case FACE:
            case DPAD: {
                int index = slotFor(pointer);
                if (index < 0) return;
                pointerIds[index] = -1;
                pointerFlags[index] = 0;
                updateMulti();
                break;
            }
            case PAD:
                if (!state.pressed || pointer != pointerIds[0]) return;
                pointerIds[0] = -1;
                state.pressed = false;
                // A tap clicks the pad, as pressing the real one does: short, and the finger stayed
                // near where it landed, by the pointer touchpad's own thresholds. A drag or a
                // lingering finger does not. The click goes out with the finger still on the pad
                // and the touch ends after it, so a click that reads the touch position sees one.
                if (PadInput.touchpadTap(time - padDownAt, padTravel, density) && PadInput.padClickFlag(control) != 0) {
                    padClickX = state.touchX;
                    padClickY = state.touchY;
                    padClickPending = true;
                    gamepad.setButtons(slot(), PadInput.padClickFlag(control));
                    if (haptics) Haptics.perform(this, Haptics.Kind.CLICK);
                    changed();
                    postDelayed(padClickRelease, PAD_CLICK_HOLD_MS);
                } else {
                    padTouch(MoonBridge.LI_TOUCH_EVENT_UP, state.touchX, state.touchY);
                }
                state.touchX = Float.NaN;
                state.touchY = Float.NaN;
                applyAlpha();
                redraw();
                break;
            case TOUCHPAD:
                finishPointerGesture(pointer, time);
                break;
            default:
                if (!state.pressed || pointerIds[0] != pointer) return;
                pointerIds[0] = -1;
                releaseButton();
                break;
        }
    }

    /** Lets go of everything this control holds, as a cancelled touch does; a grip lets go too. */
    void release() {
        if (group != null) {
            // Its own fingers let go; a member another member's finger still holds stays held until that one lifts.
            java.util.Arrays.fill(pointerIds, -1);
            java.util.Arrays.fill(pointerFlags, 0);
            group.update(null);
            stopEasing();
            redraw();
            return;
        }
        // A held click ends with its touch, in order, before the rest lets go.
        finishPadClick();
        // A pointer touchpad stops at once when input is taken away; nothing can carry on.
        removeCallbacks(cameraTick);
        if (control.shape == PadLayout.Shape.TOUCHPAD) {
            if (pointerCamera) gamepad.setStick(slot(), TouchLayout.RIGHT, 0, 0);
            pointerSecondDown = pointerScrolled = pointerTap = false;
            pointerIds[1] = -1;
            if (pointerMotion != null) pointerMotion.reset();
            scrollV = new MouseDeltaAccumulator();
            scrollH = new MouseDeltaAccumulator();
        }
        // A trackpad's finger leaves where it last was, as the classic controller's does.
        if (control.shape == PadLayout.Shape.PAD && state.pressed) {
            padTouch(MoonBridge.LI_TOUCH_EVENT_CANCEL, state.touchX, state.touchY);
        }
        java.util.Arrays.fill(pointerIds, -1);
        java.util.Arrays.fill(pointerFlags, 0);
        stickPointer = -1;
        stickClick = false;
        stickReleasedAt = 0;
        padDownAt = 0;
        stick.release();
        boolean was = state.flags != 0 || state.pressed || state.stickActive;
        state.clear();
        stopEasing();
        if (wasd == null) gamepad.setButtons(slot(), 0);
        if (control.shape == PadLayout.Shape.STICK && wasd == null) {
            gamepad.setStick(slot(), control == PadLayout.Control.LEFT_STICK ? TouchLayout.LEFT : TouchLayout.RIGHT, 0, 0);
        }
        if (PadInput.isTrigger(control)) {
            gamepad.setTrigger(slot(), control == PadLayout.Control.LT ? TouchLayout.LEFT : TouchLayout.RIGHT, 0);
        }
        if (wasd != null) {
            wasd.release();
            applyAlpha();
            redraw();
            return;
        }
        if (was) changed();
        else redraw();
    }

    /** Ends a pointer touchpad touch: a tap clicks, two fingers tap the right button, and a camera push decays. */
    private void finishPointerGesture(int pointer, long time) {
        boolean first = pointer == pointerIds[0];
        boolean second = pointerIds[1] >= 0 && pointer == pointerIds[1];
        if (!first && !second) return;
        int slot = first ? 0 : 1;
        if (first) pointerIds[0] = -1;
        if (second) pointerIds[1] = -1;
        // Each finger's own touch decides: short, and staying near where it landed. A finger that lingers
        // after its partner lifts is not a tap, so it cannot turn a scroll or a long touch into a right click.
        boolean tap = !pointerScrolled && PadInput.touchpadTap(time - pointerDownAt[slot], pointerTravel, density);
        if (pointerIds[0] >= 0 || pointerIds[1] >= 0) {
            // A finger is still down: the right click needs the last lift to be a tap too.
            pointerTap = tap;
            return;
        }
        if (pointerSecondDown) {
            if (pointerTap && tap) click(MouseButtonPacket.BUTTON_RIGHT);
        } else if (tap) {
            click(MouseButtonPacket.BUTTON_LEFT);
        }
        pointerSecondDown = pointerScrolled = pointerTap = false;
        pointerIds[0] = pointerIds[1] = -1;
        state.pressed = false;
        state.touchX = Float.NaN;
        state.touchY = Float.NaN;
        scrollV = new MouseDeltaAccumulator();
        scrollH = new MouseDeltaAccumulator();
        if (pointerCamera && pointerMotion != null) {
            // Let go: the camera eases back to the center over the next few frames, not with a jump.
            pointerTickNs = System.nanoTime();
            removeCallbacks(cameraTick);
            postDelayed(cameraTick, CAMERA_TICK_MS);
        }
        applyAlpha();
        redraw();
    }

    private void click(int button) {
        if (pointerSink != null) {
            pointerSink.button(button, true);
            pointerSink.button(button, false);
        }
        if (haptics) Haptics.perform(this, Haptics.Kind.CLICK);
    }

    /** Two fingers on the pointer touchpad scroll: down moves the wheel down, right moves it right, in whole notches. */
    private void scrollBy(float dx, float dy) {
        if (pointerSink == null) return;
        float unitsPerPixel = SCROLL_UNITS_PER_NOTCH / Math.max(1, SCROLL_DP_PER_NOTCH * density);
        int vertical = scrollV.scale(dy, unitsPerPixel);
        int horizontal = scrollH.scale(-dx, unitsPerPixel * HORIZONTAL_SCROLL_RATIO);
        if (vertical != 0 || horizontal != 0) pointerSink.scroll(vertical, horizontal);
    }

    /** Sends a finger at ({@code x}, {@code y}) on this trackpad as a touch on its Steam Controller pad. */
    private void padTouch(byte type, float x, float y) {
        if (type == MoonBridge.LI_TOUCH_EVENT_DOWN || type == MoonBridge.LI_TOUCH_EVENT_MOVE) {
            touchAt(x, y, type == MoonBridge.LI_TOUCH_EVENT_DOWN);
        } else {
            state.touchX = x;
            state.touchY = y;
        }
        if (touchSink == null) return;
        int pad = PadInput.steamPad(control);
        touchSink.touch(type, SteamControls.pointerId(pad), SteamControls.touchX(pad, fraction(x, getWidth())),
                SteamControls.touchY(fraction(y, getHeight())), SteamControls.PAD_TOUCH_PRESSURE);
    }

    private static float fraction(float position, int size) {
        return size > 0 ? position / size : 0f;
    }

    /**
     * Ends a tapped pad's click: the release, then the touch's end, so the click went out with the
     * finger on the pad. The pad's slot holds nothing else, so a resend carries the release as for
     * any button let go. A no-op when no click is held.
     */
    private void finishPadClick() {
        if (!padClickPending) return;
        padClickPending = false;
        removeCallbacks(padClickRelease);
        gamepad.setButtons(slot(), 0);
        changed();
        padTouch(MoonBridge.LI_TOUCH_EVENT_UP, padClickX, padClickY);
        state.touchX = Float.NaN;
        state.touchY = Float.NaN;
    }

    private void releaseButton() {
        state.pressed = false;
        if (PadInput.isTrigger(control)) {
            gamepad.setTrigger(slot(), control == PadLayout.Control.LT ? TouchLayout.LEFT : TouchLayout.RIGHT, 0);
        } else {
            gamepad.setButtons(slot(), 0);
        }
        changed();
    }

    private void updateStick() {
        boolean arcWasDrawn = state.stickX != 0 || state.stickY != 0;
        state.stickX = stick.x;
        state.stickY = stick.y;
        if (wasd != null) {
            WasdKeys.Feedback entered = wasd.update(stick.x, stick.y);
            if (haptics && entered != WasdKeys.Feedback.NONE) {
                Haptics.perform(this, entered == WasdKeys.Feedback.DIAGONAL ? Haptics.Kind.DIAGONAL : Haptics.Kind.DIRECTION);
            }
            state.flags = wasd.directions();
            applyAlpha();
            redraw();
            return;
        }
        stickPush = Math.max(stickPush, (float) Math.hypot(stick.x, stick.y));
        int side = control == PadLayout.Control.LEFT_STICK ? TouchLayout.LEFT : TouchLayout.RIGHT;
        gamepad.setStick(slot(), side, stick.x, stick.y);
        int flags = stickClick ? PadInput.buttonFlag(control) : 0;
        if (flags != state.flags && flags != 0) feedback(HapticFeedbackConstants.LONG_PRESS);
        boolean flagsChanged = flags != state.flags;
        state.flags = flags;
        // The touch is held, not pressed: it neither shows nor vibrates, and it goes when the finger lifts.
        int touch = stickTouch && stickPointer >= 0 ? PadInput.stickTouchFlag(control) : 0;
        gamepad.setButtons(slot(), flags | touch);
        stickMoved(flagsChanged, arcWasDrawn);
    }

    /**
     * A stick moved. The knob redraws on every move. The base also draws the amber arc from the stick
     * position, so it redraws only when that arc was or is drawn, or when the buttons it shows changed.
     */
    private void stickMoved(boolean flagsChanged, boolean arcWasDrawn) {
        applyAlpha();
        if (baseNeedsRedraw(flagsChanged, arcWasDrawn, state.stickX != 0 || state.stickY != 0)) invalidate();
        if (knobLayer != null) knobLayer.invalidate();
        changed.run();
    }

    /** Whether the base view must redraw: its glass and rim follow the state, its arc follows the stick push. */
    static boolean baseNeedsRedraw(boolean flagsChanged, boolean arcWasDrawn, boolean arcDrawn) {
        return flagsChanged || arcWasDrawn || arcDrawn;
    }

    private void updateMulti() {
        int flags = 0;
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (pointerIds[i] >= 0) flags |= pointerFlags[i];
        }
        state.flags = flags;
        gamepad.setButtons(slot(), flags);
        changed();
    }

    private int slotFor(int pointer) {
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (pointerIds[i] == pointer) return i;
        }
        return -1;
    }

    private void changed() {
        applyAlpha();
        redraw();
        changed.run();
    }

    private void feedback(int constant) {
        if (haptics) performHapticFeedback(constant);
    }

    /** The WASD stick's keys; null for every other control. */
    WasdKeys wasd() {
        return wasd;
    }

    PadPainter.State state() {
        return state;
    }
}
