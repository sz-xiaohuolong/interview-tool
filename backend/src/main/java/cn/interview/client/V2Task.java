package cn.interview.client;

/** 专项训练的固定任务，不接受客户端指定任务名。 */
public enum V2Task {
    PRACTICE_EVALUATOR, PRACTICE_COMPARE, PRACTICE_PAIR_EVALUATOR, JD_DIAGNOSTIC, STORY_DRAFTER, DESIGN_STAGE_EVALUATOR, PROJECT_GAP_QUESTION, REVIEW_GAP_QUESTION;
    String file(){return name().toLowerCase(java.util.Locale.ROOT);}
    int tokens(){return (this==PROJECT_GAP_QUESTION||this==REVIEW_GAP_QUESTION)?1200:this==JD_DIAGNOSTIC||this==PRACTICE_PAIR_EVALUATOR?6500:4500;}
}
