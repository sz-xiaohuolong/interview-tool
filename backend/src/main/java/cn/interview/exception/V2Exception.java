package cn.interview.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/** V2 恢复信息；不改变 V1 异常响应。 */
public class V2Exception extends ApiException {
    final Map<String, Object> recovery = new LinkedHashMap<>();

    public V2Exception(int status, String code, String message, String nextAction) {
        super(status, code, message);
        recovery.put("retryable", false);
        recovery.put("nextAction", nextAction);
        recovery.put("helpKey", "v2-recovery");
    }

    public V2Exception detail(String field, Object value) { recovery.put(field, value); return this; }
    public static V2Exception disabled() { return new V2Exception(503, "V2_DISABLED", "V2 当前关闭，请使用原有面试，或按维护说明开启。", "CHECK_CONFIGURATION"); }
    public static V2Exception conflict(String code, String message) { return new V2Exception(409, code, message, "LOAD_LATEST"); }
    public static V2Exception invalid(String message) { return new V2Exception(400, "V2_INVALID_INPUT", message, "EDIT_INPUT"); }

    public Map<String,Object> recovery() {return java.util.Collections.unmodifiableMap(recovery);}

}
