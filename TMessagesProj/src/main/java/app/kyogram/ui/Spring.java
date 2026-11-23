package app.kyogram.ui;

import android.view.Choreographer;

import java.util.ArrayList;

/**
 * Everything that moves in Kyogram Keypad moves on one of these: a spring pulled towards its target,
 * stepped in small fixed steps every frame, the same maths (stiffness, damping, step) as the prototype
 * page, so the motion is the same on the phone.
 */
public class Spring {

    public interface Listener {
        void onSpring(float value, float velocity);
    }

    /** Reduced motion (Settings → Motion): everything jumps to where it's going. */
    public static boolean reduced;

    public float v, t, vel;
    private final float k, c;
    private final Listener listener;

    public Spring(float value, float stiffness, float damping, Listener listener) {
        this.v = this.t = value;
        this.k = stiffness;
        this.c = damping;
        this.listener = listener;
    }

    public Spring to(float target) {
        return to(target, false);
    }

    public Spring to(float target, boolean snap) {
        t = target;
        if (snap || reduced) {
            v = t;
            vel = 0;
            Loop.remove(this);
            if (listener != null) listener.onSpring(v, 0);
        } else {
            Loop.add(this);
        }
        return this;
    }

    public boolean moving() {
        return Loop.has(this);
    }

    /** Returns true once it has come to rest. */
    boolean step(float dt) {
        float a = -k * (v - t) - c * vel;
        vel += a * dt;
        v += vel * dt;
        boolean done = Math.abs(v - t) < .002f && Math.abs(vel) < .02f;
        if (done) {
            v = t;
            vel = 0;
        }
        if (listener != null) listener.onSpring(v, vel);
        return done;
    }

    static final class Loop implements Choreographer.FrameCallback {
        private static final Loop INSTANCE = new Loop();
        private static final ArrayList<Spring> springs = new ArrayList<>();
        private static final ArrayList<Spring> frame = new ArrayList<>();
        private static boolean running;
        private static long last;

        static void add(Spring s) {
            if (!springs.contains(s)) springs.add(s);
            if (!running) {
                running = true;
                last = System.nanoTime();
                Choreographer.getInstance().postFrameCallback(INSTANCE);
            }
        }

        static void remove(Spring s) {
            springs.remove(s);
        }

        static boolean has(Spring s) {
            return springs.contains(s);
        }

        @Override
        public void doFrame(long now) {
            float dt = Math.min(.034f, (now - last) / 1e9f);
            last = now;
            if (dt <= 0) dt = .016f;
            // * fixed small steps keep stiff springs steady on a slow frame
            int n = (int) Math.ceil(dt / .008f);
            dt /= n;
            frame.clear();
            frame.addAll(springs);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < frame.size(); j++) {
                    Spring s = frame.get(j);
                    if (s != null && springs.contains(s) && s.step(dt)) {
                        springs.remove(s);
                        frame.set(j, null);
                    }
                }
            }
            if (!springs.isEmpty()) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                running = false;
            }
        }
    }
}
