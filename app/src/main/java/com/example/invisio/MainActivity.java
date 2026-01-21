package com.example.invisio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Main Activity with Emergency Features Integration
 */
public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = "InVisio-Main";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private LinearLayout btnPerceptiveVision;
    private LinearLayout btnReadMate;
    private LinearLayout btnMoneySense;
    private LinearLayout btnSocialInteraction;
    private LinearLayout btnPathGuide;
    private Button btnEmergency; // NEW: Emergency button
    private Button btnManageContacts; // NEW: Manage emergency contacts
    private TextView btnLogout;

    private LinearLayout voiceControlStatus;
    private TextView voiceStatusText;

    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private boolean ttsReady = false;
    private boolean isListening = false;

    // Emergency feature tracking
    private long volumeDownPressTime = 0;
    private long volumeUpPressTime = 0;
    private static final long VOLUME_HOLD_DURATION = 3000; // 3 seconds

    private enum Feature {
        PERCEPTIVE_VISION(0, "Perceptive Vision", DetectionActivity.class),
        READ_MATE(1, "Read Mate", DocReaderActivity.class),
        MONEY_SENSE(2, "Money Sense", null),
        SOCIAL_INTERACTION(3, "Social Interaction", null),
        PATH_GUIDE(4, "Path Guide", null),
        EMERGENCY(5, "Emergency", EmergencyActivity.class); // NEW

        final int index;
        final String name;
        final Class<?> activityClass;

        Feature(int index, String name, Class<?> activityClass) {
            this.index = index;
            this.name = name;
            this.activityClass = activityClass;
        }
    }

    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sessionManager = new SessionManager(this);

        if (!sessionManager.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        initializeViews();
        setupClickListeners();
        requestPermissions();

        // Start fall detection service
        startFallDetectionService();
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS
        };

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        } else {
            initializeVoiceControl();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean audioGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO) &&
                        grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    audioGranted = true;
                    break;
                }
            }

            if (audioGranted) {
                initializeVoiceControl();
            } else {
                Toast.makeText(this, "Microphone permission required for voice control", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initializeViews() {
        btnPerceptiveVision = findViewById(R.id.btnPerceptiveVision);
        btnReadMate = findViewById(R.id.btnReadMate);
        btnMoneySense = findViewById(R.id.btnMoneySense);
        btnPathGuide = findViewById(R.id.btnobstacledetection);
        btnEmergency = findViewById(R.id.btnEmergency); // NEW
        btnManageContacts = findViewById(R.id.btnManageContacts); // NEW
        btnLogout = findViewById(R.id.btnLogout);

        voiceControlStatus = findViewById(R.id.voiceControlStatus);
        voiceStatusText = findViewById(R.id.voiceStatusText);
    }

    private void setupClickListeners() {
        btnPerceptiveVision.setOnClickListener(v -> launchFeature(Feature.PERCEPTIVE_VISION));
        btnReadMate.setOnClickListener(v -> launchFeature(Feature.READ_MATE));

        // NEW: Emergency button - Large, red, prominent
        btnEmergency.setOnClickListener(v -> {
            speak("Opening emergency contacts");
            launchFeature(Feature.EMERGENCY);
        });

        // NEW: Manage emergency contacts
        btnManageContacts.setOnClickListener(v -> {
            speak("Opening contact management");
            startActivity(new Intent(MainActivity.this, ManageEmergencyContactsActivity.class));
        });

        if (voiceControlStatus != null) {
            voiceControlStatus.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN && !isListening) {
                    startListening();
                }
                return true;
            });
        }

        btnLogout.setOnClickListener(v -> {
            speak("Logging out");
            stopFallDetectionService(); // Stop fall detection on logout
            sessionManager.logoutUser();
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        });
    }

    private void startFallDetectionService() {
        Intent serviceIntent = new Intent(this, FallDetectionService.class);
        startService(serviceIntent);
        Log.i(TAG, "Fall detection service started");
    }

    private void stopFallDetectionService() {
        Intent serviceIntent = new Intent(this, FallDetectionService.class);
        stopService(serviceIntent);
        Log.i(TAG, "Fall detection service stopped");
    }

    // NEW: Handle volume button hold for emergency
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownPressTime = System.currentTimeMillis();
            checkVolumeHold();
            return true; // Consume event
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpPressTime = System.currentTimeMillis();
            checkVolumeHold();
            return true; // Consume event
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownPressTime = 0;
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpPressTime = 0;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void checkVolumeHold() {
        new android.os.Handler().postDelayed(() -> {
            long currentTime = System.currentTimeMillis();
            boolean downHeld = volumeDownPressTime > 0 && (currentTime - volumeDownPressTime) >= VOLUME_HOLD_DURATION;
            boolean upHeld = volumeUpPressTime > 0 && (currentTime - volumeUpPressTime) >= VOLUME_HOLD_DURATION;

            if (downHeld && upHeld) {
                // Both volume buttons held for 3 seconds
                speak("Emergency mode activated");
                launchFeature(Feature.EMERGENCY);
                volumeDownPressTime = 0;
                volumeUpPressTime = 0;
            }
        }, VOLUME_HOLD_DURATION);
    }

    private void initializeVoiceControl() {
        tts = new TextToSpeech(this, this);

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            Log.i(TAG, "SpeechRecognizer initialized");
        } else {
            Log.w(TAG, "Speech Recognition not available");
            Toast.makeText(this, "Speech Recognition not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            tts.setSpeechRate(0.9f);
            tts.setPitch(1.0f);
            ttsReady = true;
            Log.i(TAG, "TTS Ready");

            speak("InVisio ready. Say Emergency for urgent help, or choose a feature.");
            postDelayed(() -> startListening(), 1500);
        } else {
            Log.e(TAG, "TTS initialization failed");
            Toast.makeText(this, "Voice initialization failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void startListening() {
        if (speechRecognizer == null || isListening) {
            return;
        }

        isListening = true;
        updateVoiceStatus("Listening...");

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

        speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {}

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                updateVoiceStatus("Processing...");
            }

            @Override
            public void onError(int error) {
                isListening = false;
                postDelayed(() -> startListening(), 2000);
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    processVoiceInput(matches);
                } else {
                    postDelayed(() -> startListening(), 2000);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {}

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        try {
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            isListening = false;
            postDelayed(() -> startListening(), 2000);
        }
    }

    private void processVoiceInput(ArrayList<String> matches) {
        String bestMatch = matches.get(0).toLowerCase().trim();
        Log.i(TAG, "Voice input: " + bestMatch);

        Feature selectedFeature = null;

        // Check for emergency first
        if (bestMatch.contains("emergency") || bestMatch.contains("help") ||
                bestMatch.contains("sos") || bestMatch.contains("urgent")) {
            selectedFeature = Feature.EMERGENCY;
        } else {
            // Check other features
            for (Feature f : Feature.values()) {
                if (bestMatch.contains(f.name.toLowerCase())) {
                    selectedFeature = f;
                    break;
                }
            }
        }

        if (selectedFeature != null) {
            updateVoiceStatus("Selected: " + selectedFeature.name);
            speak("Opening " + selectedFeature.name);
            final Feature featureToLaunch = selectedFeature;
            postDelayed(() -> launchFeature(featureToLaunch), 800);
        } else {
            speak("Feature not recognized. Say Emergency for help, or choose another feature.");
            postDelayed(() -> startListening(), 2000);
        }
    }

    private void launchFeature(Feature feature) {
        if (feature.activityClass == null) {
            speak(feature.name + " is coming soon");
            postDelayed(() -> startListening(), 2000);
            return;
        }

        Intent intent = new Intent(MainActivity.this, feature.activityClass);
        startActivity(intent);
    }

    private void updateVoiceStatus(String text) {
        runOnUiThread(() -> {
            if (voiceControlStatus != null && voiceStatusText != null) {
                voiceControlStatus.setVisibility(android.view.View.VISIBLE);
                voiceStatusText.setText(text);
            }
        });
    }

    private void speak(String text) {
        if (ttsReady && text != null && !text.isEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_" + System.currentTimeMillis());
        }
    }

    private void postDelayed(Runnable r, long delayMs) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, delayMs);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ttsReady && speechRecognizer != null && !isListening) {
            startListening();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speechRecognizer != null && isListening) {
            speechRecognizer.stopListening();
            isListening = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopFallDetectionService();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}