package com.example.invisio;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class RegisterActivity extends AppCompatActivity {

    private EditText etFullName, etEmail, etPassword, etRetypePassword;
    private CheckBox cbTerms;
    private Button btnSignUp;
    private TextView tvAlreadyHaveAccount;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // Hide action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        initViews();
        initListeners();

        dbHelper = new DatabaseHelper(this);

        // Debug: Check database state
        Log.d("RegisterActivity", "Database initialized");
        dbHelper.debugPrintAllUsers();
    }

    private void initViews() {
        etFullName = findViewById(R.id.etFullName);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etRetypePassword = findViewById(R.id.etRetypePassword);
        cbTerms = findViewById(R.id.cbTerms);
        btnSignUp = findViewById(R.id.btnSignUp);
        tvAlreadyHaveAccount = findViewById(R.id.tvAlreadyHaveAccount);
    }

    private void initListeners() {
        btnSignUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                registerUser();
            }
        });

        tvAlreadyHaveAccount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void registerUser() {
        String fullName = etFullName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String retypePassword = etRetypePassword.getText().toString().trim();

        Log.d("RegisterActivity", "Registration attempt - Name: '" + fullName + "', Email: '" + email + "', Password length: " + password.length());

        if (TextUtils.isEmpty(fullName)) {
            etFullName.setError("Full name is required");
            etFullName.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(email)) {
            etEmail.setError("Email is required");
            etEmail.requestFocus();
            return;
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Please enter a valid email");
            etEmail.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password)) {
            etPassword.setError("Password is required");
            etPassword.requestFocus();
            return;
        }

        if (password.length() < 6) {
            etPassword.setError("Password must be at least 6 characters");
            etPassword.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(retypePassword)) {
            etRetypePassword.setError("Please retype password");
            etRetypePassword.requestFocus();
            return;
        }

        if (!password.equals(retypePassword)) {
            etRetypePassword.setError("Passwords do not match");
            etRetypePassword.requestFocus();
            return;
        }

        if (!cbTerms.isChecked()) {
            Toast.makeText(this, "Please agree to Terms & Privacy", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if user already exists
        Log.d("RegisterActivity", "Checking if user exists...");
        if (dbHelper.userExists(email)) {
            etEmail.setError("User already exists with this email");
            etEmail.requestFocus();
            Log.d("RegisterActivity", "User already exists with email: " + email);
            return;
        }

        Log.d("RegisterActivity", "User doesn't exist, proceeding with registration...");

        // Attempt registration
        boolean registrationSuccess = dbHelper.registerUser(fullName, email, password);
        Log.d("RegisterActivity", "Registration result: " + registrationSuccess);

        if (registrationSuccess) {
            // Verify user was actually created
            boolean userNowExists = dbHelper.userExists(email);
            Log.d("RegisterActivity", "User exists after registration: " + userNowExists);

            // Debug: Print all users after registration
            dbHelper.debugPrintAllUsers();

            // Test login immediately
            testLoginAfterRegistration(email, password);

            Toast.makeText(this, "Registration successful! Please login.", Toast.LENGTH_LONG).show();
            finish();
        } else {
            Log.e("RegisterActivity", "Registration failed for unknown reason");
            Toast.makeText(this, "Registration failed. Please try again.", Toast.LENGTH_SHORT).show();
        }
    }

    // Test login immediately after registration
    private void testLoginAfterRegistration(String email, String password) {
        Log.d("RegisterActivity", "Testing login immediately after registration...");

        // Create a new DatabaseHelper instance to ensure fresh connection
        DatabaseHelper testDbHelper = new DatabaseHelper(this);

        boolean loginTest = testDbHelper.loginUser(email, password);
        Log.d("RegisterActivity", "Immediate login test result: " + loginTest);

        if (loginTest) {
            Log.d("RegisterActivity", "✓ Registration and login test PASSED");
        } else {
            Log.e("RegisterActivity", "✗ Registration succeeded but login test FAILED");
            // Additional debugging
            boolean userExists = testDbHelper.userExists(email);
            Log.e("RegisterActivity", "User exists check: " + userExists);
        }
    }
}