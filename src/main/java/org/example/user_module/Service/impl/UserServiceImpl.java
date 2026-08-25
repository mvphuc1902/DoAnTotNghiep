package org.example.user_module.Service.impl;

import lombok.RequiredArgsConstructor;
import org.example.user_module.Entity.User;
import org.example.user_module.Repository.UserRepository;
import org.example.user_module.Service.UserService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    @Override
    public List<User> getAll() {
        return userRepository.findAll();

    }
}
