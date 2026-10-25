package cn.interview.client.jobsearch;

import cn.interview.entity.jobsearch.JobType;
import java.util.Locale;
import java.util.regex.Pattern;

/** 将来源明确提供的岗位类型和可解释的职位文本映射为统一类型。 */
final class JobTypeClassifier {
    private static final Pattern SUMMER = Pattern.compile("暑期实习|暑期|summer\\s*(?:intern(?:ship)?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAILY = Pattern.compile("日常实习|实习生|实习|intern(?:ship)?|寒假实习|长期实习|短期实习", Pattern.CASE_INSENSITIVE);
    private static final Pattern FULL_TIME = Pattern.compile("正式岗位|正式员工|全职|full[- ]?time", Pattern.CASE_INSENSITIVE);

    private JobTypeClassifier() {}

    static JobType classify(String title, String sourceType, String description) {
        String text = String.join(" ", value(title), value(sourceType), value(description)).toLowerCase(Locale.ROOT);
        if (SUMMER.matcher(text).find()) return JobType.SUMMER_INTERNSHIP;
        if (DAILY.matcher(text).find()) return JobType.DAILY_INTERNSHIP;
        if (FULL_TIME.matcher(text).find()) return JobType.FULL_TIME;
        return JobType.UNKNOWN;
    }

    private static String value(String text) { return text == null ? "" : text.strip(); }
}
