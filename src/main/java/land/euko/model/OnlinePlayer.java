package land.euko.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OnlinePlayer {
    private String nickname;
    private String authKey;
}