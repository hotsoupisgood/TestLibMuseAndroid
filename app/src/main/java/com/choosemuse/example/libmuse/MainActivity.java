/**
 * Example of using libmuse library on android.
 * Interaxon, Inc. 2016
 */

package com.choosemuse.example.libmuse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.lang.UnsatisfiedLinkError;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.choosemuse.libmuse.Accelerometer;
import com.choosemuse.libmuse.AnnotationData;
import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.ErrorType;
import com.choosemuse.libmuse.LibmuseVersion;
import com.choosemuse.libmuse.LogManager;
import com.choosemuse.libmuse.MessageType;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConfiguration;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseFileFactory;
import com.choosemuse.libmuse.MuseFileReader;
import com.choosemuse.libmuse.MuseFileWriter;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.choosemuse.libmuse.MuseVersion;
import com.choosemuse.libmuse.Result;
import com.choosemuse.libmuse.ResultLevel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

/**
 * In this simple example MainActivity implements a MuseHeadband listener
 * and updates UI when data from Muse is received. Similarly you can implement
 * listers for other data or register same listener to listen for different type
 * of data.
 * For simplicity we create Listeners as inner classes of MainActivity. We pass
 * reference to MainActivity as we want listeners to update UI thread in this
 * example app.
 *
 * Usage instructions:
 * 1. Enable bluetooth on your device
 * 2. Pair your device with muse
 * 3. Run this project
 * 4. Press Refresh. It should display all paired Muses in Spinner
 * 5. Make sure Muse headband is waiting for connection and press connect.
 * It may take up to 10 sec in some cases.
 * 6. You should see EEG and accelerometer data as well as connection status,
 * Version information and MuseElements (alpha, beta, theta, delta, gamma waves)
 * on the screen.
 */
public class MainActivity extends Activity implements OnClickListener {
    private final String TAG = "TestLibMuseAndroid";

    private ArrayAdapter<String> spinnerAdapter;
    private boolean dataTransmission = true;
    private MuseManagerAndroid manager = null;
    private Muse muse = null;
    private ConnectionListener connectionListener = null;
    private DataListener dataListener = null;

    // Note: the array lengths here are taken from the comments in
    // MuseDataPacketType, which specify 3 values for accelerometer and 6
    // values for EEG and EEG-derived packets.
    private final double[] eegBuffer = new double[6];
    private boolean eegStale = false;
    private final double[] alphaBuffer = new double[6];
    private boolean alphaStale = false;
    private final double[] accelBuffer = new double[3];
    private boolean accelStale = false;

    // helper methods to get different packet values
    private void getEegChannelValues(double[] buffer, MuseDataPacket p) {
        buffer[0] = p.getEegChannelValue(Eeg.EEG1);
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
        buffer[2] = p.getEegChannelValue(Eeg.EEG3);
        buffer[3] = p.getEegChannelValue(Eeg.EEG4);
        buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
        buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
    }

    private void getAccelValues(MuseDataPacket p) {
        accelBuffer[0] = p.getAccelerometerValue(Accelerometer.FORWARD_BACKWARD);
        accelBuffer[1] = p.getAccelerometerValue(Accelerometer.UP_DOWN);
        accelBuffer[2] = p.getAccelerometerValue(Accelerometer.LEFT_RIGHT);
    }

    private final Handler handler = new Handler();

