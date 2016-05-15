package net.rahar.screenshotocr;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Environment;
import android.os.FileObserver;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.IOException;

/**
 * This service handles both screenshot folder monitoring and OCR
 */
public class BackgroundOCRService extends IntentService {
    private static final String TAG = "BackgroundOCRService";
    private static FileObserver fileObserver;

    // Tesseract detection language
    private String LANG = "eng";

    // These are normalized coordinates of the box that contains the address text
    private float TEXT_LEFT_X = 388f/1440f;
    private float TEXT_TOP_LEFT_Y = 352f/2392f;
    private float TEXT_BOT_LEFT_Y = 808f/2392f;

    // This is the normalized y coordinate of the bottom button that contains the "END TRIP" text
    // it is used to detect whether the screenshot is valid or not
    private float BUTTON_BOT_LEFT_Y = 2148f/2392f;

    public BackgroundOCRService() {
        super("BackgroundOCRService");

    }

    private void doOcr(String pathToImage) {
        showNotification("OCR", "Recognizing... Please wait");

        // Getting the image from the path
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        Bitmap fullImage = BitmapFactory.decodeFile(pathToImage, options);

        // Tesseract library initialization
        TessBaseAPI baseApi;
        baseApi = new TessBaseAPI();
        baseApi.setDebug(true);
        // ExternalFilesDir should contain the tessdata subdirectory with training data file.
        baseApi.init(getApplicationContext().getExternalFilesDir(null).getAbsolutePath(), LANG);

        try {
            // Orienting the image if required
            ExifInterface exif = new ExifInterface(pathToImage);
            int exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotate = 0;
            switch (exifOrientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    rotate = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    rotate = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    rotate = 270;
                    break;
            }
            Log.v(TAG, "Rotation: " + rotate);
            int width = fullImage.getWidth();
            int height = fullImage.getHeight();
            if (rotate != 0) {
                // Getting width & height of the given image.
                // Setting pre rotate
                Matrix mtx = new Matrix();
                mtx.preRotate(rotate);
                // Rotating Bitmap
                fullImage = Bitmap.createBitmap(fullImage, 0, 0, width, height, mtx, false);
                // tesseract req. ARGB_8888
                fullImage = fullImage.copy(Bitmap.Config.ARGB_8888, true);
            }
            // Here extracting first the button area
            int y = (int)(BUTTON_BOT_LEFT_Y*height);
            Bitmap buttonPart = Bitmap.createBitmap(fullImage, 0, y, width, (int) ((1 - BUTTON_BOT_LEFT_Y) * height));

            // Providing tesser with the button image
            baseApi.setImage(buttonPart);

            // Reading the text from the image
            String buttonText = baseApi.getUTF8Text();
            Log.v(TAG, "OCR Result button: " + buttonText);

            // Checking if the screenshot is valid
            if(buttonText != null && buttonText.toLowerCase().contains("end trip")) {
                // If the screenshot if valid, extracting the text area from the image
                int x = (int) (TEXT_LEFT_X * width);
                y = (int) (TEXT_TOP_LEFT_Y * height);
                Bitmap addressPart = Bitmap.createBitmap(fullImage, x, y, width - x, (int) ((TEXT_BOT_LEFT_Y - TEXT_TOP_LEFT_Y) * height));

                // Reading the text in the address area
                baseApi.setImage(addressPart);
                String address = baseApi.getUTF8Text();
                Log.v(TAG, "OCR Result ADDRESS: " + address);

                // Showing notification to the user with the detected text
                showNotification("OCR Result", address);

                // Saving the detected text
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(BackgroundOCRService.this);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("LASTTEXT", address);
                editor.commit();

            }
            else {
                // If hte screenshot is invalid - showing notification about invalid screenshot
                showNotification("Invalid screenshot", "Invalid screenshot");
            }
            baseApi.end();
        } catch (IOException e) {
            Log.e(TAG, "Rotate or coversion failed: " + e.toString());
        }


    }

    private void startDirMonitor() {
        Log.d(TAG, "Starting dir monitor");

        // Getting the screenshot folder path
        // NOTE THAT ON DIFFERENT DEVICES THIS MIGHT BE DIFFERENT, ADJUST ACCORDINGLY
        File pix = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        final  File screenshotsDir = new File(pix, "Screenshots");

        // String the file system monitor
        fileObserver = new FileObserver(screenshotsDir.getPath(), FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String pathToAccessedFile) {
                Log.d(TAG, "Something happened with the files");
                // If the automatic OCR switch is on, passing this image to ocr
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(BackgroundOCRService.this);
                if(pathToAccessedFile == null || !preferences.getBoolean("ISON", true)) {
                    return;
                }
                // Running ocr on the image
                String fullPath = screenshotsDir.getPath() + "/" + pathToAccessedFile;
                Log.d(TAG, "Running ocr on " + fullPath);
                doOcr(fullPath);
            }
        };
        fileObserver.startWatching();


    }

    /**
     * Method shows notification to the user
     * @param title Title of the notification
     * @param text Text of the notification
     */
    private void showNotification(String title, String text) {
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.drawable.notification_icon)
                        .setContentTitle(title)
                        .setContentText(text);
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(1, mBuilder.build());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (fileObserver != null){
            fileObserver.stopWatching();
        }
        startDirMonitor();
    }
}
