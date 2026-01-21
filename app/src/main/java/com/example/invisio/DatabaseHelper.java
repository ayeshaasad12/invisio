package com.example.invisio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "InVisio.db";
    private static final int DATABASE_VERSION = 4; // Updated version

    // Users table
    private static final String TABLE_USERS = "users";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_FULL_NAME = "full_name";
    private static final String COLUMN_EMAIL = "email";
    private static final String COLUMN_PASSWORD = "password";
    private static final String COLUMN_CREATED_AT = "created_at";

    // Emergency Contacts table
    private static final String TABLE_EMERGENCY_CONTACTS = "emergency_contacts";
    private static final String COLUMN_CONTACT_ID = "id";
    private static final String COLUMN_CONTACT_NAME = "name";
    private static final String COLUMN_CONTACT_PHONE = "phone_number";
    private static final String COLUMN_CONTACT_RELATIONSHIP = "relationship";
    private static final String COLUMN_CONTACT_PRIORITY = "priority";
    private static final String COLUMN_CONTACT_USER_ID = "user_id";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create Users table
        String CREATE_USERS_TABLE = "CREATE TABLE " + TABLE_USERS + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_FULL_NAME + " TEXT NOT NULL,"
                + COLUMN_EMAIL + " TEXT UNIQUE NOT NULL,"
                + COLUMN_PASSWORD + " TEXT NOT NULL,"
                + COLUMN_CREATED_AT + " DATETIME DEFAULT CURRENT_TIMESTAMP"
                + ")";
        db.execSQL(CREATE_USERS_TABLE);

        // Create Emergency Contacts table
        String CREATE_CONTACTS_TABLE = "CREATE TABLE " + TABLE_EMERGENCY_CONTACTS + "("
                + COLUMN_CONTACT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_CONTACT_NAME + " TEXT NOT NULL,"
                + COLUMN_CONTACT_PHONE + " TEXT NOT NULL,"
                + COLUMN_CONTACT_RELATIONSHIP + " TEXT,"
                + COLUMN_CONTACT_PRIORITY + " INTEGER DEFAULT 999,"
                + COLUMN_CONTACT_USER_ID + " INTEGER NOT NULL,"
                + "FOREIGN KEY(" + COLUMN_CONTACT_USER_ID + ") REFERENCES " + TABLE_USERS + "(" + COLUMN_ID + ")"
                + ")";
        db.execSQL(CREATE_CONTACTS_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 4) {
            // Create emergency contacts table if upgrading from version 3 or earlier
            String CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE_EMERGENCY_CONTACTS + "("
                    + COLUMN_CONTACT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + COLUMN_CONTACT_NAME + " TEXT NOT NULL,"
                    + COLUMN_CONTACT_PHONE + " TEXT NOT NULL,"
                    + COLUMN_CONTACT_RELATIONSHIP + " TEXT,"
                    + COLUMN_CONTACT_PRIORITY + " INTEGER DEFAULT 999,"
                    + COLUMN_CONTACT_USER_ID + " INTEGER NOT NULL,"
                    + "FOREIGN KEY(" + COLUMN_CONTACT_USER_ID + ") REFERENCES " + TABLE_USERS + "(" + COLUMN_ID + ")"
                    + ")";
            db.execSQL(CREATE_CONTACTS_TABLE);
        }
    }

    // ==================== USER METHODS ====================

    public boolean registerUser(String fullName, String email, String password) {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_FULL_NAME, fullName);
            values.put(COLUMN_EMAIL, email.toLowerCase().trim());
            values.put(COLUMN_PASSWORD, password);
            long result = db.insert(TABLE_USERS, null, values);
            db.close();
            Log.d("DatabaseHelper", "Registration result: " + result);
            return result != -1;
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Registration error", e);
            return false;
        }
    }

    public boolean userExists(String email) {
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_USERS + " WHERE " + COLUMN_EMAIL + "=?",
                    new String[]{email.toLowerCase().trim()});
            boolean exists = cursor.getCount() > 0;
            cursor.close();
            db.close();
            return exists;
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Error checking user exists", e);
            return false;
        }
    }

    public boolean loginUser(String email, String password) {
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery("SELECT " + COLUMN_PASSWORD + " FROM " + TABLE_USERS + " WHERE " + COLUMN_EMAIL + "=?",
                    new String[]{email.toLowerCase().trim()});
            if (cursor.moveToFirst()) {
                String storedPassword = cursor.getString(0);
                cursor.close();
                db.close();
                return password.equals(storedPassword);
            }
            cursor.close();
            db.close();
            return false;
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Login error", e);
            return false;
        }
    }

    public User getUserDetails(String email) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = this.getReadableDatabase();
            cursor = db.rawQuery("SELECT * FROM " + TABLE_USERS + " WHERE " + COLUMN_EMAIL + "=?",
                    new String[]{email.toLowerCase().trim()});
            if (cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex(COLUMN_ID);
                int nameIndex = cursor.getColumnIndex(COLUMN_FULL_NAME);
                int emailIndex = cursor.getColumnIndex(COLUMN_EMAIL);
                int createdIndex = cursor.getColumnIndex(COLUMN_CREATED_AT);

                if (idIndex == -1 || nameIndex == -1 || emailIndex == -1 || createdIndex == -1) {
                    return null;
                }

                return new User(
                        cursor.getInt(idIndex),
                        cursor.getString(nameIndex),
                        cursor.getString(emailIndex),
                        cursor.getString(createdIndex)
                );
            }
            return null;
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Error getting user details", e);
            return null;
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }

    // ==================== EMERGENCY CONTACT METHODS ====================

    public long addEmergencyContact(int userId, String name, String phoneNumber, String relationship, int priority) {
        SQLiteDatabase db = null;
        try {
            db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_CONTACT_NAME, name);
            values.put(COLUMN_CONTACT_PHONE, phoneNumber);
            values.put(COLUMN_CONTACT_RELATIONSHIP, relationship);
            values.put(COLUMN_CONTACT_PRIORITY, priority);
            values.put(COLUMN_CONTACT_USER_ID, userId);

            long result = db.insert(TABLE_EMERGENCY_CONTACTS, null, values);
            Log.d("DatabaseHelper", "Emergency contact added: " + result);
            return result;
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Error adding emergency contact", e);
            return -1;
        } finally {
            if (db != null) db.close();
        }
    }

    public List<EmergencyContact> getEmergencyContacts(int userId) {
        List<EmergencyContact> contacts = new ArrayList<>();
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = this.getReadableDatabase();
            cursor = db.rawQuery(
                    "SELECT * FROM " + TABLE_EMERGENCY_CONTACTS +
                            " WHERE " + COLUMN_CONTACT_USER_ID + "=? ORDER BY " + COLUMN_CONTACT_PRIORITY + " ASC",
                    new String[]{String.valueOf(userId)}
            );

            if (cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex(COLUMN_CONTACT_ID);
                int nameIndex = cursor.getColumnIndex(COLUMN_CONTACT_NAME);
                int phoneIndex = cursor.getColumnIndex(COLUMN_CONTACT_PHONE);
                int relationshipIndex = cursor.getColumnIndex(COLUMN_CONTACT_RELATIONSHIP);
                int priorityIndex = cursor.getColumnIndex(COLUMN_CONTACT_PRIORITY);
                int userIdIndex = cursor.getColumnIndex(COLUMN_CONTACT_USER_ID);

                do {
                    EmergencyContact contact = new EmergencyContact(
                            cursor.getInt(idIndex),
                            cursor.getString(nameIndex),
                            cursor.getString(phoneIndex),
                            cursor.getString(relationshipIndex),
                            cursor.getInt(priorityIndex),
                            cursor.getInt(userIdIndex)
                    );
                    contacts.add(contact);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Error getting emergency contacts", e);
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
        return contacts;
    }

    public EmergencyContact getPrimaryContact(int userId) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = this.getReadableDatabase();
            cursor = db.rawQuery(
                    "SELECT * FROM " + TABLE_EMERGENCY_CONTACTS +
                            " WHERE " + COLUMN_CONTACT_USER_ID + "=? ORDER BY " + COLUMN_CONTACT_PRIORITY + " ASC LIMIT 1",
                    new String[]{String.valueOf(userId)}
            );

            if (cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex(COLUMN_CONTACT_ID);
                int nameIndex = cursor.getColumnIndex(COLUMN_CONTACT_NAME);
                int phoneIndex = cursor.getColumnIndex(COLUMN_CONTACT_PHONE);
                int relationshipIndex = cursor.getColumnIndex(COLUMN_CONTACT_RELATIONSHIP);
                int priorityIndex = cursor.getColumnIndex(COLUMN_CONTACT_PRIORITY);
                int userIdIndex = cursor.getColumnIndex(COLUMN_CONTACT_USER_ID);

                return new EmergencyContact(
                        cursor.getInt(idIndex),
                        cursor.getString(nameIndex),
                        cursor.getString(phoneIndex),
                        cursor.getString(relationshipIndex),
                        cursor.getInt(priorityIndex),
                        cursor.getInt(userIdIndex)
                );
            }
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Error getting primary contact", e);
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
        return null;
    }

    public boolean updateEmergencyContact(int contactId, String name, String phoneNumber, String relationship, int priority) {
        SQLiteDatabase db = null;
        try {
            db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_CONTACT_NAME, name);
            values.put(COLUMN_CONTACT_PHONE, phoneNumber);
            values.put(COLUMN_CONTACT_RELATIONSHIP, relationship);
            values.put(COLUMN_CONTACT_PRIORITY, priority);

            int rowsAffected = db.update(TABLE_EMERGENCY_CONTACTS, values,
                    COLUMN_CONTACT_ID + "=?", new String[]{String.valueOf(contactId)});
            return rowsAffected > 0;
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Error updating emergency contact", e);
            return false;
        } finally {
            if (db != null) db.close();
        }
    }

    public boolean deleteEmergencyContact(int contactId) {
        SQLiteDatabase db = null;
        try {
            db = this.getWritableDatabase();
            int rowsDeleted = db.delete(TABLE_EMERGENCY_CONTACTS,
                    COLUMN_CONTACT_ID + "=?", new String[]{String.valueOf(contactId)});
            return rowsDeleted > 0;
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Error deleting emergency contact", e);
            return false;
        } finally {
            if (db != null) db.close();
        }
    }

    public void debugPrintAllUsers() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = this.getReadableDatabase();
            cursor = db.rawQuery("SELECT * FROM " + TABLE_USERS, null);
            Log.d("DatabaseHelper", "=== ALL USERS ===");
            Log.d("DatabaseHelper", "Total users: " + cursor.getCount());
            if (cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex(COLUMN_ID);
                int nameIndex = cursor.getColumnIndex(COLUMN_FULL_NAME);
                int emailIndex = cursor.getColumnIndex(COLUMN_EMAIL);

                do {
                    Log.d("DatabaseHelper", "User: " + cursor.getInt(idIndex) + " | " +
                            cursor.getString(nameIndex) + " | " + cursor.getString(emailIndex));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("DatabaseHelper", "Debug error", e);
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }
}