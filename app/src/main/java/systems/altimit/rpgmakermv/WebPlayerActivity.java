/*
 * Copyright (c) 2017-2019 Altimit Community Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or imp
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package systems.altimit.rpgmakermv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import systems.altimit.rpgmakermv.utils.SavefileUtils;

/**
 * Created by felixjones on 28/04/2017.
 */
public class WebPlayerActivity extends Activity {

    private static final String TOUCH_INPUT_ON_CANCEL = "TouchInput._onCancel();";
    private final static AtomicBoolean gameLoaded = new AtomicBoolean();
    private final static AtomicBoolean gameEnded = new AtomicBoolean();

    private Player mPlayer;
    private AlertDialog mQuitDialog;
    private int mSystemUiVisibility;

    @SuppressLint("ObsoleteSdkInt")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        gameLoaded.set(false);
        super.onCreate(savedInstanceState);
        if (BuildConfig.BACK_BUTTON_QUITS) {
            createQuitDialog();
        }

        mSystemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mSystemUiVisibility |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            mSystemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            mSystemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            mSystemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                mSystemUiVisibility |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }
        }

        mPlayer = PlayerHelper.create(this);

        mPlayer.setKeepScreenOn();

        setContentView(mPlayer.getView());

        if (!addBootstrapInterface(mPlayer)) {
            Uri.Builder projectURIBuilder = Uri.fromFile(new File(getString(R.string.mv_project_index))).buildUpon();
            Bootstrapper.appendQuery(projectURIBuilder, getString(R.string.query_noaudio));
            if (BuildConfig.SHOW_FPS) {
                Bootstrapper.appendQuery(projectURIBuilder, getString(R.string.query_showfps));
            }
            mPlayer.loadUrl(projectURIBuilder.build().toString());
            gameLoaded.set(true);
        }
        // 启动监听按键
        keyEventThread.start();
    }

    @Override
    public void onBackPressed() {
        if (BuildConfig.BACK_BUTTON_QUITS) {
            if (mQuitDialog != null) {
                mQuitDialog.show();
            } else {
                super.onBackPressed();
            }
        } else {
            mPlayer.evaluateJavascript(TOUCH_INPUT_ON_CANCEL, null);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onPause() {
        // export savefiles before go background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            SavefileUtils.makeEmptyImportFolder();
            SavefileUtils.exportSavefiles(mPlayer);
        }
        mPlayer.pauseTimers();
        mPlayer.onHide();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(mSystemUiVisibility);
        if (mPlayer != null) {
            mPlayer.resumeTimers();
            mPlayer.onShow();
        }
        // import savefiles during playing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (gameLoaded.get()) {
                if (SavefileUtils.importSavefiles(mPlayer)) {
                    SavefileUtils.cleanImportDir();
                    Dialog dialog = new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.complete))
                            .setMessage(getString(R.string.whetherRestartGame))
                            .setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    // restart Activity
                                    Intent intent = new Intent(getApplicationContext(), WebPlayerActivity.class);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(intent);
                                }
                            })
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialog) {
                                    dialog.dismiss();
                                }
                            })
                            .setNegativeButton(getString(R.string.no), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            }).create();
                    dialog.show();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPlayer.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // delete import folder when quit games
            SavefileUtils.deleteImportDir();
        }
        gameEnded.set(true);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    private void createQuitDialog() {
        String appName = BuildConfig.APP_NAME;
        String[] quitLines = getResources().getStringArray(R.array.quit_message);
        String cancelStr = getResources().getString(R.string.cancel);
        String quitStr = getResources().getString(R.string.quit);
        StringBuilder quitMessage = new StringBuilder();
        for (int ii = 0; ii < quitLines.length; ii++) {
            quitMessage.append(quitLines[ii].replace("$1", appName));
            if (ii < quitLines.length - 1) {
                quitMessage.append("\n");
            }
        }

        if (quitMessage.length() > 0) {
            mQuitDialog = new AlertDialog.Builder(this)
                    .setPositiveButton(cancelStr, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            getWindow().getDecorView().setSystemUiVisibility(mSystemUiVisibility);
                        }
                    })
                    .setNegativeButton(quitStr, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            WebPlayerActivity.super.onBackPressed();
                        }
                    })
                    .setMessage(quitMessage.toString())
                    .create();
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private static boolean addBootstrapInterface(Player player) {
        if (BuildConfig.BOOTSTRAP_INTERFACE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            new Bootstrapper(player);
            return true;
        }
        return false;
    }

    /**
     *
     */
    private static final class Bootstrapper extends PlayerHelper.Interface implements Runnable {

        private static Uri.Builder appendQuery(Uri.Builder builder, String query) {
            Uri current = builder.build();
            String oldQuery = current.getEncodedQuery();
            if (oldQuery != null && oldQuery.length() > 0) {
                query = oldQuery + "&" + query;
            }
            return builder.encodedQuery(query);
        }

        private static final String INTERFACE = "boot";
        private static final String PREPARE_FUNC = "prepare( webgl(), webaudio(), false )";

        private Player mPlayer;
        private Uri.Builder mURIBuilder;

        private Bootstrapper(Player player) {
            Context context = player.getContext();
            player.addJavascriptInterface(this, Bootstrapper.INTERFACE);

            mPlayer = player;
            mURIBuilder = Uri.fromFile(new File(context.getString(R.string.mv_project_index))).buildUpon();
            mPlayer.loadData(context.getString(R.string.webview_default_page));
        }

        @Override
        protected void onStart() {
            Context context = mPlayer.getContext();
            final String code = new String(Base64.decode(context.getString(R.string.webview_detection_source), Base64.DEFAULT), Charset.forName("UTF-8")) + INTERFACE + "." + PREPARE_FUNC + ";";
            mPlayer.post(new Runnable() {
                @Override
                public void run() {
                    mPlayer.evaluateJavascript(code, null);
                }
            });
        }

        @Override
        protected void onPrepare(boolean webgl, boolean webaudio, boolean showfps) {
            Context context = mPlayer.getContext();
            if (webgl && !BuildConfig.FORCE_CANVAS) {
                mURIBuilder = appendQuery(mURIBuilder, context.getString(R.string.query_webgl));
            } else {
                mURIBuilder = appendQuery(mURIBuilder, context.getString(R.string.query_canvas));
            }
            if (!webaudio || BuildConfig.FORCE_NO_AUDIO) {
                mURIBuilder = appendQuery(mURIBuilder, context.getString(R.string.query_noaudio));
            }
            if (showfps || BuildConfig.SHOW_FPS) {
                mURIBuilder = appendQuery(mURIBuilder, context.getString(R.string.query_showfps));
            }
            mPlayer.post(this);
        }

        @Override
        public void run() {
            mPlayer.removeJavascriptInterface(INTERFACE);
            mPlayer.loadUrl(mURIBuilder.build().toString());
            gameLoaded.set(true);
        }

    }

    // // 音量键模拟上下 监听
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (BuildConfig.VOLUME_AS_ARROW) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                try {
                    linkedBlockingQueue.put(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                try {
                    linkedBlockingQueue.put(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) { if (BuildConfig.VOLUME_AS_ARROW) { if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true;if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true; }return super.onKeyLongPress(keyCode, event); }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (BuildConfig.VOLUME_AS_ARROW) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                try {
                    linkedBlockingQueue.put(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                try {
                    linkedBlockingQueue.put(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_DOWN));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    // 音量键模拟上下 处理
    private final LinkedBlockingQueue<KeyEvent> linkedBlockingQueue = new LinkedBlockingQueue<>();
    private Thread keyEventThread = new Thread () {
        public void run () {
            Instrumentation inst=new Instrumentation();
            //noinspection ConditionalBreakInInfiniteLoop
            while (true) {
                try {
                    KeyEvent keyEvent = linkedBlockingQueue.take();
                    if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                        Thread.sleep(50);
                    }
                    inst.sendKeySync(keyEvent);
                } catch(Exception e) {
                    Log.e("sendKeyDownUpSync", e.toString());
                }
                if (gameEnded.get()) {
                    break;
                }
            }
        }
    };

}