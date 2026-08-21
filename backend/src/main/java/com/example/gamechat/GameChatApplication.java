package com.example.gamechat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GameChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(GameChatApplication.class, args);
    }
}
