package cn.interview.common;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.UnaryOperator;

/** 业务事务产生的创建结果；不进入公开响应正文。 */
public record CreationResult(ObjectNode body, boolean created) {
    public CreationResult map(UnaryOperator<ObjectNode> view) {
        return new CreationResult(view.apply(body), created);
    }
}
