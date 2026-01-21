package com.example.invisio;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    private EditText etEmail, etPassword;
    private Button btnLogin;
    private TextView tvCreateAccount, tvForgotPassword;
    private DatabaseHelper dbHelper;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // Hide action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        initViews();
        initListeners();

        dbHelper = new DatabaseHelper(this);
        sessionManager = new SessionManager(this);

        // Debug: Print all users in database
        dbHelper.debugPrintAllUsers();
    }

    private void initViews() {
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvCreateAccount = findViewById(R.id.tvCreateAccount);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
    }

    private void initListeners() {
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loginUser();
            }
        });

        tvCreateAccount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
            }
        });

        tvForgotPassword.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(LoginActivity.this, "Forgot password feature coming soon!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loginUser() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        // Debug logging
        Log.d("LoginActivity", "Login attempt - Email: '" + email + "', Password length: " + password.length());

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

        // Check if user exists first
        if (!dbHelper.userExists(email)) {
            Toast.makeText(this, "No account found with this email", Toast.LENGTH_SHORT).show();
            Log.d("LoginActivity", "User does not exist with email: " + email);
            return;
        }

        Log.d("LoginActivity", "User exists, attempting login...");

        if (dbHelper.loginUser(email, password)) {
            User user = dbHelper.getUserDetails(email);
            if (user != null) {
                sessionManager.createLoginSession(user.getEmail(), user.getFullName());
                Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show();
                Log.d("LoginActivity", "Login successful for user: " + user.getFullName());

                Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            } else {
                Toast.makeText(this, "Error retrieving user details", Toast.LENGTH_SHORT).show();
                Log.e("LoginActivity", "Failed to retrieve user details after successful login");
            }
        } else {
            Toast.makeText(this, "Invalid email or password", Toast.LENGTH_SHORT).show();
            Log.d("LoginActivity", "Login failed - Invalid credentials");
        }
    }
}