package com.example.gamechat.chat.bus;

public enum ChatEventKind {
    MESSAGE,
    PRESENCE,
    TYPING,
    DROP_USER,
    USER_JOINED,
    USER_LEFT,
    MESSAGE_DELETED,
    MESSAGE_EDITED,
    REACTION,
    DELIVERY,
    READ
}
