package com.example.gamechat.common.exception;

public record ErrorResponse(String code, String message, int status) {
}
