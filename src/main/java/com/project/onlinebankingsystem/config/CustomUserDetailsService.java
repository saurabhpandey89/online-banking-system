package com.project.onlinebankingsystem.config;

import com.project.onlinebankingsystem.enums.Role;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.trim().isEmpty()) {
            throw new UsernameNotFoundException("Username is required");
        }

        String identifier = username.trim();
        String lower = identifier.toLowerCase();

        User user = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByIdentifier(identifier))
                .or(() -> {
                    if (lower.equals("admin") || lower.equals("administrator")
                            || lower.equals("saurabh") || lower.equals("saurabh pandey")
                            || lower.equals("saurabhpandey") || lower.equals("saurabh_pandey")) {
                        return userRepository.findByUsername("admin")
                                .or(() -> userRepository.findByUsername("saurabh"))
                                .or(() -> userRepository.findByUsername("saurabhpandey"))
                                .or(() -> userRepository.findByIdentifier("Saurabh Pandey"))
                                .or(() -> userRepository.findByUsername("shubham"));
                    } else if (lower.equals("tanishq") || lower.equals("tanisq")) {
                        return userRepository.findByUsername("tanishq")
                                .or(() -> userRepository.findByUsername("tanisq"));
                    } else if (lower.equals("rahul") || lower.equals("rahul kumar") || lower.equals("user")) {
                        return userRepository.findByUsername("rahul")
                                .or(() -> userRepository.findByUsername("user"));
                    } else if (lower.equals("shubham")) {
                        return userRepository.findByUsername("shubham")
                                .or(() -> userRepository.findByUsername("admin"))
                                .or(() -> userRepository.findByUsername("saurabh"));
                    }
                    return java.util.Optional.empty();
                })
                .orElseThrow(() -> new UsernameNotFoundException("User not found with identifier: " + identifier));

        // Administrators are never locked out of the system
        boolean accountNonLocked = (user.getRole() == Role.ROLE_ADMIN)
                || user.getLockoutUntil() == null
                || user.getLockoutUntil().isBefore(java.time.LocalDateTime.now());

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                true,
                true,
                true,
                accountNonLocked,
                Collections.singletonList(new SimpleGrantedAuthority(user.getRole().name()))
        );
    }
}
