package com.teamplespace;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TeampleSpaceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TeampleSpaceApplication.class, args);
	}

	// 묶음 종료 시각은 서버가 요청을 받은 시각으로 판단한다
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
