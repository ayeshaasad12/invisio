package com.example.invisio;

public class EmergencyContact {
    private int id;
    private String name;
    private String phoneNumber;
    private String relationship; // Family, Friend, Doctor, etc.
    private int priority; // 1 = Primary, 2 = Secondary, etc.
    private int userId;

    public EmergencyContact() {}

    public EmergencyContact(int id, String name, String phoneNumber, String relationship, int priority, int userId) {
        this.id = id;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.relationship = relationship;
        this.priority = priority;
        this.userId = userId;
    }

    // Getters
    public int getId() { return id; }
    public String getName() { return name; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getRelationship() { return relationship; }
    public int getPriority() { return priority; }
    public int getUserId() { return userId; }

    // Setters
    public void setId(int id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public void setRelationship(String relationship) { this.relationship = relationship; }
    public void setPriority(int priority) { this.priority = priority; }
    public void setUserId(int userId) { this.userId = userId; }

    @Override
    public String toString() {
        return name + " (" + relationship + ") - " + phoneNumber;
    }
}