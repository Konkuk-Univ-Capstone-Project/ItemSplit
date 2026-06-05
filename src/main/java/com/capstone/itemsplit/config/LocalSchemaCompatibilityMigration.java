package com.capstone.itemsplit.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalSchemaCompatibilityMigration implements ApplicationRunner {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public void run(ApplicationArguments args) {
		jdbcTemplate.execute("alter table if exists room_members alter column user_id drop not null");
		jdbcTemplate.execute("alter table if exists room_members alter column display_name drop not null");
		jdbcTemplate.execute("alter table if exists assignments alter column user_id drop not null");
		jdbcTemplate.execute("alter table if exists assignments alter column room_member_id drop not null");
		jdbcTemplate.execute("alter table if exists receipts add column if not exists payer_member_id bigint");
		jdbcTemplate.execute("""
			update receipts receipt
			set payer_member_id = room_member.id
			from room_members room_member
			where receipt.payer_member_id is null
			  and receipt.payer_id is not null
			  and receipt.room_id = room_member.room_id
			  and receipt.payer_id = room_member.user_id
			""");
	}

}
