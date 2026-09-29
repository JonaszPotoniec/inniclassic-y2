package com.themoon.y1.games;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

public class GamePlayerActivity extends Activity {
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String bundlePath = getIntent().getStringExtra("BUNDLE_PATH");
        if (bundlePath == null || bundlePath.isEmpty()) {
            Toast.makeText(this, com.themoon.y1.managers.LanguageManager.getInstance(this).t("Invalid game path"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        gameView = new GameView(this);
        gameView.setGameExitListener(new GameView.GameExitListener() {
            @Override
            public void onGameExit() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
            }
        });

        setContentView(gameView);
        boolean started = gameView.startGame(bundlePath);
        if (!started) {
            String err = FliwheelBridge.getLastError();
            String msg = com.themoon.y1.managers.LanguageManager.getInstance(this).t("Failed to start game");
            if (err != null && !err.isEmpty()) {
                msg += ":\n" + err;
            }
            Toast.makeText(this, msg + "\n(Szczegóły: y1_game_log.txt)", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        // Physical Top button (KEYCODE_BACK) is used for in-game UP / walking and double-tap Menu.
        // Long-pressing Center (>1.5s) exits to launcher.
    }

    @Override
    protected void onDestroy() {
        if (gameView != null) {
            gameView.stopGame();
        }
        super.onDestroy();
    }
}
