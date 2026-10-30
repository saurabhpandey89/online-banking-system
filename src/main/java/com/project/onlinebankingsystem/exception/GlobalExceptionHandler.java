package com.project.onlinebankingsystem.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        return uri.startsWith("/api/") || (accept != null && accept.contains("application/json"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public Object handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.NOT_FOUND.value(),
                    "Not Found",
                    ex.getMessage(),
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.NOT_FOUND.value());
        mav.addObject("error", "Not Found");
        mav.addObject("message", ex.getMessage());
        return mav;
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public Object handleInsufficientBalance(InsufficientBalanceException ex, HttpServletRequest request) {
        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Insufficient Balance",
                    ex.getMessage(),
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.BAD_REQUEST.value());
        mav.addObject("error", "Insufficient Balance");
        mav.addObject("message", ex.getMessage());
        return mav;
    }

    @ExceptionHandler(AccountSuspendedException.class)
    public Object handleAccountSuspended(AccountSuspendedException ex, HttpServletRequest request) {
        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.FORBIDDEN.value(),
                    "Account Suspended",
                    ex.getMessage(),
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.FORBIDDEN.value());
        mav.addObject("error", "Account Suspended");
        mav.addObject("message", ex.getMessage());
        return mav;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Bad Request",
                    ex.getMessage(),
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.BAD_REQUEST.value());
        mav.addObject("error", "Bad Request");
        mav.addObject("message", ex.getMessage());
        return mav;
    }

    @ExceptionHandler(SecurityException.class)
    public Object handleSecurityException(SecurityException ex, HttpServletRequest request) {
        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.FORBIDDEN.value(),
                    "Security Verification Failed",
                    ex.getMessage(),
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.FORBIDDEN.value());
        mav.addObject("error", "Security Verification Failed");
        mav.addObject("message", ex.getMessage());
        return mav;
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public Object handleMethodArgumentTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value for parameter '" + ex.getName() + "'. Please enter a valid numerical value.";
        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Invalid Parameter Value",
                    message,
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.BAD_REQUEST.value());
        mav.addObject("error", "Bad Request");
        mav.addObject("message", message);
        return mav;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public Object handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        String message = "Malformed request payload. Please verify input data types and numerical formats.";
        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Malformed Request",
                    message,
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.BAD_REQUEST.value());
        mav.addObject("error", "Bad Request");
        mav.addObject("message", message);
        return mav;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Validation Failed",
                    "Invalid input parameters",
                    request.getRequestURI(),
                    errors
            );
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.BAD_REQUEST.value());
        mav.addObject("error", "Validation Failed");
        mav.addObject("message", "Invalid input parameters");
        mav.addObject("validationErrors", errors);
        return mav;
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public Object handleAuthenticationException(org.springframework.security.core.AuthenticationException ex, HttpServletRequest request) {
        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.UNAUTHORIZED.value(),
                    "Unauthorized",
                    "Invalid username or password",
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
        }
        ModelAndView mav = new ModelAndView("redirect:/login?error");
        return mav;
    }

    @ExceptionHandler(Exception.class)
    public Object handleGlobalException(Exception ex, HttpServletRequest request) {
        // Log the exception
        ex.printStackTrace();

        if (isApiRequest(request)) {
            ErrorResponse error = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Internal Server Error",
                    ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred",
                    request.getRequestURI()
            );
            return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.addObject("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        mav.addObject("error", "Internal Server Error");
        mav.addObject("message", ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred");
        return mav;
    }
}
