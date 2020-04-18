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

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import systems.altimit.rpgmakermv.utils.SavefileUtils;

import static systems.altimit.rpgmakermv.MyApplication.restarted;

/**
 * Created by felixjones on 28/04/2017.
 */
public class WebPlayerActivity extends Activity {

    private static final String TOUCH_INPUT_ON_CANCEL = "TouchInput._onCancel();";
    private final static AtomicBoolean gameLoaded = new AtomicBoolean();
    private static boolean runningFlag = false;
    public static String obbBasePath = null;
    private static volatile long lastVolumeUpKeyDownTimeMs;// ms
    private static volatile long lastVolumeDownKeyDownTimeMs;
    private static volatile boolean volumeUpKeyPressed;
    private static volatile boolean volumeDownKeyPressed;
    private static final long longPressTimeOutMs = 25; //50ms
    Vibrator vibrator; // 震动马达

    Dialog saveFileHelpDialog = null;

    private Player mPlayer;
    private AlertDialog mQuitDialog;
    private int mSystemUiVisibility;

    private Display display;

    private StorageManager storageManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        gameLoaded.set(false);
        runningFlag = true;
        vibrator = (Vibrator) getSystemService(Service.VIBRATOR_SERVICE);
        storageManager = (StorageManager) getApplicationContext()
                .getSystemService(Context.STORAGE_SERVICE);

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

        display = getWindowManager().getDefaultDisplay();

        mPlayer = PlayerHelper.create(this);

        mPlayer.setKeepScreenOn();

        setContentView(mPlayer.getView());

