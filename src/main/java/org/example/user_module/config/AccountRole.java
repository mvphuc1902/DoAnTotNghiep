package org.example.user_module.config;

public enum AccountRole {
    ADMIN, DIRECTOR, HR, MANAGER, LEADER, EMPLOYEE;

    public static AccountRole fromStored(String role) {
        return "USER".equals(role) ? EMPLOYEE : valueOf(role);
    }
}