    // We update the UI from this Runnable instead of in packet handlers
    // because packets come in at high frequency -- 220Hz or more for raw EEG
    // -- and it only makes sense to update the UI at about 60fps. The update
    // functions do some string allocation, so this reduces our memory
    // footprint and makes GC pauses less frequent/noticeable.
    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (eegStale) {
                updateEeg();
            }
            if (accelStale) {
                updateAccel();
            }
            if (alphaStale) {
                updateAlpha();
            }
            handler.postDelayed(tickUi, 1000 / 60);
        }
    };

    private final AtomicReference<MuseFileWriter> fileWriter = new AtomicReference<>();
    private final AtomicReference<Handler> fileHandler = new AtomicReference<>();

    private final Thread fileThread = new Thread() {
        @Override
        public void run() {
            Looper.prepare();
            fileHandler.set(new Handler());
            final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            fileWriter.set(MuseFileFactory.getMuseFileWriter(new File(dir, "new_muse_file.muse")));
            //Connect();
            Looper.loop();
        }
    };

    static {
        // Try to load our own all-in-one JNI lib. If it fails, rely on libmuse
        // to load libmuse_android.so for us.
        try {
            System.loadLibrary("TestLibMuseAndroid");
        } catch (UnsatisfiedLinkError e) {
        }
    }

    public void receiveMuseConnectionPacket(final MuseConnectionPacket p) {
        final ConnectionState current = p.getCurrentConnectionState();
        final String status = p.getPreviousConnectionState().toString().
            concat(" -> ").
            concat(current.toString());
        Log.i(TAG, status);
        if (p.getCurrentConnectionState() == ConnectionState.DISCONNECTED) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (muse != null) {
                        muse.runAsynchronously();
                    }
                }
            }, 20);
        }
        handler.post(new Runnable() {
            @Override public void run() {
                final TextView statusText = (TextView)findViewById(R.id.con_status);
                statusText.setText(status);
                final TextView museVersionText = (TextView)findViewById(R.id.version);
                if (current == ConnectionState.CONNECTED) {
                    final MuseVersion museVersion = muse.getMuseVersion();
                    final String version = museVersion.getFirmwareType().
                        concat(" - ").concat(museVersion.getFirmwareVersion()).
                        concat(" - ").concat(Integer.toString(museVersion.getProtocolVersion()));
                    museVersionText.setText(version);
                } else {
                    museVersionText.setText(R.string.undefined);
                }
            }
        });
    }

    public void receiveMuseDataPacket(final MuseDataPacket p) {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    fileWriter.get().addDataPacket(0, p);
                }
            });
        }
        final long n = p.valuesSize();
        switch (p.packetType()) {
            case EEG:
                assert(eegBuffer.length >= n);
                getEegChannelValues(eegBuffer,p);
                eegStale = true;
                break;
//            case ACCELEROMETER:
//                assert(accelBuffer.length >= n);
//                getAccelValues(p);
//                accelStale = true;
//                break;
            case ALPHA_RELATIVE:
                assert(alphaBuffer.length >= n);
                getEegChannelValues(alphaBuffer,p);
                alphaStale = true;
                break;
            default:
                break;
        }
    }

    private void updateAccel() {
        TextView acc_x = (TextView)findViewById(R.id.acc_x);
        TextView acc_y = (TextView)findViewById(R.id.acc_y);
        TextView acc_z = (TextView)findViewById(R.id.acc_z);
        acc_x.setText(String.format("%6.2f", accelBuffer[0]));
        acc_y.setText(String.format("%6.2f", accelBuffer[1]));
        acc_z.setText(String.format("%6.2f", accelBuffer[2]));
    }
//    public void Connect(){
//        try {
//            Socket SOCK = new Socket("10.0.0.3", 333);
//            PrintWriter pw = new PrintWriter(SOCK.getOutputStream());
//            pw.println("FROM ANDROID!");
//        } catch (UnknownHostException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        } catch (IOException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        }
//    }
//    Socket socket = null;
//    public String dstAddress;
//    public int dstPort;
    public Boolean avgB = true;
    private void updateEeg() {
        TextView tp9 = (TextView)findViewById(R.id.eeg_tp9);
        TextView fp1 = (TextView)findViewById(R.id.eeg_fp1);
        TextView fp2 = (TextView)findViewById(R.id.eeg_fp2);
        TextView tp10 = (TextView)findViewById(R.id.eeg_tp10);
        if(eegBuffer[0] > 1100){
            tp9.setText("1");
            avgB = true;
        }else {
            tp9.setText("0");
            avgB = false;
        }if(eegBuffer[1] > 1100){
            fp1.setText("1");
            avgB = true;
        }else {
            fp1.setText("0");
            avgB = false;
        }if (eegBuffer[2] > 1100) {
            fp2.setText("1");
            avgB = true;
        }else {
            fp2.setText("0");
            avgB = false;
        }if (eegBuffer[3] > 1100) {
            tp10.setText("1");
            avgB = true;
        }else {
            tp10.setText("0");
            avgB = false;
        }
//
//        try {
//            socket = new Socket(dstAddress, dstPort);
//
//            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(
//                    1024);
//            byte[] buffer = new byte[1024];
//
//            int bytesRead;
//            InputStream inputStream = socket.getInputStream();
//
//         /*
//          * notice: inputStream.read() will block if no data return
//          */
//            while ((bytesRead = inputStream.read(buffer)) != -1) {
//                byteArrayOutputStream.write(buffer, 0, bytesRead);
//            }
//
//        } catch (UnknownHostException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        } catch (IOException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        } finally {
//            //do stuff
//        }
    }
