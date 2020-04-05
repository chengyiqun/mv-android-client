package systems.altimit.rpgmakermv;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import systems.altimit.rpgmakermv.utils.SavefileUtils;

public class MyApplication extends Application {
    public static boolean restarted = false;
    private Context context;
    @Override
    public void onCreate() {
        super.onCreate();
        context = this.getApplicationContext();
        initContext();
    }


    private void initContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            SavefileUtils.setAppContext(this.getApplicationContext());
        }
    }

    public Context getContext() {
        return context;
    }

}
