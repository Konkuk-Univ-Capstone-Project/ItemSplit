package com.capstone.itemsplit.domain.roominvitetoken;

import com.capstone.itemsplit.domain.room.Room;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "room_invite_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Access(AccessType.FIELD)
public class RoomInviteToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "room_id", nullable = false, unique = true)
	private Room room;

	@Column(nullable = false, unique = true, length = 128)
	private String token;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private RoomInviteToken(Room room, String token, LocalDateTime expiresAt) {
		this.room = room;
		this.token = token;
		this.expiresAt = expiresAt;
	}

	public static RoomInviteToken create(Room room, String token, LocalDateTime expiresAt) {
		return new RoomInviteToken(room, token, expiresAt);
	}

	public void reissue(String token, LocalDateTime expiresAt) {
		this.token = token;
		this.expiresAt = expiresAt;
	}

	public boolean isExpired(LocalDateTime now) {
		return expiresAt.isBefore(now);
	}

	@PrePersist
	protected void onCreate() {
		this.createdAt = LocalDateTime.now();
	}

}
