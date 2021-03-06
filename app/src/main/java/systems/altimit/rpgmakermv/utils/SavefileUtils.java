package systems.altimit.rpgmakermv.utils;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;
import android.webkit.ValueCallback;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.snackbar.Snackbar;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

import systems.altimit.rpgmakermv.BuildConfig;
import systems.altimit.rpgmakermv.Player;
import systems.altimit.rpgmakermv.R;
import systems.altimit.rpgmakermv.WebPlayerActivity;

import static android.content.Context.MODE_PRIVATE;
import static systems.altimit.rpgmakermv.MyApplication.restarted;

@RequiresApi(Build.VERSION_CODES.KITKAT)
public class SavefileUtils {
    private static final String TAG = SavefileUtils.class.getName();
    private static final String IMPORT_FOLDER_NAME = "import";
    /**
     * app上下文
     */
    private static Context appContext;

    /**
     * 在Application类中初始化上下文
     *
     * @param appContext 传入的上下文对象
     */
    public static void setAppContext(Context appContext) {
        SavefileUtils.appContext = appContext;
    }

    /**
     * export android rpgmv savefiles into private folder
     *
     * @param player webView player
     */
    public static void exportSavefiles(Player player) {
        String script = "javascript:(function readLocalStorage(){\n" +
                "    var size = localStorage.length;\n" +
                "    var row = new Array();\n" +
                "    for(var i=0;i<size;i++){\n" +
                "        var col = new Array();\n" +
                "        col[0]= window.localStorage.key(i);\n" +
                "        col[1]= window.localStorage.getItem(col[0]);\n" +
                "        row[i] = col;\n" +
                "    }\n" +
                "    return row.toString();\n" +
                "})()";
        player.evaluateJavascript(script, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value != null) {
                    value = value.replace("\"", "");
                    String[] split = value.split(",");
                    if (split.length % 2 == 0) {
                        SimpleDateFormat sdf = new SimpleDateFormat(appContext.getString(R.string.dateFormat), Locale.ENGLISH);
                        String dateStr = sdf.format(new Date());
                        for (int i = 0; i < split.length; i++) {
                            if (i % 2 == 0) {
                                String fileName = split[i].replace(appContext.getString(R.string.LocalStorageKeyPrefix), "").toLowerCase().trim() + appContext.getString(R.string.saveFileSuffix);
                                String content = split[i + 1];
                                // writeSaveFiles
                                SavefileUtils.writeSaveFiles(dateStr, fileName, content);
                            }
                        }
                        // cleanSaveFiles
                        SavefileUtils.cleanSavefiles();
                    }
                }
            }
        });
    }

    /**
     * 把localStorage的存档写入sd卡的app私有目录 /sdcard/Android/xxx.xxx.xxx/file/export/年月日时分秒/xxx.rpgsave
     *
     * @param fileSimpleName 文件名
     * @param content        文件内容
     */
    private static void writeSaveFiles(String dateStr, String fileSimpleName, String content) {
        // String to file
        File externalFilesDir = appContext.getExternalCacheDir();
        Log.d(TAG, "fileSimpleName = " + fileSimpleName);
        Log.d(TAG, "content = " + content);
        assert externalFilesDir != null;
        String fileName = externalFilesDir.getPath() + "/export/" + dateStr + "/" + fileSimpleName;
        Log.d(TAG, "fileName = " + fileName);
        try {
            FileUtils.writeStringToFile(new File(fileName), content, "UTF-8");
        } catch (IOException e) {
            Log.e(TAG, fileName + "can not be created");
            e.printStackTrace();
        }
    }


    /**
     * 当存档导出文件夹数量超过时, 删除多余的存档, 避免存档爆炸.
     */
    private static void cleanSavefiles() {
        int num = BuildConfig.SAVE_FILE_EXPORT_NUM + 1;
        if (num < 1) {
            num = 1;
        }
        File externalFilesDir = appContext.getExternalCacheDir();
        assert externalFilesDir != null;
        File exportDir = new File(externalFilesDir.getPath() + "/export/");
        Collection<File> files = FileUtils.listFilesAndDirs(exportDir, DirectoryFileFilter.DIRECTORY, DirectoryFileFilter.DIRECTORY);
        if (files.size() > num) {
            Iterator<File> filesIt = files.iterator();
            filesIt.next();
            for (int i = 1, until = files.size() - num; i < until; i++) {
                File file = filesIt.next();
                try {
                    FileUtils.forceDelete(file);
                } catch (IOException e) {
                    Log.e(TAG, file + "can not be forceDeleted");
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 在app启动时, 创建import文件夹, 便于理解
     */
    public static File makeEmptyImportFolder() {
        File externalFilesDir = appContext.getExternalCacheDir();
        assert externalFilesDir != null;
        File importDir = new File(externalFilesDir.getPath() + "/" + IMPORT_FOLDER_NAME + "/");
        //noinspection ResultOfMethodCallIgnored
        importDir.mkdirs();
        return importDir;
    }

    /**
     * importSaveFile from app private dir
     * just supported since android 4.4
     *
     * @param player webViewPlayer
     */
    public static boolean importSavefiles(final Player player) {
        boolean imported = false;
        File externalFilesDir = appContext.getExternalCacheDir();
        assert externalFilesDir != null;
        File importDir = new File(externalFilesDir.getPath() + "/" + IMPORT_FOLDER_NAME + "/");
        final Collection<File> savefileCollection = FileUtils.listFiles(importDir, new String[]{"rpgsave"}, false);
        if (savefileCollection.size() > 0) {
            // 导入前清空
            player.evaluateJavascript("javascript:(function(){\n" +
                    "localStorage.clear()\n" +
                    "return 'OK'\n" +
                    "})()", new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    Snackbar.make(player.getView(), appContext.getString(R.string.clear_save_file_msg), 1000).show();
                    for (File file : savefileCollection) {
                        char[] chars = file.getName().toCharArray();
                        if (chars[0] >= 'a' && chars[0] <= 'z') {
                            chars[0] = (char) (chars[0] - 32);
                        }
                        final String key = appContext.getString(R.string.LocalStorageKeyPrefix) + " " + new String(chars).replace(appContext.getString(R.string.saveFileSuffix), "");
                        String content = "";
                        try {
                            content = FileUtils.readFileToString(file, "UTF-8");
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        if (!"".equals(content)) {
                            // 写入 localStorage
                            String script = "javascript:" +
                                    "(function loadLocalStore(){\n" +
                                    "localStorage.setItem(\"" + key + "\",\"" + content + "\")\n"
                                    +
                                    "return 'OK'\n"
                                    +
                                    "})()";
                            player.evaluateJavascript(script, new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    Log.i(TAG, value);
                                    Snackbar.make(player.getView(), key + appContext.getString(R.string.loadComplete), 1000).show();
                                }
                            });
                        }
                    }
                }
            });

            imported = true;
        }
        return imported;
    }

    /**
     * clean the import dir
     */
    public static void cleanImportDir() {
        File externalFilesDir = appContext.getExternalCacheDir();
        assert externalFilesDir != null;
        File importDir = new File(externalFilesDir.getPath() + "/" + IMPORT_FOLDER_NAME + "/");
        if (importDir.exists()) {
            try {
                FileUtils.cleanDirectory(importDir);
                Toast.makeText(appContext, appContext.getString(R.string.importFolderCleaned), Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void deleteImportDir() {
        File externalFilesDir = appContext.getExternalCacheDir();
        assert externalFilesDir != null;
        File importDir = new File(externalFilesDir.getPath() + "/" + IMPORT_FOLDER_NAME + "/");
        if (importDir.exists()) {
            try {
                FileUtils.forceDelete(importDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void importAuthorSupport(final Context context, String url,final Player mPlayer) {
        if (url.contains("index.html")) {
            // 初始化自带的存档
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try {
                    // 初始化配置
                    final SharedPreferences mSpf = context.getSharedPreferences("globalSettings",MODE_PRIVATE);
                    boolean embedSaveFileLoad = mSpf.getBoolean("embedSaveFileLoad", false);
                    if (!embedSaveFileLoad) {
                        final File importSaveFileDir = SavefileUtils.makeEmptyImportFolder();
                        final String embedSaveFileDir;
                        // 加载assets的情况 TODO obb的情况暂时不想写
                        embedSaveFileDir = context.getString(R.string.mv_project_index).replace("//android_asset/","")
                                .replace("index.html", "") + "save";
                        final AssetManager assetManager = context.getAssets();
                        final String[] filenameListByAsstes = assetManager.list(embedSaveFileDir);
                        // TODO obb找到存档的情况, 使用或者关系
                        if (filenameListByAsstes != null && filenameListByAsstes.length > 0) {
                            Dialog dialog = new AlertDialog.Builder(context)
                                    .setTitle(context.getString(R.string.authorSupportTitle))
                                    .setMessage(context.getString(R.string.authorSupportMsg))
                                    .setPositiveButton(context.getString(R.string.yes), new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            // 拷贝文件
                                            // assets
                                            if (filenameListByAsstes != null && filenameListByAsstes.length > 0) {
                                                for (String s : filenameListByAsstes) {
                                                    try {
                                                        InputStream open = assetManager.open(embedSaveFileDir + '/' + s);
                                                        FileUtils.copyInputStreamToFile(open, new File(importSaveFileDir.getPath() + '/' + s));
                                                    } catch (IOException e) {
                                                        e.printStackTrace();
                                                    }
                                                }
                                            }
                                            // TODO 拷贝obb的存档文件


                                            if (SavefileUtils.importSavefiles(mPlayer)) {
                                                Dialog dialog1 = new AlertDialog.Builder(context)
                                                        .setTitle(context.getString(R.string.complete))
                                                        .setMessage(context.getString(R.string.whetherRestartGame))
                                                        .setPositiveButton(context.getString(R.string.yes), new DialogInterface.OnClickListener() {
                                                            @Override
                                                            public void onClick(DialogInterface dialog, int which) {
                                                                SharedPreferences.Editor editor = mSpf.edit();
                                                                editor.putBoolean("embedSaveFileLoad",true);
                                                                editor.apply();
                                                                dialog.dismiss();
                                                                restarted = true;
                                                                SavefileUtils.cleanImportDir();
                                                                // restart Activity
                                                                Intent intent = new Intent(context, WebPlayerActivity.class);
                                                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                                                                context.startActivity(intent);
                                                            }
                                                        })
                                                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                                            @Override
                                                            public void onDismiss(DialogInterface dialog) {
                                                                dialog.dismiss();
                                                            }
                                                        })
                                                        .setNegativeButton(context.getString(R.string.no), new DialogInterface.OnClickListener() {
                                                            @Override
                                                            public void onClick(DialogInterface dialog, int which) {
                                                                dialog.dismiss();
                                                            }
                                                        }).create();
                                                dialog1.show();
                                            }
                                        }
                                    })
                                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                        @Override
                                        public void onDismiss(DialogInterface dialog) {
                                            dialog.dismiss();
                                        }
                                    })
                                    .setNegativeButton(context.getString(R.string.no), new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            SharedPreferences.Editor editor = mSpf.edit();
                                            editor.putBoolean("embedSaveFileLoad",true);
                                            editor.apply();
                                            dialog.dismiss();
                                        }
                                    }).create();
                            dialog.show();
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
