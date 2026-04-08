package com.capstone.itemsplit.ping;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import jakarta.validation.ValidationException;
import java.util.NoSuchElementException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-errors")
class TestErrorController {

	@GetMapping("/not-found")
	void notFound() {
		throw new ApiException(ErrorCode.NOT_FOUND, "Ping target was not found.");
	}

	@GetMapping("/unauthorized")
	void unauthorized() {
		throw new BadCredentialsException("Authentication is required.");
	}

	@GetMapping("/forbidden")
	void forbidden() {
		throw new AccessDeniedException("Ping access is forbidden.");
	}

	@GetMapping("/validation")
	void validation() {
		throw new ValidationException("Invalid ping request.");
	}

	@GetMapping("/missing")
	void missing() {
		throw new NoSuchElementException("Ping target was not found.");
	}

	@GetMapping("/internal")
	void internal() {
		throw new IllegalStateException("Unexpected ping failure.");
	}

}
