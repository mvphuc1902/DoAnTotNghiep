package org.example.user_module.Service.impl;

import lombok.RequiredArgsConstructor;
import org.example.user_module.Entity.User;
import org.example.user_module.Repository.UserRepository;
import org.example.user_module.Service.UserService;
//import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
//    private final PasswordEncoder passwordEncoder;
    @Override
    public List<User> getAll() {
        return userRepository.findAll();

    }

    @Override
    public User createUser(User user) {
        if (user.getName() == null || user.getPassword() == null || user.getEmail() == null) {
            throw new IllegalArgumentException("Username or Password or Email is null");

        }

//        user.setPassword(passwordEncoder.encode(user.getPassword()));
        User newUser = userRepository.save(user);
        return newUser;

    }

}
