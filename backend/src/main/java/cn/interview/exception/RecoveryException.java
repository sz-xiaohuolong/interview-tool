package cn.interview.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/** 专项训练 恢复信息；不改变 原有功能 异常响应。 */
public class RecoveryException extends ApiException {
    final Map<String, Object> recovery = new LinkedHashMap<>();

    public RecoveryException(int status, String code, String message, String nextAction) {
        super(status, code, message);
        recovery.put("retryable", false);
        recovery.put("nextAction", nextAction);
        recovery.put("helpKey", "v2-recovery");
    }

    public RecoveryException detail(String field, Object value) { recovery.put(field, value); return this; }
    public static RecoveryException disabled() { return new RecoveryException(503, "V2_DISABLED", "专项训练 当前关闭，请使用原有面试，或按维护说明开启。", "CHECK_CONFIGURATION"); }
    public static RecoveryException conflict(String code, String message) { return new RecoveryException(409, code, message, "LOAD_LATEST"); }
    public static RecoveryException invalid(String message) { return new RecoveryException(400, "V2_INVALID_INPUT", message, "EDIT_INPUT"); }

    public Map<String,Object> recovery() {return java.util.Collections.unmodifiableMap(recovery);}

}
