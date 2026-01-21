package com.example.invisio;

public class User {
    private int id;
    private String fullName;
    private String email;
    private String createdAt;

    public User(int id, String fullName, String email, String createdAt) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.createdAt = createdAt;
    }

    // Getters
    public int getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getCreatedAt() { return createdAt; }

    // Setters
    public void setId(int id) { this.id = id; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setEmail(String email) { this.email = email; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}