//    private String getBrainForce(double rawEeg[]){
//
//        d.format("%6.2f", eegBuffer[1]);
//    }

    private void updateAlpha() {
        TextView elem1 = (TextView)findViewById(R.id.elem1);
        elem1.setText(String.format("%6.2f", alphaBuffer[Eeg.EEG1.ordinal()]));
        TextView elem2 = (TextView)findViewById(R.id.elem2);
        elem2.setText(String.format("%6.2f", alphaBuffer[Eeg.EEG2.ordinal()]));
        TextView elem3 = (TextView)findViewById(R.id.elem3);
        elem3.setText(String.format("%6.2f", alphaBuffer[Eeg.EEG3.ordinal()]));
        TextView elem4 = (TextView)findViewById(R.id.elem4);
        elem4.setText(String.format("%6.2f", alphaBuffer[Eeg.EEG4.ordinal()]));
    }

    public void receiveMuseArtifactPacket(final MuseArtifactPacket p) {
    }

    public void museListChanged() {
        final ArrayList<Muse> list = manager.getMuses();
        spinnerAdapter.clear();
        for (Muse m : list) {
            spinnerAdapter.add(m.getName().concat(m.getMacAddress()));
        }
    }

    public MainActivity() {
        for (int i = 0; i < eegBuffer.length; ++i) {
            eegBuffer[i] = 0.0;
            alphaBuffer[i] = 0.0;
        }
        for (int i = 0; i < accelBuffer.length; ++i) {
            accelBuffer[i] = 0.0;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // XX this must come before other libmuse API calls; it loads the
        // library.
        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);

        fileThread.start();

        Log.i(TAG, "libmuse version=" + LibmuseVersion.instance().getString());

        // The ACCESS_COARSE_LOCATION permission is required to use the
        // BlueTooth LE library and must be requested at runtime for Android 6.0+
        // On an Android 6.0 device, the following code will display 2 dialogs,
        // one to provide context and the second to request the permission.
        // On an Android device running an earlier version, nothing is displayed
        // as the permission is granted from the manifest.
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            DialogInterface.OnClickListener buttonListener =
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which){
                            dialog.dismiss();
                            ActivityCompat.requestPermissions(MainActivity.this,
                                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                    0);
                        }
                    };

            AlertDialog introDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_dialog_title)
                    .setMessage(R.string.permission_dialog_description)
                    .setPositiveButton(R.string.permission_dialog_understand, buttonListener)
                    .create();
            introDialog.show();
        }

        WeakReference<MainActivity> weakActivity =
            new WeakReference<MainActivity>(this);
        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);
        manager.setMuseListener(new MuseL(weakActivity));

        setContentView(R.layout.activity_main);
        Button refreshButton = (Button) findViewById(R.id.refresh);
        refreshButton.setOnClickListener(this);
        Button connectButton = (Button) findViewById(R.id.connect);
        connectButton.setOnClickListener(this);
        Button disconnectButton = (Button) findViewById(R.id.disconnect);
        disconnectButton.setOnClickListener(this);
        Button pauseButton = (Button) findViewById(R.id.pause);
        pauseButton.setOnClickListener(this);

        spinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = (Spinner) findViewById(R.id.muses_spinner);
        musesSpinner.setAdapter(spinnerAdapter);

        handler.post(tickUi);
    }

    protected void onPause() {
        super.onPause();
        // It is important to call stopListening when the Activity is paused
        // to avoid a resource leak from the LibMuse library.
        manager.stopListening();
    }


    @Override
    public void onClick(View v) {
        Spinner musesSpinner = (Spinner) findViewById(R.id.muses_spinner);
        if (v.getId() == R.id.refresh) {
            manager.stopListening();
            manager.startListening();
        } else if (v.getId() == R.id.connect) {
            manager.stopListening();
            List<Muse> pairedMuses = manager.getMuses();
            if (pairedMuses.size() < 1 ||
                musesSpinner.getAdapter().getCount() < 1) {
                Log.w("MUSEAPP", "There is nothing to connect to");
            } else {
                muse = pairedMuses.get(musesSpinner.getSelectedItemPosition());
                muse.unregisterAllListeners();
                muse.registerConnectionListener(connectionListener);
                muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
                muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
                muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
                muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
                muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
                muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);
                muse.runAsynchronously();
            }            
        } else if (v.getId() == R.id.disconnect) {
            if (muse != null) {
                muse.unregisterAllListeners();
                muse.disconnect(false);

                Handler h = fileHandler.get();
                if (h != null) {
                    h.post(new Runnable() {
                        @Override public void run() {
                            MuseFileWriter w = fileWriter.get();
                            w.addAnnotationString(0, "Disconnect clicked");
                            w.flush();
                            w.close();
                        }
                    });
                }
            }
        } else if (v.getId() == R.id.pause) {
            dataTransmission = !dataTransmission;
            if (muse != null) {
                muse.enableDataTransmission(dataTransmission);
            }
        }
    }

    /*
     * Simple example of getting data from the "*.muse" file
     */
    private void playMuseFile(String name) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, name);
        final String tag = "Muse File Reader";
        if (!file.exists()) {
            Log.w(tag, "file doesn't exist");
            return;
        }
        MuseFileReader fileReader = MuseFileFactory.getMuseFileReader(file);
        Result res = fileReader.gotoNextMessage();
        while (res.getLevel() == ResultLevel.R_INFO && !res.getInfo().contains("EOF")) {
            MessageType type = fileReader.getMessageType();
            int id = fileReader.getMessageId();
            long timestamp = fileReader.getMessageTimestamp();
            Log.i(tag, "type: " + type.toString() +
                  " id: " + Integer.toString(id) +
                  " timestamp: " + String.valueOf(timestamp));
            switch(type) {
                case EEG: case BATTERY: case ACCELEROMETER: case QUANTIZATION: case GYRO:
                    MuseDataPacket packet = fileReader.getDataPacket();
                    Log.i(tag, "data packet: " + packet.packetType().toString());
                    break;
                case VERSION:
                    MuseVersion version = fileReader.getVersion();
                    Log.i(tag, "version" + version.getFirmwareType());
                    break;
                case CONFIGURATION:
                    MuseConfiguration config = fileReader.getConfiguration();
                    Log.i(tag, "config" + config.getBluetoothMac());
                    break;
                case ANNOTATION:
                    AnnotationData annotation = fileReader.getAnnotation();
                    Log.i(tag, "annotation" + annotation.getData());
                    break;
                default:
                    break;
            }
            res = fileReader.gotoNextMessage();
        }
    }


    // Listener translators follow.

    class ConnectionListener extends MuseConnectionListener {
        final WeakReference<MainActivity> activityRef;

        ConnectionListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
            activityRef.get().receiveMuseConnectionPacket(p);
        }
    }

    class DataListener extends MuseDataListener {
        final WeakReference<MainActivity> activityRef;

        DataListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
            activityRef.get().receiveMuseDataPacket(p);
        }

        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
            activityRef.get().receiveMuseArtifactPacket(p);
        }
    }


    class MuseL extends MuseListener {
        final WeakReference<MainActivity> activityRef;

        MuseL(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }
}
