package com.example.invisio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

/**
 * Emergency Screen - Accessible emergency contact calling
 * Activated by voice command "Emergency" or shake detection
 */
public class EmergencyActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = "EmergencyActivity";
    private static final int PERMISSION_REQUEST_CODE = 200;

    private LinearLayout contactsContainer;
    private Button btnCancel;
    private TextView tvEmergencyTitle;

    private DatabaseHelper dbHelper;
    private SessionManager sessionManager;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private List<EmergencyContact> emergencyContacts;
    private int userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        dbHelper = new DatabaseHelper(this);
        sessionManager = new SessionManager(this);

        // Get user ID
        String email = sessionManager.getUserEmail();
        User user = dbHelper.getUserDetails(email);
        if (user != null) {
            userId = user.getId();
        }

        initializeViews();
        checkPermissions();

        tts = new TextToSpeech(this, this);
    }

    private void initializeViews() {
        contactsContainer = findViewById(R.id.contactsContainer);
        btnCancel = findViewById(R.id.btnCancelEmergency);
        tvEmergencyTitle = findViewById(R.id.tvEmergencyTitle);

        btnCancel.setOnClickListener(v -> {
            speak("Emergency cancelled");
            finish();
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS},
                    PERMISSION_REQUEST_CODE);
        } else {
            loadEmergencyContacts();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadEmergencyContacts();
            } else {
                Toast.makeText(this, "Phone and SMS permissions required for emergency calls", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void loadEmergencyContacts() {
        emergencyContacts = dbHelper.getEmergencyContacts(userId);

        if (emergencyContacts.isEmpty()) {
            speak("No emergency contacts configured. Please add emergency contacts in settings.");
            Toast.makeText(this, "No emergency contacts found. Please add contacts first.", Toast.LENGTH_LONG).show();

            // Redirect to manage contacts
            new android.os.Handler().postDelayed(() -> {
                startActivity(new Intent(EmergencyActivity.this, ManageEmergencyContactsActivity.class));
                finish();
            }, 3000);
            return;
        }

        displayEmergencyContacts();
        announceContacts();
    }

    private void displayEmergencyContacts() {
        contactsContainer.removeAllViews();

        for (int i = 0; i < emergencyContacts.size(); i++) {
            final EmergencyContact contact = emergencyContacts.get(i);
            final int position = i + 1;

            // Create large, accessible contact button
            Button contactButton = new Button(this);
            contactButton.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    300 // Large height for accessibility
            ));

            String buttonText = position + ". " + contact.getName() + "\n" +
                    contact.getRelationship() + "\n" + contact.getPhoneNumber();
            contactButton.setText(buttonText);
            contactButton.setTextSize(24);
            contactButton.setPadding(40, 40, 40, 40);

            // Set colors for visibility
            if (contact.getPriority() == 1) {
                contactButton.setBackgroundColor(0xFFFF4444); // Red for primary
                contactButton.setTextColor(0xFFFFFFFF);
            } else {
                contactButton.setBackgroundColor(0xFFFF8800); // Orange for others
                contactButton.setTextColor(0xFFFFFFFF);
            }

            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) contactButton.getLayoutParams();
            params.setMargins(20, 20, 20, 20);
            contactButton.setLayoutParams(params);

            contactButton.setOnClickListener(v -> {
                speak("Calling " + contact.getName());
                makeEmergencyCall(contact);
            });

            contactsContainer.addView(contactButton);
        }
    }

    private void announceContacts() {
        StringBuilder announcement = new StringBuilder("Emergency mode activated. ");

        if (emergencyContacts.size() == 1) {
            announcement.append("Press the screen to call ")
                    .append(emergencyContacts.get(0).getName());
        } else {
            announcement.append("You have ").append(emergencyContacts.size()).append(" emergency contacts. ");
            for (int i = 0; i < Math.min(3, emergencyContacts.size()); i++) {
                announcement.append("Option ").append(i + 1).append(": ")
                        .append(emergencyContacts.get(i).getName()).append(". ");
            }
            announcement.append("Tap any contact to call.");
        }

        speak(announcement.toString());
    }

    private void makeEmergencyCall(EmergencyContact contact) {
        try {
            // Send SMS to all contacts first
            sendEmergencySMS();

            // Make phone call
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + contact.getPhoneNumber()));

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(callIntent);
                Log.i(TAG, "Emergency call initiated to: " + contact.getName());
            } else {
                Toast.makeText(this, "Call permission not granted", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making emergency call", e);
            Toast.makeText(this, "Error making call", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendEmergencySMS() {
        String message = "EMERGENCY: " + sessionManager.getUserName() + " has activated emergency mode in InVisio app. Please check on them immediately.";

        try {
            SmsManager smsManager = SmsManager.getDefault();
            for (EmergencyContact contact : emergencyContacts) {
                smsManager.sendTextMessage(contact.getPhoneNumber(), null, message, null, null);
                Log.i(TAG, "Emergency SMS sent to: " + contact.getName());
            }
            Toast.makeText(this, "Emergency alerts sent to all contacts", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error sending emergency SMS", e);
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            tts.setSpeechRate(1.0f);
            tts.setPitch(1.1f);
            ttsReady = true;
            Log.i(TAG, "TTS Ready for emergency announcements");
        } else {
            Log.e(TAG, "TTS initialization failed");
        }
    }

    private void speak(String text) {
        if (ttsReady && text != null && !text.isEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "EMERGENCY_TTS");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    @Override
    public void onBackPressed() {
        speak("Emergency cancelled");
        super.onBackPressed();
    }
}