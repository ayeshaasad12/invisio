package com.example.invisio;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Manage Emergency Contacts Activity
 * Add, edit, and delete emergency contacts
 */
public class ManageEmergencyContactsActivity extends AppCompatActivity {

    private LinearLayout contactsList;
    private Button btnAddContact, btnBack;
    private EditText etContactName, etContactPhone;
    private Spinner spinnerRelationship, spinnerPriority;

    private DatabaseHelper dbHelper;
    private SessionManager sessionManager;
    private int userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_emergency_contacts);

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
        setupSpinners();
        loadContacts();
    }

    private void initializeViews() {
        contactsList = findViewById(R.id.contactsList);
        btnAddContact = findViewById(R.id.btnAddContact);
        btnBack = findViewById(R.id.btnBackFromContacts);
        etContactName = findViewById(R.id.etContactName);
        etContactPhone = findViewById(R.id.etContactPhone);
        spinnerRelationship = findViewById(R.id.spinnerRelationship);
        spinnerPriority = findViewById(R.id.spinnerPriority);

        btnAddContact.setOnClickListener(v -> addContact());
        btnBack.setOnClickListener(v -> finish());
    }

    private void setupSpinners() {
        // Relationship spinner
        String[] relationships = {"Family", "Friend", "Doctor", "Neighbor", "Caregiver", "Other"};
        ArrayAdapter<String> relationshipAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, relationships);
        spinnerRelationship.setAdapter(relationshipAdapter);

        // Priority spinner
        String[] priorities = {"1 - Primary (Most Important)", "2 - Secondary", "3 - Tertiary", "4 - Other"};
        ArrayAdapter<String> priorityAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, priorities);
        spinnerPriority.setAdapter(priorityAdapter);
    }

    private void addContact() {
        String name = etContactName.getText().toString().trim();
        String phone = etContactPhone.getText().toString().trim();
        String relationship = spinnerRelationship.getSelectedItem().toString();
        int priority = spinnerPriority.getSelectedItemPosition() + 1;

        if (TextUtils.isEmpty(name)) {
            etContactName.setError("Name is required");
            return;
        }

        if (TextUtils.isEmpty(phone)) {
            etContactPhone.setError("Phone number is required");
            return;
        }

        // Basic phone validation
        if (phone.length() < 10) {
            etContactPhone.setError("Enter valid phone number");
            return;
        }

        long result = dbHelper.addEmergencyContact(userId, name, phone, relationship, priority);

        if (result != -1) {
            Toast.makeText(this, "Emergency contact added", Toast.LENGTH_SHORT).show();
            etContactName.setText("");
            etContactPhone.setText("");
            spinnerPriority.setSelection(0);
            loadContacts();
        } else {
            Toast.makeText(this, "Failed to add contact", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadContacts() {
        contactsList.removeAllViews();
        List<EmergencyContact> contacts = dbHelper.getEmergencyContacts(userId);

        if (contacts.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("No emergency contacts added yet.\nAdd at least one contact for emergency situations.");
            emptyView.setTextSize(16);
            emptyView.setPadding(20, 40, 20, 40);
            emptyView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            contactsList.addView(emptyView);
            return;
        }

        for (EmergencyContact contact : contacts) {
            LinearLayout contactCard = createContactCard(contact);
            contactsList.addView(contactCard);
        }
    }

    private LinearLayout createContactCard(EmergencyContact contact) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(30, 30, 30, 30);
        card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(20, 20, 20, 20);
        card.setLayoutParams(params);

        // Contact info
        TextView tvInfo = new TextView(this);
        String priorityText = contact.getPriority() == 1 ? "PRIMARY" : "Priority " + contact.getPriority();
        tvInfo.setText(priorityText + "\n" +
                contact.getName() + "\n" +
                contact.getPhoneNumber() + "\n" +
                contact.getRelationship());
        tvInfo.setTextSize(16);
        tvInfo.setPadding(0, 0, 0, 20);
        if (contact.getPriority() == 1) {
            tvInfo.setTextColor(0xFFFF0000); // Red for primary
        }
        card.addView(tvInfo);

        // Buttons
        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);

        Button btnDelete = new Button(this);
        btnDelete.setText("Delete");
        btnDelete.setBackgroundColor(0xFFFF4444);
        btnDelete.setTextColor(0xFFFFFFFF);
        btnDelete.setOnClickListener(v -> confirmDelete(contact));

        buttonLayout.addView(btnDelete);
        card.addView(buttonLayout);

        return card;
    }

    private void confirmDelete(EmergencyContact contact) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Contact")
                .setMessage("Are you sure you want to delete " + contact.getName() + " from emergency contacts?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (dbHelper.deleteEmergencyContact(contact.getId())) {
                        Toast.makeText(this, "Contact deleted", Toast.LENGTH_SHORT).show();
                        loadContacts();
                    } else {
                        Toast.makeText(this, "Failed to delete contact", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}