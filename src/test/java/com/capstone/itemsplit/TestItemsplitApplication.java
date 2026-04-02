package com.capstone.itemsplit;

import org.springframework.boot.SpringApplication;

public class TestItemsplitApplication {

	public static void main(String[] args) {
		SpringApplication.from(ItemsplitApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
