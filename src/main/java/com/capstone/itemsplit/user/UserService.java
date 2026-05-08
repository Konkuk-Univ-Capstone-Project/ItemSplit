package com.capstone.itemsplit.user;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

	private final UserRepository userRepository;

	@Transactional
	public User createUser(String email, String encodedPassword, String nickname) {
		return userRepository.save(User.create(email, encodedPassword, nickname));
	}

	public boolean existsByEmail(String email) {
		return userRepository.existsByEmail(email);
	}

	public User getByEmail(String email) {
		return userRepository.findByEmail(email)
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Email or password is incorrect."));
	}

	public User getById(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User was not found."));
	}

}
