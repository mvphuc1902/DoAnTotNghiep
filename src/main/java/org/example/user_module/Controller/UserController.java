package org.example.user_module.Controller;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.user_module.Entity.User;
import org.example.user_module.Service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;
    @GetMapping
    public List<User> getUsers() {
        return userService.getAll();
    }

}
