package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;

/**
 * Kyogram: the unfold, as the iPhone Duo does it. When the big screen comes on, the half that swings
 * open starts frosted and shaded, most at its outer edge and hardly at the hinge, and clears as the
 * phone opens: with the hinge's angle where the phone reports it, else by itself in about half a second.
 * The layout under it doesn't move; only the frost lifts.
 *
 * The frost is a blurred picture of the new layout, taken once it's laid out, laid over it with a
 * mask that fades it out towards the hinge; the shade is a dark gradient on top.
 */
public class KyogramFoldReveal extends View implements SensorEventListener {

    private static final float CURVE = 1.35f; // * how fast frost and shade grow away from the hinge
    private static final int STOPS = 9;

    private final Activity activity;
    private final View content;
    private final boolean openingLeft;

    private Bitmap frost;
    private float progress = 1f; // * 1 folded (all frost), 0 flat open (none)
    private boolean useHinge;
    private ValueAnimator animator;
    private SensorManager sensors;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint();
    private final Paint shadePaint = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    /** Called when the screen turns from the cover screen to the big one. */
    public static void show(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        View content = activity.findViewById(android.R.id.content);
        if (!(decor instanceof ViewGroup) || content == null) {
            return;
        }
        ViewGroup root = (ViewGroup) decor;
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            if (root.getChildAt(i) instanceof KyogramFoldReveal) {
                ((KyogramFoldReveal) root.getChildAt(i)).finish();
            }
        }
        root.addView(new KyogramFoldReveal(activity, content), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private KyogramFoldReveal(Activity activity, View content) {
        super(activity);
        this.activity = activity;
        this.content = content;
        // * the cover screen sits on the half that swings open, like a book's front cover: the left one,
        // * as the phone is held unfolded (a right-to-left layout doesn't change the phone)
        this.openingLeft = true;
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        startHinge(activity);
    }

    private void startHinge(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor hinge = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) : null;
        if (hinge != null) {
            useHinge = true;
            sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // * if the hinge says nothing soon (or the phone has none), clear by itself; if it does speak but
        // * the phone stops short of flat, finish the rest after a while all the same
        postDelayed(quiet, 120);
        postDelayed(late, 1600);
    }

    private final Runnable quiet = () -> {
        if (!useHinge || progress >= 1f) {
            clearBy(450);
        }
    };
    private final Runnable late = () -> clearBy(250);

    private void clearBy(long duration) {
        if (animator != null || progress <= 0f) {
            return;
        }
        stopHinge();
        animator = ValueAnimator.ofFloat(progress, 0f);
        animator.setDuration(duration);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                finish();
            }
        });
        animator.start();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (animator != null) {
            return;
        }
        float angle = event.values[0]; // * 180 is flat open
        float p = Math.max(0f, Math.min(1f, (180f - angle) / 90f));
        progress = Math.min(progress, p); // * frost only ever lifts
        invalidate();
        if (angle >= 172f) {
            clearBy(180);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void stopHinge() {
        if (sensors != null) {
            sensors.unregisterListener(this);
            sensors = null;
        }
    }

    private void finish() {
        stopHinge();
        removeCallbacks(quiet);
        removeCallbacks(late);
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
        }
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        if (frost != null) {
            frost.recycle();
            frost = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if (frost == null) {
            // * the new layout is laid out by the time this first draws: picture it once, small and blurred
            frost = AndroidUtilities.makeBlurBitmap(content, 14f, 14);
            if (frost == null) {
                finish();
                return;
            }
        }

        float motion = smoothstep(progress);
        if (motion <= 0f) {
            return;
        }

        float hinge = w / 2f;
        float left = openingLeft ? 0 : hinge;
        float right = openingLeft ? hinge : w;
        float edgeX = openingLeft ? 0 : w; // * the outer edge, where frost and shade are strongest

        // * the frost: the blurred picture, faded out towards the hinge by the mask
        int layer = canvas.saveLayerAlpha(left, 0, right, h, (int) (255 * motion));
        src.set(0, 0, frost.getWidth(), frost.getHeight());
        dst.set(0, 0, w, h);
        canvas.drawBitmap(frost, src, dst, bitmapPaint);
        maskPaint.setShader(gradient(hinge, edgeX, 0f, 1f, Color.BLACK, 1f));
        canvas.drawRect(left, 0, right, h, maskPaint);
        canvas.restoreToCount(layer);

        // * the shade: starts a fifth of the way out, twice as strong as the frost, black at the most
        shadePaint.setShader(gradient(hinge, edgeX, .2f, 2f * motion, Color.BLACK, 1f));
        canvas.drawRect(left, 0, right, h, shadePaint);
    }

    /** From the hinge (0) to the outer edge (1): alpha = scale * ((x - start) / (1 - start)) ^ CURVE. */
    private static LinearGradient gradient(float fromX, float toX, float start, float scale, int color, float max) {
        int[] colors = new int[STOPS];
        float[] positions = new float[STOPS];
        for (int i = 0; i < STOPS; i++) {
            float x = i / (float) (STOPS - 1);
            float t = Math.max(0f, (x - start) / (1f - start));
            float a = Math.min(max, scale * (float) Math.pow(t, CURVE));
            colors[i] = Color.argb((int) (255 * a), Color.red(color), Color.green(color), Color.blue(color));
            positions[i] = x;
        }
        return new LinearGradient(fromX, 0, toX, 0, colors, positions, Shader.TileMode.CLAMP);
    }

    private static float smoothstep(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }
}
