package net.rahar.screenshotocr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * This receiver handles wake up events. It allows us to have always running background service
 * that monitors the pictures directory.
 */
public class CommReceiver extends BroadcastReceiver {

    public void onReceive(Context paramContext, Intent paramIntent)
    {
        // Simply start the service when anything is received.
        paramContext.startService(new Intent(paramContext, BackgroundOCRService.class));
    }
}