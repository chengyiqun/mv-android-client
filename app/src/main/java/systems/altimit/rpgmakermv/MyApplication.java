package systems.altimit.rpgmakermv;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import java.sql.Savepoint;

import systems.altimit.rpgmakermv.utils.SavefileUtils;

public class MyApplication extends Application {
    private Context context;
    @Override
    public void onCreate() {
        super.onCreate();
        context = this.getApplicationContext();
        initContext();
    }


    private void initContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String path = getExternalFilesDir(null).getPath(); // can't be null
            Toast.makeText(getApplicationContext(), getString(R.string.toastSaveFileLocation) + path, Toast.LENGTH_LONG).show();
            SavefileUtils.setAppContext(this.getApplicationContext());
        }
    }

    public Context getContext() {
        return context;
    }

}
