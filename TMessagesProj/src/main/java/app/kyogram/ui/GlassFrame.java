package app.kyogram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.widget.FrameLayout;

/** A rounded glass panel that holds views: the header, the message box, the menu, the quote. */
public class GlassFrame extends FrameLayout {

    private final Style.Glass glass;
    private float radius;
    private float[] radii;
    private final int[] loc = new int[2];
    private final Path clip = new Path();

    public GlassFrame(Context context, float radiusPx) {
        this(context, radiusPx, new Style.Glass());
    }

    public GlassFrame(Context context, float radiusPx, Style.Glass glass) {
        super(context);
        this.radius = Style.px(radiusPx);
        this.glass = glass;
        setWillNotDraw(false);
        setClipChildren(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        getLocationInWindow(loc);
        glass.draw(canvas, getWidth(), getHeight(), radius, loc[0], loc[1]);
        super.onDraw(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        clip.reset();
        clip.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
