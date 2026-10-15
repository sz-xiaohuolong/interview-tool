package cn.interview;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static ApiException bad(String message) { return new ApiException(400,"INVALID_INPUT",message); }
    public static ApiException missing(String message) { return new ApiException(404,"NOT_FOUND",message); }
    public static ApiException conflict(String message) { return new ApiException(409,"INVALID_STATE",message); }
    public static ApiException model(String message) { return new ApiException(502,"MODEL_ERROR",message); }
}
