package land.euko.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OnlinePlayer {
    private final String nickname;
    private final String authKey;
    private final String uuid;
}