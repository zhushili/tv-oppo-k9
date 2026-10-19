package com.gathin.tvfix;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Method;

public class FixActivity extends Activity {
    private static final String TAG = "TVFix";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String result;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "tvapisvc");
            Log.i(TAG, "binder=" + binder);
            Class<?> stub = Class.forName("com.oneplus.tvapi.ITvApi$Stub");
            Object api = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> itf = Class.forName("com.oneplus.tvapi.ITvApi");
            Method m = itf.getMethod("disPatchCommand", String.class, int.class, String[].class, boolean.class);
            Object r = m.invoke(api, "ModuleDevice", 30,
                    new String[]{"service.tvapi.enable_launcher_install", "1"}, false);
            String prop = String.valueOf(Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, "service.tvapi.enable_launcher_install", "?"));
            result = "disPatchCommand -> " + r + " prop=" + prop;
        } catch (Throwable t) {
            result = "FAILED: " + t;
            Log.e(TAG, result, t);
        }
        Log.i(TAG, result);
        Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        finish();
    }
}
