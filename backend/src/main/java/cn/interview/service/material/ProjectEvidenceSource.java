package cn.interview.service.material;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 由独立会话模块提供只读快照，原后端不依赖扩展模块。 */
public interface ProjectEvidenceSource {
    boolean supports(String sourceKind);
    ObjectNode source(String id);
}
