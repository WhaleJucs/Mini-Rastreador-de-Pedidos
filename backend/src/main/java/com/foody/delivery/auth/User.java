package com.foody.delivery.auth;

record User(long id, String name, String email, String passwordHash) {
}
