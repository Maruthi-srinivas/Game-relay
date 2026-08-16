package com.example.gamechat.room.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 32)
        String type,

        @Min(2)
        @Max(1000)
        Integer maxMembers
) {
}