        new Bootstrapper(mPlayer);
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
                    if (saveFileHelpDialog != null) {
                        saveFileHelpDialog.dismiss();
                    }
                    Dialog dialog = new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.complete))
                            .setMessage(getString(R.string.whetherRestartGame))
                            .setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    restarted = true;
                                    SavefileUtils.cleanImportDir();
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
            } else {
                if (!restarted) {
                    String path = Objects.requireNonNull(getExternalCacheDir()).getPath();
                    int index = path.indexOf("/Android/data");
                    if (index != -1) {
                        path = getString(R.string.internalStorage) + path.substring(index);
                    }
                    saveFileHelpDialog = new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.toastSaveFileLocation))
                            .setMessage(path + "\n\n" + getString(R.string.explanImportExport))
                            .setPositiveButton(getString(R.string.complete), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                            .setCancelable(false)
                            .create();
                    saveFileHelpDialog.show();
                } else {
                    if (saveFileHelpDialog != null) {
                        saveFileHelpDialog.dismiss();
                    }
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
        runningFlag = false;
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

    private Uri.Builder appendQuery(Uri.Builder builder, String query) {
        Uri current = builder.build();
        String oldQuery = current.getEncodedQuery();
        if (oldQuery != null && oldQuery.length() > 0) {
            query = oldQuery + "&" + query;
        }
        return builder.encodedQuery(query);
    }

    /**
     *
     */
    private final class Bootstrapper extends PlayerHelper.Interface implements Runnable {

        private static final String INTERFACE = "boot";
        private static final String PREPARE_FUNC = "prepare( webgl(), webaudio(), false )";

        private Player mPlayer;
        private Uri.Builder mURIBuilder;

        private Bootstrapper(Player player) {
            final Context context = player.getContext();
            player.addJavascriptInterface(this, Bootstrapper.INTERFACE);

            mPlayer = player;

            String packageName = BuildConfig.APPLICATION_ID;
            String filePath = Environment.getExternalStorageDirectory()
                    + "/Android/obb/" + packageName + "/" + "main."
                    + BuildConfig.VERSION_CODE + "." + packageName + ".obb";
            final File mainFile = new File(filePath);
            if (mainFile.exists()) {
                Log.d("STORAGE", "FILE: " + filePath + " Exists");
            } else {
                Log.d("STORAGE", "FILE: " + filePath + " DOESNT EXIST");
            }

            final String key = null;
            if (!storageManager.isObbMounted(mainFile.getAbsolutePath())) {
                if (mainFile.exists()) {
                    if (storageManager.mountObb(mainFile.getAbsolutePath(), key,
                            new OnObbStateChangeListener() {
                                @Override
                                public void onObbStateChange(String path, int state) {
                                    super.onObbStateChange(path, state);
                                    Log.d("PATH = ", path);
                                    Log.d("STATE = ", state + "");
                                    if (state == OnObbStateChangeListener.MOUNTED) {
                                        Log.d("STORAGE", "-->MOUNTED");
                                        obbBasePath = storageManager.getMountedObbPath(path);
                                        mURIBuilder = Uri.fromFile(new File(obbBasePath + context.getString(R.string.mv_project_index_obb))).buildUpon();
                                    } else {
                                        Log.d("##", "Path: " + path + "; state: " + state);
                                        mURIBuilder = Uri.fromFile(new File(context.getString(R.string.mv_project_index))).buildUpon();
                                    }
                                    mPlayer.loadData(context.getString(R.string.webview_default_page));
                                }
                            })) {
                        Log.d("STORAGE_MNT", "SUCCESSFULLY QUEUED");
                    } else {
                        Log.d("STORAGE_MNT", "FAILED");
                    }
                } else {
                    Log.d("STORAGE", "Patch file not found");
                    mURIBuilder = Uri.fromFile(new File(context.getString(R.string.mv_project_index))).buildUpon();
                    mPlayer.loadData(context.getString(R.string.webview_default_page));
                }
            } else {
                obbBasePath = storageManager.getMountedObbPath(mainFile.getAbsolutePath());
                mURIBuilder = Uri.fromFile(new File(obbBasePath + context.getString(R.string.mv_project_index_obb))).buildUpon();
                mPlayer.loadData(context.getString(R.string.webview_default_page));
            }
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

    // 记录音量键按下的时间
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (BuildConfig.VOLUME_AS_ARROW) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (!volumeUpKeyPressed) {
                    volumeUpKeyPressed = true;
                    lastVolumeUpKeyDownTimeMs = System.currentTimeMillis();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (!volumeDownKeyPressed) {
                    volumeDownKeyPressed = true;
                    lastVolumeDownKeyDownTimeMs = System.currentTimeMillis();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // 长按音量键发送左右, 并识别屏幕方向
    // 当按键松开时, 如果是短按, 那么发送方向键上下
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (BuildConfig.VOLUME_AS_ARROW) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                volumeUpKeyPressed = false;
                long volumeUpKeyUpTimeNs = System.currentTimeMillis();
                if (volumeUpKeyUpTimeNs - lastVolumeUpKeyDownTimeMs < longPressTimeOutMs) {
                    System.err.println(volumeUpKeyUpTimeNs - lastVolumeUpKeyDownTimeMs);
                    try {
                        keyCodeBlockingQueue.put(KeyEvent.KEYCODE_DPAD_UP);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {// 长按
                    int rotation = display.getRotation();
                    if (Surface.ROTATION_90 == rotation) {
                        try {
                            keyCodeBlockingQueue.put(KeyEvent.KEYCODE_DPAD_LEFT);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else if (Surface.ROTATION_270 == rotation) {
                        try {
                            keyCodeBlockingQueue.put(KeyEvent.KEYCODE_DPAD_RIGHT);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                volumeDownKeyPressed = false;
                long volumeDownKeyUpTimeNs = System.currentTimeMillis();
                if (volumeDownKeyUpTimeNs - lastVolumeDownKeyDownTimeMs < longPressTimeOutMs) {
                    try {
                        keyCodeBlockingQueue.put(KeyEvent.KEYCODE_DPAD_DOWN);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {// 长按
                    int rotation = display.getRotation();
                    if (Surface.ROTATION_90 == rotation) {
                        try {
                            keyCodeBlockingQueue.put(KeyEvent.KEYCODE_DPAD_RIGHT);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else if (Surface.ROTATION_270 == rotation) {
                        try {
                            keyCodeBlockingQueue.put(KeyEvent.KEYCODE_DPAD_LEFT);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    // 音量键模拟的按键 处理线程
    private final LinkedBlockingQueue<Integer> keyCodeBlockingQueue = new LinkedBlockingQueue<>();
    private Thread keyEventThread = new Thread() {
        public void run() {
            if (BuildConfig.VOLUME_AS_ARROW) {
                @SuppressWarnings("UnusedAssignment")
                Instrumentation inst = new Instrumentation();
                //noinspection ConstantConditions
                while (runningFlag && BuildConfig.VOLUME_AS_ARROW) {
                    try {
                        @SuppressWarnings("UnusedAssignment")
                        int keyCode = keyCodeBlockingQueue.take();
                        // 震动8毫秒
                        vibrator.vibrate(4);
                        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                        Thread.sleep(80);
                        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                    } catch (Exception e) {
                        Log.e("sendKeyDownUpSync", e.toString());
                    }
                }
            }
        }
    };

}