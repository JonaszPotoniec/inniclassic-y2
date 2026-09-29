package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private static final String TAG = "GameView";

    private SurfaceHolder holder;
    private Thread renderThread;
    private volatile boolean isGameRunning = false;
    private volatile boolean isRenderThreadRunning = false;

    private FliwheelBridge bridge;
    private Bitmap frameBitmap;
    private final int[] pixelBuffer = new int[320 * 240];
    private final Rect srcRect = new Rect(0, 0, 320, 240);
    private final Rect dstRect = new Rect();
    private Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Vibrator vibrator;

    public interface GameExitListener {
        void onGameExit();
    }

    private GameExitListener exitListener;
    private final Handler backHandler = new Handler(Looper.getMainLooper());
    private long lastBackPressTime = 0;
    private boolean isCenterLongPressed = false;
    private final Runnable centerLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            isCenterLongPressed = true;
            if (bridge != null) {
                bridge.sendKey(KeyEvent.KEYCODE_DPAD_CENTER, false);
            }
            vibrate(40);
            stopGame();
            if (exitListener != null) {
                exitListener.onGameExit();
            }
        }
    };

    public GameView(Context context) {
        super(context);
        init();
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        holder = getHolder();
        holder.addCallback(this);
        frameBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
        try {
            vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception ignored) {}
    }

    private void vibrate(int ms) {
        try {
            if (vibrator != null) {
                vibrator.vibrate(ms);
            }
        } catch (Exception ignored) {}
    }

    public void setGameExitListener(GameExitListener listener) {
        this.exitListener = listener;
    }

    public synchronized boolean startGame(String bundlePath) {
        stopGame();
        bridge = new FliwheelBridge();
        boolean ok = bridge.init(getContext(), bundlePath);
        if (!ok) {
            bridge = null;
            return false;
        }

        isGameRunning = true;
        if (holder.getSurface().isValid()) {
            startRenderThread();
        }
        requestFocus();
        return true;
    }

    public synchronized void stopGame() {
        isGameRunning = false;
        stopRenderThread();
        backHandler.removeCallbacks(centerLongPressRunnable);
        if (bridge != null) {
            bridge.destroy();
            bridge = null;
        }
    }

    private synchronized void startRenderThread() {
        if (!isGameRunning || bridge == null) return;
        if (renderThread != null && renderThread.isAlive()) return;
        isRenderThreadRunning = true;
        renderThread = new Thread(this, "GameRenderLoop");
        renderThread.start();
    }

    private synchronized void stopRenderThread() {
        isRenderThreadRunning = false;
        if (renderThread != null) {
            try {
                renderThread.join(500);
            } catch (InterruptedException ignored) {}
            renderThread = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (isGameRunning && bridge != null) {
            startRenderThread();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Fit 320x240 (4:3) maintaining aspect ratio
        float srcAspect = 320f / 240f;
        float dstAspect = (float) width / (float) height;

        int targetW, targetH;
        if (dstAspect > srcAspect) {
            targetH = height;
            targetW = (int) (height * srcAspect);
        } else {
            targetW = width;
            targetH = (int) (width / srcAspect);
        }

        int offsetX = (width - targetW) / 2;
        int offsetY = (height - targetH) / 2;
        dstRect.set(offsetX, offsetY, offsetX + targetW, offsetY + targetH);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderThread();
    }

    @Override
    public void run() {
        long framePeriodNs = 16_666_667L; // 60 FPS (~16.6ms)

        while (isRenderThreadRunning && isGameRunning && bridge != null) {
            long startTime = System.nanoTime();

            boolean updated = bridge.renderFrame(pixelBuffer);
            if (updated) {
                frameBitmap.setPixels(pixelBuffer, 0, 320, 0, 0, 320, 240);

                Canvas canvas = holder.lockCanvas();
                if (canvas != null) {
                    try {
                        canvas.drawColor(Color.BLACK);
                        canvas.drawBitmap(frameBitmap, srcRect, dstRect, paint);
                    } finally {
                        holder.unlockCanvasAndPost(canvas);
                    }
                }

                long elapsed = System.nanoTime() - startTime;
                long sleepTimeNs = framePeriodNs - elapsed;
                if (sleepTimeNs > 1_000_000L) {
                    try {
                        Thread.sleep(sleepTimeNs / 1_000_000L);
                    } catch (InterruptedException ignored) {}
                }
            } else {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    public boolean handleWheel(float delta) {
        if (bridge != null) {
            bridge.sendWheel(delta);
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Top button (KEYCODE_BACK):
        // Single press / hold = UP (immediate response for game steering / walking; NEVER exits)
        // Double-tap (<350ms) = In-game MENU (Pause / In-game options)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                long now = SystemClock.uptimeMillis();
                if (now - lastBackPressTime < 350) {
                    // Double-tap TOP button: trigger in-game MENU / Pause!
                    if (bridge != null) {
                        bridge.sendKey(KeyEvent.KEYCODE_BACK, false); // release UP
                        bridge.sendKey(KeyEvent.KEYCODE_MENU, true);
                        postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (bridge != null) {
                                    bridge.sendKey(KeyEvent.KEYCODE_MENU, false);
                                }
                            }
                        }, 100);
                    }
                    lastBackPressTime = 0;
                    return true;
                }
                lastBackPressTime = now;

                // Send UP immediately to game
                if (bridge != null) {
                    bridge.sendKey(KeyEvent.KEYCODE_BACK, true);
                }
            }
            return true;
        }

        // Center button: single press = Action/Select; hold (>1.5s) = Exit to InniClassic launcher
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) {
                isCenterLongPressed = false;
                backHandler.removeCallbacks(centerLongPressRunnable);
                backHandler.postDelayed(centerLongPressRunnable, 1500);
                if (bridge != null) {
                    bridge.sendKey(KeyEvent.KEYCODE_DPAD_CENTER, true);
                }
            }
            return true;
        }

        // Innioasis clickwheel rotational events:
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            handleWheel(-2.0f); // CCW (previous / up)
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            handleWheel(2.0f);  // CW (next / down)
            return true;
        }

        if (bridge != null) {
            bridge.sendKey(keyCode, true);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (bridge != null) {
                bridge.sendKey(KeyEvent.KEYCODE_BACK, false); // release UP
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            backHandler.removeCallbacks(centerLongPressRunnable);
            if (!isCenterLongPressed) {
                if (bridge != null) {
                    bridge.sendKey(KeyEvent.KEYCODE_DPAD_CENTER, false);
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return true;
        }

        if (bridge != null) {
            bridge.sendKey(keyCode, false);
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }
}
