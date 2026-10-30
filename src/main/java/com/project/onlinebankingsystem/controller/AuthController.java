package com.project.onlinebankingsystem.controller;

import com.project.onlinebankingsystem.config.JwtUtils;
import com.project.onlinebankingsystem.dto.LoginRequest;
import com.project.onlinebankingsystem.dto.LoginResponse;
import com.project.onlinebankingsystem.dto.RegisterRequest;
import com.project.onlinebankingsystem.dto.RegisterResponse;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtUtils jwtUtils;

    public AuthController(AuthenticationManager authenticationManager, UserService userService, JwtUtils jwtUtils) {
        this.authenticationManager = authenticationManager;
        this.userService = userService;
        this.jwtUtils = jwtUtils;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> registerUser(@Valid @RequestBody RegisterRequest registerRequest) {
        RegisterResponse response = userService.registerUser(registerRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String principalUsername = authentication.getName();
        String jwt = jwtUtils.generateToken(principalUsername);

        User user = userService.findByUsername(principalUsername)
                .or(() -> userService.findByIdentifier(loginRequest.getUsername()))
                .or(() -> userService.findByUsername(loginRequest.getUsername()))
                .orElseThrow();

        return ResponseEntity.ok(new LoginResponse(
                jwt,
                user.getUsername(),
                user.getRole().name(),
                user.getFullName()
        ));
    }
}
