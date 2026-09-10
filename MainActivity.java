package com.songpyun.foldpiano;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int SR = 44100;
    private static final double[] FREQS = {
            523.25, 587.33, 659.26, 698.46, 783.99, 880.00, 987.77, 1046.50
    };
    private static final String[] NAMES = {"도", "레", "미", "파", "솔", "라", "시", "도↑"};
    private static final double ZONE_DEG = 20.0;  // 20도마다 한 음 (0~160도에 8음)
    private static final double MARGIN = 3.0;     // 경계에서 떨림 방지

    private SensorManager sensorManager;
    private Sensor accel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private short[][] notes;

    private final float[] g = new float[3];   // 필터된 중력 벡터
    private float[] g0 = null;                // 기준 자세(0도)
    private boolean hasSample = false;
    private boolean needCalib = true;
    private int zone = -1;

    private TextView noteView;
    private TextView infoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);

        noteView = new TextView(this);
        noteView.setTextSize(120);
        noteView.setTextColor(Color.WHITE);
        noteView.setGravity(Gravity.CENTER);
        noteView.setText("--");

        infoView = new TextView(this);
        infoView.setTextSize(18);
        infoView.setTextColor(Color.GRAY);
        infoView.setGravity(Gravity.CENTER);

        root.addView(noteView);
        root.addView(infoView);
        root.setOnClickListener(v -> needCalib = true);
        setContentView(root);

        notes = new short[FREQS.length][];
        for (int i = 0; i < FREQS.length; i++) {
            notes[i] = synth(FREQS[i]);
        }

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) {
            infoView.setText("가속도 센서를 찾을 수 없어요");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }
        needCalib = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!hasSample) {
            g[0] = event.values[0];
            g[1] = event.values[1];
            g[2] = event.values[2];
            hasSample = true;
        } else {
            for (int i = 0; i < 3; i++) {
                g[i] += 0.2f * (event.values[i] - g[i]);  // 저역 필터
            }
        }

        if (needCalib) {
            g0 = new float[]{g[0], g[1], g[2]};
            zone = -1;
            needCalib = false;
        }

        double dot = g[0] * g0[0] + g[1] * g0[1] + g[2] * g0[2];
        double n1 = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        double n0 = Math.sqrt(g0[0] * g0[0] + g0[1] * g0[1] + g0[2] * g0[2]);
        if (n1 < 1e-3 || n0 < 1e-3) return;
        double c = Math.max(-1.0, Math.min(1.0, dot / (n1 * n0)));
        double angle = Math.toDegrees(Math.acos(c));  // 기준 자세에서 기울어진 각도

        int newZone = (int) (angle / ZONE_DEG);
        if (newZone > 7) newZone = 7;

        if (zone == -1) {
            zone = newZone;  // 기준 잡을 땐 소리 없이 위치만 기억
        } else if (newZone != zone) {
            double lower = zone * ZONE_DEG - MARGIN;
            double upper = (zone + 1) * ZONE_DEG + MARGIN;
            if (angle < lower || (angle > upper && zone < 7)) {
                zone = newZone;
                play(zone);
            }
        }

        noteView.setText(NAMES[zone]);
        infoView.setText("접은 각도: 약 " + Math.round(angle) + "°\n\n"
                + "화면 면을 책상에 펼쳐 두고\n카메라 면을 들어 올려 연주하세요\n\n"
                + "화면을 탭하면 지금 자세가 0°가 돼요");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void play(int idx) {
        final short[] pcm = notes[idx];
        final AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        track.write(pcm, 0, pcm.length);
        track.play();
        handler.postDelayed(track::release, 1000);
    }

    private short[] synth(double f) {
        int n = (int) (SR * 0.7);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double y = Math.sin(2 * Math.PI * f * t) * Math.exp(-4 * t)
                    + 0.35 * Math.sin(2 * Math.PI * 2 * f * t) * Math.exp(-7 * t)
                    + 0.12 * Math.sin(2 * Math.PI * 3 * f * t) * Math.exp(-10 * t);
            double env = 1.0;
            if (i < 200) env = i / 200.0;
            if (i > n - 2200) env = (n - i) / 2200.0;
            out[i] = (short) (y * env * 0.37 * 32767);
        }
        return out;
    }
}
